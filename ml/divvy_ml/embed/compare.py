"""Compare embedders on related-product similarity.

python -m divvy_ml.embed.compare hashing minilm st:sentence-transformers/paraphrase-MiniLM-L3-v2

Related pairs are different wordings of one product ("Coke Zero 12-pack" and "Coca-Cola Zero Sugar
bottles"); unrelated pairs are wordings of different products. AUC is the chance a related pair is
more similar than an unrelated one.
"""

import argparse
import time
from collections.abc import Sequence

import numpy as np

from divvy_ml.embed.registry import make_embedder
from divvy_ml.synthetic.personas import PRODUCTS

BATCH = 30
DEFAULT_MODELS = ("hashing", "minilm")


def catalog_texts() -> tuple[list[str], np.ndarray]:
    """Distinct wordings that belong to exactly one product, and each one's product index."""
    owners: dict[str, set[str]] = {}
    for product in PRODUCTS.values():
        for variant in product.variants:
            text = variant if isinstance(variant, str) else variant[0]
            owners.setdefault(text, set()).add(product.name)

    texts = [t for t, products in owners.items() if len(products) == 1]
    names = sorted({next(iter(owners[t])) for t in texts})
    index = {name: i for i, name in enumerate(names)}
    return texts, np.array([index[next(iter(owners[t]))] for t in texts])


def separation(vectors: np.ndarray, product: np.ndarray) -> dict[str, float]:
    sims = vectors @ vectors.T
    same = product[:, None] == product[None, :]
    upper = np.triu(np.ones_like(same, dtype=bool), k=1)
    related, unrelated = sims[same & upper], sims[~same & upper]

    order = np.argsort(np.concatenate([related, unrelated]), kind="stable")
    ranks = np.empty(len(order))
    ranks[order] = np.arange(1, len(order) + 1)
    n1, n2 = len(related), len(unrelated)
    auc = (ranks[:n1].sum() - n1 * (n1 + 1) / 2) / (n1 * n2)
    return {
        "auc": float(auc),
        "related": float(related.mean()),
        "unrelated": float(unrelated.mean()),
    }


def compare(specs: Sequence[str]) -> list[dict[str, float | str]]:
    texts, product = catalog_texts()
    batch = texts[:BATCH]
    rows = []
    for spec in specs:
        started = time.perf_counter()
        embedder = make_embedder(spec)
        embedder.embed(["warm up"])
        loaded = time.perf_counter() - started

        started = time.perf_counter()
        embedder.embed(batch)
        batch_seconds = time.perf_counter() - started

        vectors = embedder.embed(texts)
        rows.append(
            {
                "model": spec,
                "dimensions": int(vectors.shape[1]),
                "load_s": loaded,
                "batch30_s": batch_seconds,
                **separation(vectors, product),
            }
        )

    return rows


def to_markdown(rows: Sequence[dict[str, float | str]]) -> str:
    header = "| model | dims | load (s) | 30 items (s) | AUC | related sim | unrelated sim |"
    lines = [header, "|---|---|---|---|---|---|---|"]
    for r in rows:
        lines.append(
            f"| {r['model']} | {r['dimensions']} | {r['load_s']:.2f} | {r['batch30_s']:.3f} "
            f"| {r['auc']:.3f} | {r['related']:.3f} | {r['unrelated']:.3f} |"
        )

    return "\n".join(lines)


def main(argv: Sequence[str] | None = None) -> None:
    parser = argparse.ArgumentParser(prog="python -m divvy_ml.embed.compare", description=__doc__)
    parser.add_argument("models", nargs="*", default=list(DEFAULT_MODELS))
    args = parser.parse_args(argv)
    print(to_markdown(compare(args.models)))


if __name__ == "__main__":
    main()
