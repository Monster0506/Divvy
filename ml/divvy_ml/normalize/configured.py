from functools import lru_cache

from divvy_ml.normalize.llm import LlmNameNormalizer
from divvy_ml.normalize.normalizer import NameNormalizer, RulesNormalizer
from divvy_ml.qwen_config import NotConfiguredError, QwenConfig


@lru_cache(maxsize=1)
def configured_normalizer() -> NameNormalizer:
    """The LLM normaliser when a key is set and QWEN_LLM_NORMALIZE is not off, else rules only."""
    try:
        config = QwenConfig.from_env()
    except NotConfiguredError:
        return RulesNormalizer()
    return LlmNameNormalizer(config) if config.llm_normalize else RulesNormalizer()
