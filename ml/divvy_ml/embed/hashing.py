"""Character n-gram hashing: lexical only, but stateless, instant and needs no download."""

from collections.abc import Sequence

import numpy as np
from sklearn.feature_extraction.text import HashingVectorizer

HASHING = "hashing"
FEATURES = 2**12
NGRAMS = (2, 4)


class HashingEmbedder:
    def __init__(self, features: int = FEATURES, ngrams: tuple[int, int] = NGRAMS) -> None:
        self.name = HASHING
        self._features = features
        self._vectorizer = HashingVectorizer(
            analyzer="char_wb",
            ngram_range=ngrams,
            n_features=features,
            alternate_sign=False,
            norm="l2",
        )

    def embed(self, texts: Sequence[str]) -> np.ndarray:
        if not texts:
            return np.zeros((0, self._features), dtype=np.float32)

        return self._vectorizer.transform(list(texts)).toarray().astype(np.float32)
