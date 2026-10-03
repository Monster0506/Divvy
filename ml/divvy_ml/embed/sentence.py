"""Pretrained sentence-transformers model. Needs the optional `semantic` extra."""

from collections.abc import Sequence

import numpy as np

MINILM = "minilm"
MINILM_MODEL = "sentence-transformers/all-MiniLM-L6-v2"


class SentenceEmbedder:
    def __init__(self, model: str = MINILM_MODEL) -> None:
        try:
            from sentence_transformers import SentenceTransformer
        except ImportError as err:
            raise RuntimeError('install the extra: pip install -e ".[semantic]"') from err

        self.name = model
        self._model = SentenceTransformer(model)

    def embed(self, texts: Sequence[str]) -> np.ndarray:
        if not texts:
            return np.zeros((0, self._model.get_sentence_embedding_dimension()), dtype=np.float32)

        vectors = self._model.encode(list(texts), normalize_embeddings=True, convert_to_numpy=True)
        return np.asarray(vectors, dtype=np.float32)
