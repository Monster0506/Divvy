"""Readable product names from an LLM, with the rule-based name as the draft and the fallback.

One text-only call covers every name not seen before. A failed call, or an answer that does not pass
the checks below, leaves the rule-based name in place, so normalising never fails a request.
"""

import json
import logging
import re
import threading
from collections import OrderedDict
from typing import TYPE_CHECKING, Any

import httpx

from divvy_ml.normalize.normalizer import normalize_product_name

if TYPE_CHECKING:
    from divvy_ml.qwen_config import QwenConfig

log = logging.getLogger(__name__)

SYSTEM_PROMPT = """You turn abbreviated retail receipt lines into readable product names.

Rules:

1. Answer in lowercase with full words, for example "ORG WHL MLK 1GAL" is "organic whole milk".
2. Remove sizes, weights, counts, prices, product codes and tax flags.
3. Expand store brand prefixes, for example GV is "great value" and MS is "mainstays".
4. A draft name made by simple rules is given. Keep it when it is already right.
5. If you do not know what an abbreviation means, keep it as printed. Never guess a product.
6. Do not add words the line does not imply. Do not add a brand that is not there.
7. Return exactly one name for every input, matched by its number i."""

NAMES_SCHEMA: dict[str, Any] = {
    "type": "object",
    "properties": {
        "names": {
            "type": "array",
            "items": {
                "type": "object",
                "properties": {"i": {"type": "integer"}, "name": {"type": "string"}},
                "required": ["i", "name"],
                "additionalProperties": False,
            },
        }
    },
    "required": ["names"],
    "additionalProperties": False,
}

MAX_NAME_LENGTH = 80
MAX_WORDS = 10
BATCH_SIZE = 60
_SPACES = re.compile(r"\s+")


def clean_name(candidate: Any) -> str | None:
    """The answer for one item if it looks like a product name, else None."""
    if not isinstance(candidate, str):
        return None
    name = _SPACES.sub(" ", candidate.strip().lower())
    if not name or len(name) > MAX_NAME_LENGTH or len(name.split()) > MAX_WORDS:
        return None
    return name if any(ch.isalpha() for ch in name) else None


class LlmNameNormalizer:
    def __init__(
        self, config: "QwenConfig", client: httpx.Client | None = None, cache_size: int = 5000
    ) -> None:
        self._config = config
        self._client = client or httpx.Client(timeout=config.timeout_seconds)
        self._cache: OrderedDict[str, str] = OrderedDict()
        self._cache_size = cache_size
        self._lock = threading.Lock()

    def normalize_many(self, raws: list[str]) -> list[str]:
        drafts = [normalize_product_name(raw) for raw in raws]
        unseen = self._unseen(raws, drafts)
        if unseen:
            self._ask(unseen)
        with self._lock:
            return [
                self._cache.get(_key(raw), draft) for raw, draft in zip(raws, drafts, strict=True)
            ]

    def _unseen(self, raws: list[str], drafts: list[str]) -> dict[str, str]:
        with self._lock:
            return {
                _key(raw): draft
                for raw, draft in zip(raws, drafts, strict=True)
                if _key(raw) and _key(raw) not in self._cache
            }

    def _ask(self, unseen: dict[str, str]) -> None:
        items = list(unseen.items())
        for start in range(0, len(items), BATCH_SIZE):
            batch = items[start : start + BATCH_SIZE]
            try:
                answers = self._call(batch)
            except (httpx.HTTPError, ValueError, KeyError, IndexError, TypeError) as exc:
                log.warning("Name normalisation fell back to rules (%s)", type(exc).__name__)
                return
            self._store(batch, answers)

    def _call(self, batch: list[tuple[str, str]]) -> dict[int, Any]:
        config = self._config
        lines = [{"i": i, "printed": raw, "draft": draft} for i, (raw, draft) in enumerate(batch)]
        response = self._client.post(
            f"{config.base_url}/chat/completions",
            json={
                "model": config.text_model,
                "messages": [
                    {"role": "system", "content": SYSTEM_PROMPT},
                    {"role": "user", "content": json.dumps(lines)},
                ],
                "response_format": {
                    "type": "json_schema",
                    "json_schema": {"name": "names", "schema": NAMES_SCHEMA, "strict": True},
                },
                "enable_thinking": False,
            },
            headers={"Authorization": f"Bearer {config.api_key}"},
        )
        response.raise_for_status()
        content = json.loads(response.json()["choices"][0]["message"]["content"])
        return {entry["i"]: entry["name"] for entry in content["names"]}

    def _store(self, batch: list[tuple[str, str]], answers: dict[int, Any]) -> None:
        with self._lock:
            for i, (raw, draft) in enumerate(batch):
                self._cache[raw] = clean_name(answers.get(i)) or draft
                self._cache.move_to_end(raw)
            while len(self._cache) > self._cache_size:
                self._cache.popitem(last=False)


def _key(raw: str) -> str:
    return _SPACES.sub(" ", raw.strip().lower())
