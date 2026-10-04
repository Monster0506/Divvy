"""Evaluate ownership models on a household and write a report.

python -m divvy_ml.eval --household synthetic --out eval-report
python -m divvy_ml.eval --data data/real/observations.jsonl --expect real
"""

import argparse
import json
import sys
from collections.abc import Sequence
from pathlib import Path

from divvy_ml.embed import make_embedder
from divvy_ml.eval import render
from divvy_ml.eval.dataset import Dataset, DatasetError, Source, from_records, load
from divvy_ml.eval.evaluate import Config, evaluate
from divvy_ml.eval.split import chrono_split
from divvy_ml.models import available_models, make_models
from divvy_ml.synthetic import DEFAULT_SIZE, SEED, bayes_ceiling, generate, is_generated

SYNTHETIC = "synthetic"
DEFAULT_OUT = Path("eval-report")


def _dataset(args: argparse.Namespace) -> Dataset:
    if args.data:
        expect = Source(args.expect) if args.expect else None
        return load(args.data, expect)

    if args.household != SYNTHETIC:
        raise DatasetError(
            "only --household synthetic is built in; export real data and use --data"
        )

    purchases = generate(args.size, args.seed)
    return from_records([p.to_json() for p in purchases], Source.SYNTHETIC)


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="python -m divvy_ml.eval", description=__doc__)
    parser.add_argument("--household", default=SYNTHETIC, help="'synthetic' (default)")
    parser.add_argument("--data", type=Path, help="labelled JSONL instead of --household")
    parser.add_argument("--expect", choices=[s.value for s in Source], help="required source")
    parser.add_argument("--size", type=int, default=DEFAULT_SIZE, help="synthetic purchases")
    parser.add_argument("--seed", type=int, default=SEED)
    parser.add_argument("--models", nargs="+", default=None, help=f"from {available_models()}")
    parser.add_argument("--embedder", default="hashing", help="hashing, minilm or st:<model id>")
    parser.add_argument("--step", type=int, help="refit every N items")
    parser.add_argument(
        "--target", type=float, default=Config().target, help="auto-assign accuracy"
    )
    parser.add_argument("--out", type=Path, default=DEFAULT_OUT, help="report directory")
    parser.add_argument(
        "--min-exact", type=float, default=0.0, help="exit 1 if the best model scores below this"
    )
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    try:
        dataset = _dataset(args)
        embedder = make_embedder(args.embedder)
        models = make_models(args.models, embedder)
        results = evaluate(dataset, models, Config(step=args.step, target=args.target))
        results["embedder"] = embedder.name
    except (DatasetError, ValueError) as err:
        print(f"error: {err}", file=sys.stderr)
        return 2

    if dataset.source is Source.SYNTHETIC and is_generated(dataset.observations):
        split = chrono_split(dataset.dates)
        results["bayes_ceiling"] = bayes_ceiling(dataset.observations, start=split.test)

    args.out.mkdir(parents=True, exist_ok=True)
    (args.out / "results.json").write_text(json.dumps(results, indent=2), encoding="utf-8")
    markdown = render.to_markdown(results)
    (args.out / "report.md").write_text(markdown, encoding="utf-8")
    (args.out / "report.html").write_text(render.to_html(results), encoding="utf-8")
    print(markdown)
    print(f"Report written to {args.out}/ (report.md, report.html, results.json)")

    best = render.best_exact_match(results)
    if best < args.min_exact:
        print(
            f"error: best test exact match {best:.3f} is below --min-exact {args.min_exact}",
            file=sys.stderr,
        )
        return 1

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
