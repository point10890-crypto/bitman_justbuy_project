"""Deterministic explanations built only from engine numbers.

An optional LLM rewrite must pass `numbers_are_grounded`: every number in its
text has to appear in the facts the engine supplied, otherwise the template
text is used. The model may rephrase; it may not invent.
"""
from __future__ import annotations

import math
import re

import pandas as pd

from . import dsl

NUMBER = re.compile(r"[-+]?\d+(?:[.,]\d+)?")


def _p(x: float | None, signed: bool = True) -> str | None:
    if x is None or (isinstance(x, float) and not math.isfinite(x)):
        return None
    return f"{x * 100:+.1f}%" if signed else f"{x * 100:.1f}%"


def explain_row(snap: dict) -> list[str]:
    lines: list[str] = []
    dd20 = snap.get("dd20")
    if dd20 is not None and math.isfinite(dd20):
        lines.append("20일 고가에 붙어 있음" if dd20 >= -0.01 else f"20일 고가 대비 {_p(dd20)}")
    for w in (20, 60):
        g = snap.get(f"gap_ma{w}")
        if g is not None and math.isfinite(g):
            lines.append(f"{w}일선 {'위' if g >= 0 else '아래'} ({_p(g)})")
    atr = snap.get("atr14_pct")
    if atr is not None and math.isfinite(atr):
        tone = "차분함" if atr <= 0.03 else "보통" if atr <= 0.05 else "변동 큼"
        lines.append(f"ATR {_p(atr, signed=False)} · {tone}")
    volr = snap.get("volr")
    if volr is not None and math.isfinite(volr):
        lines.append(f"거래량 {volr:.1f}배")
    r1 = snap.get("r1")
    if r1 is not None and math.isfinite(r1):
        lines.append(f"당일 {_p(r1)}")
    return lines


def condition_checks(condition: dict, ctx: dsl.Context, codes: list[str]) -> dict[str, list[dict[str, str]]]:
    """Explain only matched clauses using the same session and DSL as selection."""
    checks: dict[str, list[dict[str, str]]] = {code: [] for code in codes}
    day = ctx.index[0]

    def add(cond, matched):
        if not matched:
            return
        if "all" in cond:
            for child in cond["all"]:
                add(child, matched)
        elif "any" in cond:
            for child in cond["any"]:
                hit = dsl.evaluate(child, ctx).loc[day]
                add(child, [code for code in matched if hit.at[code]])
        else:
            label = dsl.describe(cond)[0]
            for code in matched:
                checks[code].append({"label": label, "detail": _observed(cond, ctx, day, code)})

    add(condition, codes)
    return checks


def _observed(cond, ctx: dsl.Context, day, code: str) -> str:
    if "not" in cond:
        return _observed(cond["not"], ctx, day, code)
    if "all" in cond or "any" in cond:
        children = cond.get("all", cond.get("any"))
        return " · ".join(_observed(child, ctx, day, code) for child in children)
    if "market_in" in cond:
        value = ctx.meta.get("market", pd.Series(dtype=object)).get(code)
        return value if isinstance(value, str) and value else "시장 데이터 없음"
    if "sector_any" in cond:
        values = [ctx.meta.get(field, pd.Series(dtype=object)).get(code) for field in ("sector", "industry")]
        return " / ".join(dict.fromkeys(value for value in values if isinstance(value, str) and value)) or "업종 데이터 없음"
    feature = cond["between"][0] if "between" in cond else cond["f"]
    value = ctx.feats[feature].at[day, code]
    if pd.isna(value) or not math.isfinite(value):
        return "관측값 없음"
    detail = dsl.format_value(feature, value)
    if feature == "val_today":
        return f"{detail} (종가 × 거래량)"
    return detail


def numbers_are_grounded(text: str, facts: str) -> bool:
    """True when every number in `text` also appears in `facts` (after normalising)."""
    allowed = {_norm(n) for n in NUMBER.findall(facts)}
    return all(_norm(n) in allowed for n in NUMBER.findall(text))


def _norm(token: str) -> str:
    return token.replace(",", "").lstrip("+").rstrip("0").rstrip(".") or "0"
