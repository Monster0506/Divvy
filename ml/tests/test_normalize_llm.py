import json

import httpx
import pytest
from fastapi.testclient import TestClient

from divvy_ml.app import app
from divvy_ml.normalize import RulesNormalizer, normalize_product_name
from divvy_ml.normalize.configured import configured_normalizer
from divvy_ml.normalize.llm import MAX_NAME_LENGTH, LlmNameNormalizer, clean_name
from divvy_ml.normalize.router import get_normalizer
from divvy_ml.qwen_config import QwenConfig

KEY = "sk-test-secret-key"


def reply(names: list[tuple[int, str]]) -> httpx.Response:
    content = json.dumps({"names": [{"i": i, "name": name} for i, name in names]})
    return httpx.Response(200, json={"choices": [{"message": {"content": content}}]})


def normalizer(handler, **config) -> tuple[LlmNameNormalizer, list[httpx.Request]]:
    seen: list[httpx.Request] = []

    def record(request: httpx.Request) -> httpx.Response:
        seen.append(request)
        return handler(request)

    client = httpx.Client(transport=httpx.MockTransport(record))
    return LlmNameNormalizer(QwenConfig(api_key=KEY, **config), client), seen


def answer_from(table: dict[str, str]):
    def handler(request: httpx.Request) -> httpx.Response:
        lines = json.loads(json.loads(request.content)["messages"][1]["content"])
        return reply([(line["i"], table.get(line["printed"], line["draft"])) for line in lines])

    return handler


# --- what is asked ----------------------------------------------------------------------------


def test_one_call_covers_every_new_name_and_sends_the_rule_draft():
    llm, seen = normalizer(answer_from({"tbc rncln 24": "tbc raccoon cleaner"}))

    llm.normalize_many(["TBC RNCLN 24", "GV 40PK"])

    assert len(seen) == 1
    body = json.loads(seen[0].content)
    lines = json.loads(body["messages"][1]["content"])
    assert lines == [
        {"i": 0, "printed": "tbc rncln 24", "draft": "tbc rncln 24"},
        {"i": 1, "printed": "gv 40pk", "draft": "great value"},
    ]
    assert str(seen[0].url) == "https://maas.qwencloudapi.com/compatible-mode/v1/chat/completions"
    assert seen[0].headers["authorization"] == f"Bearer {KEY}"
    assert body["enable_thinking"] is False
    assert body["response_format"]["json_schema"]["strict"] is True


def test_the_text_model_can_differ_from_the_vision_model():
    llm, seen = normalizer(answer_from({}), model="vision", text_model="small-text")

    llm.normalize_many(["GV 40PK"])

    assert json.loads(seen[0].content)["model"] == "small-text"


def test_the_prompt_forbids_guessing():
    llm, seen = normalizer(answer_from({}))

    llm.normalize_many(["GV 40PK"])

    system = json.loads(seen[0].content)["messages"][0]["content"]
    assert "Never guess a product" in system
    assert "keep it as printed" in system


# --- answers ----------------------------------------------------------------------------------


def test_the_llm_name_replaces_the_rule_draft():
    llm, _ = normalizer(answer_from({"gv 40pk": "great value purified water"}))

    assert llm.normalize_many(["GV 40PK"]) == ["great value purified water"]


def test_answers_are_lowercased_and_tidied():
    llm, _ = normalizer(lambda r: reply([(0, "  Great   Value WATER ")]))

    assert llm.normalize_many(["GV 40PK"]) == ["great value water"]


def test_names_seen_before_are_not_asked_again_and_duplicates_are_sent_once():
    llm, seen = normalizer(answer_from({"gv 40pk": "great value water"}))

    first = llm.normalize_many(["GV 40PK", "gv  40pk", "GV 40PK"])
    second = llm.normalize_many(["GV 40PK"])

    assert first == ["great value water"] * 3
    assert second == ["great value water"]
    assert len(seen) == 1
    assert len(json.loads(json.loads(seen[0].content)["messages"][1]["content"])) == 1


def test_a_cache_that_is_full_forgets_the_oldest_name():
    client = httpx.Client(transport=httpx.MockTransport(answer_from({})))
    llm = LlmNameNormalizer(QwenConfig(api_key=KEY), client, cache_size=2)

    llm.normalize_many(["a1", "b1", "c1"])

    assert list(llm._cache) == ["b1", "c1"]


def test_a_blank_line_is_never_sent():
    llm, seen = normalizer(answer_from({}))

    assert llm.normalize_many(["   "]) == [""]
    assert seen == []


def test_large_receipts_are_sent_in_batches():
    llm, seen = normalizer(answer_from({}))

    llm.normalize_many([f"item{n}" for n in range(130)])

    assert len(seen) == 3


# --- fallbacks --------------------------------------------------------------------------------


@pytest.mark.parametrize("status", [401, 429, 500])
def test_an_error_status_leaves_the_rule_names(status):
    llm, _ = normalizer(lambda r: httpx.Response(status, text=f"nope {KEY}"))

    assert llm.normalize_many(["BNLS CK BRST"]) == ["boneless chicken breast"]


def test_a_timeout_leaves_the_rule_names_and_is_retried_next_time():
    calls = []

    def flaky(request):
        calls.append(1)
        if len(calls) == 1:
            raise httpx.ReadTimeout("slow", request=request)
        return reply([(0, "boneless chicken breast fillet")])

    llm, _ = normalizer(flaky)

    assert llm.normalize_many(["BNLS CK BRST"]) == ["boneless chicken breast"]
    assert llm.normalize_many(["BNLS CK BRST"]) == ["boneless chicken breast fillet"]


@pytest.mark.parametrize(
    "content", ["", "not json", "[]", '{"names": "x"}', '{"names": [{"name": "x"}]}']
)
def test_an_unusable_answer_leaves_the_rule_names(content):
    llm, _ = normalizer(
        lambda r: httpx.Response(200, json={"choices": [{"message": {"content": content}}]})
    )

    assert llm.normalize_many(["WHT BREAD"]) == ["white bread"]


def test_a_missing_or_bad_entry_falls_back_for_that_item_only():
    llm, _ = normalizer(lambda r: reply([(1, "x" * 200)]))

    assert llm.normalize_many(["WHT BREAD", "GRD BF"]) == ["white bread", "ground beef"]


@pytest.mark.parametrize(
    ("candidate", "expected"),
    [
        ("Organic Milk", "organic milk"),
        ("", None),
        ("   ", None),
        ("12345", None),
        ("x" * (MAX_NAME_LENGTH + 1), None),
        (" ".join(["word"] * 11), None),
        (42, None),
        (None, None),
    ],
)
def test_only_name_shaped_answers_are_accepted(candidate, expected):
    assert clean_name(candidate) == expected


# --- configuration and the endpoint -----------------------------------------------------------


def test_without_a_key_or_with_the_switch_off_only_rules_are_used(monkeypatch):
    configured_normalizer.cache_clear()
    monkeypatch.delenv("QWEN_API_KEY", raising=False)
    monkeypatch.delenv("QWEN_API_KEY_FILE", raising=False)
    assert isinstance(configured_normalizer(), RulesNormalizer)

    configured_normalizer.cache_clear()
    monkeypatch.setenv("QWEN_API_KEY", KEY)
    monkeypatch.setenv("QWEN_LLM_NORMALIZE", "false")
    assert isinstance(configured_normalizer(), RulesNormalizer)

    configured_normalizer.cache_clear()
    monkeypatch.setenv("QWEN_LLM_NORMALIZE", "true")
    assert isinstance(configured_normalizer(), LlmNameNormalizer)
    configured_normalizer.cache_clear()


def test_the_rules_normalizer_matches_the_function():
    assert RulesNormalizer().normalize_many(["ORG WHL MLK 1GAL"]) == [
        normalize_product_name("ORG WHL MLK 1GAL")
    ]


class FakeNormalizer:
    def normalize_many(self, raws):
        return [raw.lower() + "!" for raw in raws]


@pytest.fixture
def client():
    app.dependency_overrides[get_normalizer] = lambda: FakeNormalizer()
    yield TestClient(app)
    app.dependency_overrides.clear()


def test_the_endpoint_returns_one_name_per_printed_line(client):
    response = client.post("/normalize", json={"names": ["GV 40PK", "MS ICE BIN"]})

    assert response.status_code == 200
    assert response.json() == {
        "names": [
            {"printed": "GV 40PK", "name": "gv 40pk!"},
            {"printed": "MS ICE BIN", "name": "ms ice bin!"},
        ]
    }


@pytest.mark.parametrize(
    "payload", [{}, {"names": []}, {"names": ["x"] * 201}, {"names": ["x" * 201]}, {"names": [1]}]
)
def test_the_endpoint_rejects_bad_requests(client, payload):
    assert client.post("/normalize", json=payload).status_code == 422


def test_the_endpoint_works_with_rules_when_nothing_is_configured(monkeypatch):
    configured_normalizer.cache_clear()
    monkeypatch.delenv("QWEN_API_KEY", raising=False)
    monkeypatch.delenv("QWEN_API_KEY_FILE", raising=False)

    response = TestClient(app).post("/normalize", json={"names": ["ORG WHL MLK 1GAL"]})

    assert response.json()["names"][0]["name"] == "organic whole milk"
    configured_normalizer.cache_clear()
