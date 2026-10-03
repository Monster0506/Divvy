"""Write a synthetic household: python -m divvy_ml.synthetic --size 6000 --seed 7 --out FILE."""

import argparse
import json
from pathlib import Path

from divvy_ml.synthetic import DEFAULT_SIZE, SEED, bayes_ceiling, generate

DEFAULT_OUT = Path("data/synthetic/household.jsonl")
CEILING_WINDOW = 500


def main(argv: list[str] | None = None) -> None:
    parser = argparse.ArgumentParser(prog="python -m divvy_ml.synthetic", description=__doc__)
    parser.add_argument("--size", type=int, default=DEFAULT_SIZE, help="number of purchases")
    parser.add_argument("--seed", type=int, default=SEED)
    parser.add_argument("--out", type=Path, default=DEFAULT_OUT)
    args = parser.parse_args(argv)

    if args.size < 1:
        parser.error("--size must be at least 1")

    purchases = generate(args.size, args.seed)
    args.out.parent.mkdir(parents=True, exist_ok=True)
    with args.out.open("w", encoding="utf-8") as handle:
        for purchase in purchases:
            handle.write(json.dumps(purchase.to_json()) + "\n")

    days = (purchases[-1].date - purchases[0].date).days
    ceiling = bayes_ceiling(purchases, start=max(0, len(purchases) - CEILING_WINDOW))
    print(f"wrote {len(purchases)} purchases over {days} days to {args.out}")
    print(f"exact-set ceiling on the last {min(CEILING_WINDOW, len(purchases))}: {ceiling:.1%}")


if __name__ == "__main__":
    main()
