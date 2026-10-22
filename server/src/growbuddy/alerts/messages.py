"""Notification texts in the configured language (growbuddy/locales/<code>.json).

Missing keys in a language fall back to English, so a partial translation still works.
"""

from __future__ import annotations

import json
from functools import cache
from importlib import resources
from typing import Any

DEFAULT_LANGUAGE = "en"


def _load(code: str) -> dict[str, str]:
    data: dict[str, str] = json.loads(
        resources.files("growbuddy.locales").joinpath(f"{code}.json").read_text(encoding="utf-8")
    )
    return data


@cache
def available_languages() -> dict[str, str]:
    """``{code: language name}`` for every bundled locale file."""
    codes = sorted(
        entry.name.removesuffix(".json")
        for entry in resources.files("growbuddy.locales").iterdir()
        if entry.name.endswith(".json")
    )
    return {code: _load(code).get("language_name", code) for code in codes}


class Messages:
    def __init__(self, texts: dict[str, str], fallback: dict[str, str] | None = None) -> None:
        self._texts = {**(fallback or {}), **texts}

    def __call__(self, key: str, **values: Any) -> str:
        return self._texts.get(key, key).format(**values)

    def number(self, value: float, digits: int) -> str:
        return f"{value:.{digits}f}".replace(".", self._texts.get("decimal_separator", "."))


@cache
def messages(code: str = DEFAULT_LANGUAGE) -> Messages:
    """Texts for ``code``; unknown codes get English."""
    english = _load(DEFAULT_LANGUAGE)
    if code == DEFAULT_LANGUAGE or code not in available_languages():
        return Messages(english)
    return Messages(_load(code), fallback=english)
