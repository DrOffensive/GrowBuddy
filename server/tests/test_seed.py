"""Invariants of the seeded strain catalog (scraped from a shop, then cleaned)."""

from __future__ import annotations

import json
import re
from collections import Counter
from importlib import resources
from typing import Any

import pytest

STRAINS: list[dict[str, Any]] = json.loads(
    resources.files("growbuddy.seed").joinpath("strains.json").read_text(encoding="utf-8")
)["strains"]


def test_names_are_unique_per_vendor() -> None:
    keys = Counter((s["name"].lower(), s["vendor"]) for s in STRAINS)
    assert [k for k, n in keys.items() if n > 1] == []


def test_vendor_spellings_are_canonical() -> None:
    # One spelling per vendor: no pack sizes ("7.stk") or case variants ("Fast buds").
    vendors = {s["vendor"] for s in STRAINS}
    by_key = Counter(re.sub(r"[^a-z]", "", v.lower()) for v in vendors)
    assert [k for k, n in by_key.items() if n > 1] == []
    assert not [v for v in vendors if re.search(r"\d|stk", v, re.I)]


PACKAGING = [r"frø", r"\bstk\b", r"\bmix\b", r"femini", r"\bfemi$", r"olie"]


@pytest.mark.parametrize("pattern", PACKAGING)
def test_names_hold_no_packaging_or_products(pattern: str) -> None:
    assert [s["name"] for s in STRAINS if re.search(pattern, s["name"], re.I)] == []


def test_category_matches_auto_in_name() -> None:
    assert {s["category"] for s in STRAINS} == {"Feminized", "Autoflower"}
    mismatched = [
        s["name"]
        for s in STRAINS
        if ("auto" in s["name"].lower()) != (s["category"] == "Autoflower")
    ]
    assert mismatched == []


def test_thc_is_a_single_range() -> None:
    assert not [s["thc"] for s in STRAINS if s["thc"] and "," in s["thc"]]
