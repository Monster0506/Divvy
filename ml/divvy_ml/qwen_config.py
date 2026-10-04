"""Settings for the Qwen vision call, read from the environment.

The API key comes from QWEN_API_KEY, or from the file named by QWEN_API_KEY_FILE, which holds
only the key. It is never logged or included in an error.
"""

import os
from dataclasses import dataclass, field
from pathlib import Path

DEFAULT_BASE_URL = "https://maas.qwencloudapi.com/compatible-mode/v1"
DEFAULT_MODEL = "qwen3.7-plus"
DEFAULT_THINKING_BUDGET = 512
DEFAULT_TIMEOUT_SECONDS = 60.0


class NotConfiguredError(RuntimeError):
    """Extraction was asked for but no API key is available."""


@dataclass(frozen=True)
class QwenConfig:
    api_key: str = field(repr=False)
    base_url: str = DEFAULT_BASE_URL
    model: str = DEFAULT_MODEL
    enable_thinking: bool = True
    thinking_budget: int = DEFAULT_THINKING_BUDGET
    timeout_seconds: float = DEFAULT_TIMEOUT_SECONDS
    text_model: str = DEFAULT_MODEL
    llm_normalize: bool = True

    @classmethod
    def from_env(cls, env: dict[str, str] | None = None) -> "QwenConfig":
        env = dict(os.environ) if env is None else env
        return cls(
            api_key=_api_key(env),
            base_url=env.get("QWEN_BASE_URL", DEFAULT_BASE_URL).rstrip("/"),
            model=env.get("QWEN_MODEL", DEFAULT_MODEL),
            enable_thinking=_flag(env.get("QWEN_ENABLE_THINKING", "true")),
            thinking_budget=int(env.get("QWEN_THINKING_BUDGET", DEFAULT_THINKING_BUDGET)),
            timeout_seconds=float(env.get("QWEN_TIMEOUT_SECONDS", DEFAULT_TIMEOUT_SECONDS)),
            text_model=env.get("QWEN_TEXT_MODEL", env.get("QWEN_MODEL", DEFAULT_MODEL)),
            llm_normalize=_flag(env.get("QWEN_LLM_NORMALIZE", "true")),
        )


def _flag(value: str) -> bool:
    return value.strip().lower() not in ("0", "false", "no", "off")


def _api_key(env: dict[str, str]) -> str:
    key = env.get("QWEN_API_KEY", "").strip()
    if not key and env.get("QWEN_API_KEY_FILE"):
        try:
            key = Path(env["QWEN_API_KEY_FILE"]).read_text(encoding="utf-8").strip()
        except OSError as exc:
            raise NotConfiguredError(f"cannot read QWEN_API_KEY_FILE: {exc.strerror}") from None
    if not key:
        raise NotConfiguredError("set QWEN_API_KEY or QWEN_API_KEY_FILE to enable extraction")
    return key
