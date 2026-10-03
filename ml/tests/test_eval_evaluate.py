import dataclasses
import json
import xml.etree.ElementTree as ET

import numpy as np
import pytest

from divvy_ml.eval import render
from divvy_ml.eval.__main__ import main
from divvy_ml.eval.dataset import Dataset, DatasetError, Source, from_records, parse_lines
from divvy_ml.eval.evaluate import Config, default_step, evaluate, walk_predictions
from divvy_ml.eval.slices import Slice, assign
from divvy_ml.eval.split import aligned, walk_forward
from divvy_ml.models import ExactItemModel, FrequencyModel, available_models, make_models
from divvy_ml.synthetic import generate

START, CUT, STEP = 100, 150, 10


def synthetic(size: int, seed: int = 1) -> Dataset:
    return from_records([p.to_json() for p in generate(size, seed)], Source.SYNTHETIC)


def flipped(dataset: Dataset, cut: int) -> Dataset:
    """The same household with every owner set from index `cut` on inverted."""
    changed = []
    for i, obs in enumerate(dataset.observations):
        if i >= cut:
            inverse = frozenset(dataset.people) - obs.owners
            obs = dataclasses.replace(obs, owners=inverse or frozenset({dataset.people[0]}))

        changed.append(obs)

    return Dataset(dataset.people, tuple(changed), dataset.source)


@pytest.mark.parametrize("name", available_models())
def test_predictions_never_see_future_labels(name):
    dataset = synthetic(300)
    cut = aligned(dataset.dates, CUT)
    tampered = flipped(dataset, cut)
    start = aligned(dataset.dates, START)

    original = walk_predictions(dataset, make_models([name])[0], start, STEP)
    altered = walk_predictions(tampered, make_models([name])[0], start, STEP)

    blocks = list(walk_forward(dataset.dates, start, len(dataset), STEP))
    first_exposed = next(fit_end for fit_end, _ in blocks if fit_end > cut)
    safe = first_exposed - start
    assert safe > 0
    assert np.allclose(original[:safe], altered[:safe])
    assert not np.allclose(original[safe:], altered[safe:])


def test_evaluate_produces_every_section():
    results = evaluate(synthetic(600), make_models(), Config())

    assert results["people"] == ["sumedh", "savar", "andrew", "tj"]
    assert set(results["models"]) == set(available_models())
    data = results["dataset"]
    assert data["warmup"] + data["validation"] + data["test"] == 600
    assert sum(data["test_slices"].values()) == data["test"]
    for model in results["models"].values():
        assert {"validation", "test", "expanding", "abstention", "reliability"} <= set(model)
        assert {"joint", "weakest"} == set(model["abstention"])
    assert results["config"]["step"] == default_step(600)


def test_a_useful_model_beats_the_prior_baseline():
    results = evaluate(synthetic(900), make_models(), Config())["models"]

    assert (
        results["exact-item"]["test"]["exact_match"] > results["frequency"]["test"]["exact_match"]
    )


def test_recommendation_names_the_best_model_with_its_threshold():
    results = evaluate(synthetic(900), make_models(), Config())

    rec = results["recommendation"]
    assert rec["model"] == "exact-item"
    assert rec["rule"] == "joint"
    assert rec["threshold"] is None or 0 <= rec["threshold"] <= 1
    assert rec["auto_rate"] is None or 0 <= rec["auto_rate"] <= 1


def test_the_threshold_comes_from_validation_and_is_held_fixed_for_test():
    results = evaluate(synthetic(900), [ExactItemModel()], Config(target=0.9))

    abstention = results["models"]["exact-item"]["abstention"]["joint"]
    assert abstention["target_accuracy"] == 0.9
    assert abstention["test"]["threshold"] == abstention["threshold"]


def test_evaluate_rejects_tiny_datasets():
    with pytest.raises(DatasetError, match="too few"):
        evaluate(synthetic(2), make_models())


def test_evaluate_handles_a_member_who_joins_late():
    rows = []
    for i, p in enumerate(generate(400, 4)):
        record = p.to_json()
        if i < 150:
            del record["owners"]["tj"]
            if not any(record["owners"].values()):
                record["owners"]["sumedh"] = True

        rows.append(record)

    dataset = from_records(rows, Source.SYNTHETIC)
    results = evaluate(dataset, make_models())

    assert results["people"][-1] == "tj" or "tj" in results["people"]
    assert 0 <= results["models"]["exact-item"]["test"]["exact_match"] <= 1


def test_slices_use_tags_when_present_and_seen_or_new_otherwise():
    tagged = synthetic(300).observations
    kinds = assign(tagged, 50)
    assert len(kinds) == 250
    assert Slice.SEEN in kinds and Slice.RELATED in kinds

    plain = parse_lines(
        [
            json.dumps(
                {
                    "seq": n,
                    "date": f"2026-01-{n:02d}",
                    "item": item,
                    "owners": {"a": True},
                    "source": "real",
                }
            )
            for n, item in enumerate(["Milk", "Eggs", "Milk", "Rice"], start=1)
        ]
    )
    assert assign(plain.observations, 0) == [Slice.NEW, Slice.NEW, Slice.SEEN, Slice.NEW]


def test_markdown_report_has_the_sections_the_issue_asks_for():
    results = evaluate(synthetic(600), make_models())

    text = render.to_markdown(results)

    assert text.startswith("# Ownership evaluation")
    for heading in (
        "Test metrics",
        "Coverage vs accuracy",
        "Auto-assign operating point",
        "Reliability",
    ):
        assert heading in text
    assert "Recommended:" in text or "No threshold reached" in text


def test_html_report_is_standalone_and_embeds_the_reliability_diagram():
    html = render.to_html(evaluate(synthetic(600), make_models()), title="A & B")

    assert html.startswith("<!doctype html>")
    assert "A &amp; B" in html
    start = html.index("<svg")
    ET.fromstring(html[start : html.index("</svg>") + 6])


def test_command_writes_the_report_files(tmp_path, capsys):
    out = tmp_path / "report"

    code = main(["--household", "synthetic", "--size", "500", "--out", str(out)])

    assert code == 0
    assert (out / "report.md").read_text(encoding="utf-8").startswith("# Ownership evaluation")
    assert "<svg" in (out / "report.html").read_text(encoding="utf-8")
    assert "models" in json.loads((out / "results.json").read_text(encoding="utf-8"))
    assert "Report written to" in capsys.readouterr().out


def test_command_can_evaluate_a_file(tmp_path):
    data = tmp_path / "household.jsonl"
    rows = [json.dumps(p.to_json()) for p in generate(500, 2)]
    data.write_text("\n".join(rows), encoding="utf-8")

    assert main(["--data", str(data), "--expect", "synthetic", "--out", str(tmp_path / "r")]) == 0


def test_command_fails_below_the_minimum_score(tmp_path):
    args = ["--size", "500", "--out", str(tmp_path / "r"), "--models", "frequency"]

    assert main([*args, "--min-exact", "0.99"]) == 1
    assert main([*args, "--min-exact", "0.0"]) == 0


@pytest.mark.parametrize(
    "args", [["--models", "nope"], ["--household", "elsewhere"], ["--size", "3"]]
)
def test_command_reports_bad_input_with_exit_code_2(tmp_path, args):
    assert main([*args, "--out", str(tmp_path / "r")]) == 2


def test_frequency_model_ignores_the_item():
    model = FrequencyModel()
    labels = np.array([[1, 0], [1, 0], [1, 1]])
    model.fit(["a", "b", "c"], labels, np.ones_like(labels, dtype=bool))

    probs = model.predict(["x", "y"])

    assert probs.shape == (2, 2)
    assert np.allclose(probs[0], probs[1])
    assert probs[0, 0] > probs[0, 1]


def test_exact_item_model_uses_history_and_falls_back_to_the_prior():
    model = ExactItemModel()
    labels = np.array([[1, 0], [1, 0], [0, 1]])
    model.fit(["Milk", "milk!", "Eggs"], labels, np.ones_like(labels, dtype=bool))

    milk, unseen = model.predict(["MILK", "Rice"])

    assert milk[0] > 0.7 > milk[1]
    assert np.allclose(unseen, model._prior)


def test_exact_item_model_counts_only_known_cells():
    model = ExactItemModel()
    labels = np.array([[1, 0], [1, 0]])
    known = np.array([[True, False], [True, False]])
    model.fit(["Milk", "Milk"], labels, known)

    assert model.predict(["Milk"])[0, 1] == pytest.approx(0.5)


def test_make_models_rejects_unknown_names():
    with pytest.raises(ValueError, match="unknown models"):
        make_models(["nope"])
