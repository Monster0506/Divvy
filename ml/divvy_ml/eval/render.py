"""Render evaluation results as Markdown or a standalone HTML page."""

from dataclasses import dataclass
from html import escape

from divvy_ml.eval.coverage import COVERAGE_RATES
from divvy_ml.eval.evaluate import PRIMARY_RULE, Results
from divvy_ml.eval.metrics import Confidence, ReliabilityBin
from divvy_ml.eval.reliability import reliability_svg
from divvy_ml.eval.slices import Slice

MISSING = "-"


@dataclass(frozen=True)
class Table:
    title: str
    header: list[str]
    rows: list[list[str]]
    note: str = ""


def _pct(value: float | None) -> str:
    return MISSING if value is None else f"{100 * value:.1f}"


def summary(results: Results) -> list[str]:
    data = results["dataset"]
    cfg = results["config"]
    span = f"{data['first_date']} to {data['last_date']}"
    lines = [
        f"{data['size']} {data['source']} observations, {span}.",
        f"Split by date: {data['warmup']} warm-up, {data['validation']} validation, "
        f"{data['test']} test.",
        f"Refit every {cfg['step']} items; scored from item {data['scored_from'] + 1}.",
        f"People: {', '.join(results['people'])}.",
    ]
    if "bayes_ceiling" in results:
        ceiling = _pct(results["bayes_ceiling"])
        lines.append(f"Exact-set ceiling for a model that knows every habit: {ceiling}%.")

    rec = results.get("recommendation")
    if rec and rec["threshold"] is not None:
        lines.append(
            f"Recommended: `{rec['model']}` with {rec['rule']} confidence threshold "
            f"{rec['threshold']:.3f} (auto-assigns {_pct(rec['auto_rate'])}% at "
            f"{_pct(rec['auto_accuracy'])}% exact-set accuracy; "
            f"{_pct(rec['review_rate'])}% go to review)."
        )
    else:
        lines.append(
            f"No threshold reached the {_pct(cfg['target'])}% target on validation, "
            "so every item goes to review."
        )

    return lines


def tables(results: Results) -> list[Table]:
    models = results["models"]
    out = [
        Table(
            "Test metrics (%, hamming and brier raw)",
            ["model", "exact", "micro F1", "macro F1", "hamming", "brier", "ECE", "item ECE"],
            [
                [
                    name,
                    _pct(m["test"]["exact_match"]),
                    _pct(m["test"]["micro_f1"]),
                    _pct(m["test"]["macro_f1"]),
                    f"{m['test']['hamming_loss']:.3f}",
                    f"{m['test']['brier']:.3f}",
                    f"{m['test']['ece']:.3f}",
                    f"{m['test']['item_ece']:.3f}",
                ]
                for name, m in models.items()
            ],
        ),
        Table(
            "Test per-person precision / recall / F1 (%)",
            ["model", *results["people"]],
            [
                [name]
                + [
                    "{}/{}/{}".format(_pct(per["precision"]), _pct(per["recall"]), _pct(per["f1"]))
                    for per in (m["test"]["per_person"][p] for p in results["people"])
                ]
                for name, m in models.items()
            ],
        ),
    ]

    counts = results["dataset"]["test_slices"]
    kinds = [s for s in Slice if s.value in counts]
    if kinds:
        out.append(
            Table(
                "Test exact-set accuracy by cold-start slice (%, n in brackets)",
                ["model", *[f"{s.value} ({counts[s.value]})" for s in kinds]],
                [
                    [name]
                    + [
                        _pct(m["test_slices"][s.value]["exact_match"])
                        if s.value in m["test_slices"]
                        else MISSING
                        for s in kinds
                    ]
                    for name, m in models.items()
                ],
            )
        )

    target = results["config"]["target"]
    rows = []
    for name, m in models.items():
        for rule in Confidence:
            a = m["abstention"][rule.value]
            point = a["test"] or {}
            threshold = MISSING if a["threshold"] is None else f"{a['threshold']:.3f}"
            rows.append(
                [
                    name,
                    rule.value,
                    threshold,
                    _pct(point.get("auto_rate")),
                    _pct(point.get("auto_accuracy")),
                    _pct(point.get("review_rate")),
                ]
            )

    out.append(
        Table(
            "Auto-assign operating point (%)",
            ["model", "rule", "threshold", "auto", "auto accuracy", "review"],
            rows,
            f"Threshold picked on validation for {_pct(target)}% exact-set accuracy, "
            "applied unchanged to test.",
        )
    )
    out.append(
        Table(
            f"Coverage vs accuracy, {PRIMARY_RULE.value} confidence (%)",
            ["model", *[f"top {round(100 * r)}%" for r in COVERAGE_RATES]],
            [
                [name]
                + [
                    _pct(p["auto_accuracy"])
                    for p in m["abstention"][PRIMARY_RULE.value]["test_coverage"]
                ]
                for name, m in models.items()
            ],
            "Accuracy when only the most confident share of items is auto-assigned.",
        )
    )
    return out


def reliability_table(results: Results, name: str) -> Table:
    bins = results["models"][name]["reliability"]
    return Table(
        f"Reliability, {name} ({PRIMARY_RULE.value} confidence on test)",
        ["confidence", "items", "mean confidence", "observed accuracy"],
        [
            [
                f"{b['lower']:.1f} to {b['upper']:.1f}",
                str(b["count"]),
                f"{b['mean_confidence']:.3f}",
                f"{b['observed_rate']:.3f}",
            ]
            for b in bins
        ],
    )


def _reference_model(results: Results) -> str | None:
    rec = results.get("recommendation")
    return rec["model"] if rec else None


def to_markdown(results: Results, title: str = "Ownership evaluation") -> str:
    parts = [f"# {title}", "", *summary(results)]
    sections = tables(results)
    reference = _reference_model(results)
    if reference:
        sections.append(reliability_table(results, reference))

    for table in sections:
        parts += ["", f"## {table.title}", ""]
        if table.note:
            parts += [table.note, ""]

        parts.append("| " + " | ".join(table.header) + " |")
        parts.append("|" + "|".join("---" for _ in table.header) + "|")
        parts += ["| " + " | ".join(row) + " |" for row in table.rows]

    return "\n".join(parts) + "\n"


STYLE = """
:root { --bg:#fff; --ink:#16191d; --dim:#666d75; --line:#e2e5e8; }
@media (prefers-color-scheme: dark) {
  :root { --bg:#121212; --ink:#f1eee6; --dim:#8d8a82; --line:#2c2c2a; }
}
body {
  background:var(--bg); color:var(--ink); font:15px/1.5 system-ui,sans-serif;
  max-width:980px; margin:32px auto; padding:0 16px;
}
h1 { font-size:24px; } h2 { font-size:17px; margin-top:32px; }
table { border-collapse:collapse; width:100%; font-variant-numeric:tabular-nums; }
th, td { text-align:right; padding:6px 10px; border-bottom:1px solid var(--line); }
th:first-child, td:first-child { text-align:left; }
.note { color:var(--dim); font-size:13px; } svg { max-width:360px; }
"""


def to_html(results: Results, title: str = "Ownership evaluation") -> str:
    body = [f"<h1>{escape(title)}</h1>"]
    body += [f"<p>{escape(line)}</p>" for line in summary(results)]
    sections = tables(results)
    reference = _reference_model(results)
    if reference:
        sections.append(reliability_table(results, reference))

    for table in sections:
        body.append(f"<h2>{escape(table.title)}</h2>")
        if table.note:
            body.append(f'<p class="note">{escape(table.note)}</p>')

        head = "".join(f"<th>{escape(h)}</th>" for h in table.header)
        rows = "".join(
            "<tr>" + "".join(f"<td>{escape(c)}</td>" for c in row) + "</tr>" for row in table.rows
        )
        body.append(f"<table><thead><tr>{head}</tr></thead><tbody>{rows}</tbody></table>")

    if reference:
        bins = [ReliabilityBin(**b) for b in results["models"][reference]["reliability"]]
        body.append(reliability_svg(bins, f"Reliability, {reference}"))

    return (
        '<!doctype html><html lang="en"><head><meta charset="utf-8">'
        '<meta name="viewport" content="width=device-width, initial-scale=1">'
        f"<title>{escape(title)}</title><style>{STYLE}</style></head><body>"
        + "\n".join(body)
        + "</body></html>\n"
    )


def best_exact_match(results: Results) -> float:
    return max((m["test"]["exact_match"] for m in results["models"].values()), default=0.0)
