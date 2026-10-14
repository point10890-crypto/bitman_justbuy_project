"""Condition DSL — the contract between the AI agent and the deterministic engine.

The LLM never picks stocks or emits prices. It translates a member's request into
this DSL, the engine validates it against a whitelist, and only then evaluates it.

  cond     := {"all": [cond...]} | {"any": [cond...]} | {"not": cond}
            | {"f": FEATURE, "op": OP, "v": NUMBER}
            | {"between": [FEATURE, LOW, HIGH]}
            | {"sector_any": [KEYWORD...]}          # matches sector or industry text
            | {"market_in": ["KOSPI" | "KOSDAQ", ...]}
  strategy := {"id", "title", "when": cond, "rank_by": {"f", "desc"}, "top_n",
               "guard"?: {"exclude_risk": [FLAG...], "regime_in": [REGIME...]}}

A missing value never satisfies any condition, including NOT: absence of data
is not evidence.
"""
from __future__ import annotations

import hashlib
import json
import operator
import re
from typing import Any, Mapping

import numpy as np
import pandas as pd

from .features import REGISTRY

OPS = {">=": operator.ge, ">": operator.gt, "<=": operator.le, "<": operator.lt, "==": operator.eq}
MAX_DEPTH = 6
MAX_LEAVES = 16
MAX_TOP_N = 50
MARKETS = {"KOSPI", "KOSDAQ"}
RISK_FLAGS = {"chase", "high_vol", "breakout_spike", "volume_spike_flat"}
REGIMES = {"UP_TREND", "PULLBACK", "DOWN_TREND", "REBOUND"}
KEYWORD = re.compile(r"^[0-9A-Za-z가-힣 ]{1,20}$")
DEFAULT_GUARD = {"exclude_risk": ["chase", "high_vol"]}


class DslError(ValueError):
    pass


# --------------------------------------------------------------------- validation
def validate(cond: Any, features: set[str] | None = None, depth: int = 0, _count: list | None = None) -> None:
    features = features if features is not None else set(REGISTRY)
    count = _count if _count is not None else [0]
    if depth > MAX_DEPTH:
        raise DslError("조건이 너무 깊게 중첩되었습니다")
    if not isinstance(cond, Mapping) or not cond:
        raise DslError(f"조건 형식이 올바르지 않습니다: {cond!r}")
    if "all" in cond or "any" in cond:
        key = "all" if "all" in cond else "any"
        items = cond[key]
        if not isinstance(items, list) or not items:
            raise DslError(f"'{key}' 에는 조건 목록이 필요합니다")
        for item in items:
            validate(item, features, depth + 1, count)
        return
    if "not" in cond:
        validate(cond["not"], features, depth + 1, count)
        return
    count[0] += 1
    if count[0] > MAX_LEAVES:
        raise DslError(f"조건은 최대 {MAX_LEAVES}개까지 사용할 수 있습니다")
    if "between" in cond:
        name, lo, hi = _triple(cond["between"])
        _feature(name, features)
        if not (_num(lo) and _num(hi)) or lo > hi:
            raise DslError(f"{name} 범위가 올바르지 않습니다: {lo} ~ {hi}")
        return
    if "sector_any" in cond:
        words = cond["sector_any"]
        if not isinstance(words, list) or not words or not all(isinstance(w, str) and KEYWORD.match(w) for w in words):
            raise DslError("업종 키워드는 1~20자 한글/영문/숫자 목록이어야 합니다")
        return
    if "market_in" in cond:
        markets = cond["market_in"]
        if not isinstance(markets, list) or not markets or not set(markets) <= MARKETS:
            raise DslError("시장은 KOSPI 또는 KOSDAQ 만 지정할 수 있습니다")
        return
    if {"f", "op", "v"} <= set(cond):
        _feature(cond["f"], features)
        if cond["op"] not in OPS:
            raise DslError(f"지원하지 않는 연산자입니다: {cond['op']!r}")
        if not _num(cond["v"]):
            raise DslError(f"값은 숫자여야 합니다: {cond['v']!r}")
        return
    raise DslError(f"알 수 없는 조건입니다: {cond!r}")


def validate_strategy(strategy: Mapping, features: set[str] | None = None) -> dict:
    features = features if features is not None else set(REGISTRY)
    for key in ("title", "when", "rank_by", "top_n"):
        if key not in strategy:
            raise DslError(f"전략에 '{key}' 가 없습니다")
    validate(strategy["when"], features)
    rank = strategy["rank_by"]
    if not isinstance(rank, Mapping) or "f" not in rank:
        raise DslError("rank_by 에는 피처 이름이 필요합니다")
    _feature(rank["f"], features)
    top_n = strategy["top_n"]
    if not isinstance(top_n, int) or not (1 <= top_n <= MAX_TOP_N):
        raise DslError(f"top_n 은 1~{MAX_TOP_N} 사이 정수여야 합니다")
    guard = strategy.get("guard", DEFAULT_GUARD)
    if not isinstance(guard, Mapping):
        raise DslError("guard 형식이 올바르지 않습니다")
    flags = guard.get("exclude_risk", [])
    if not set(flags) <= RISK_FLAGS:
        raise DslError(f"알 수 없는 위험 플래그: {sorted(set(flags) - RISK_FLAGS)}")
    regimes = guard.get("regime_in")
    if regimes is not None and (not regimes or not set(regimes) <= REGIMES):
        raise DslError(f"알 수 없는 레짐: {regimes}")
    out = dict(strategy)
    out["guard"] = {"exclude_risk": list(flags), **({"regime_in": list(regimes)} if regimes else {})}
    out.setdefault("id", "adhoc_" + dsl_hash(out)[:10])
    return out


def dsl_hash(strategy: Mapping) -> str:
    body = {k: strategy[k] for k in ("when", "rank_by", "top_n", "guard") if k in strategy}
    return hashlib.sha256(json.dumps(body, sort_keys=True, ensure_ascii=False).encode()).hexdigest()


def _triple(value):
    if not isinstance(value, (list, tuple)) or len(value) != 3:
        raise DslError("between 은 [피처, 하한, 상한] 형식이어야 합니다")
    return value


def _num(x) -> bool:
    return isinstance(x, (int, float)) and not isinstance(x, bool) and np.isfinite(x)


def _feature(name, features: set[str]) -> None:
    if name not in features:
        raise DslError(f"알 수 없는 피처입니다: {name!r}")


# --------------------------------------------------------------------- evaluation
class Context:
    """Everything a condition can read: feature matrices and per-code metadata."""

    def __init__(self, feats: Mapping[str, pd.DataFrame], meta: pd.DataFrame):
        self.feats = feats
        self.meta = meta
        any_frame = next(iter(feats.values()))
        self.index, self.columns = any_frame.index, any_frame.columns

    def broadcast(self, per_code: pd.Series) -> pd.DataFrame:
        values = per_code.reindex(self.columns).fillna(False).to_numpy(bool)
        return pd.DataFrame(np.tile(values, (len(self.index), 1)), index=self.index, columns=self.columns)


def evaluate(cond: Mapping, ctx: Context) -> pd.DataFrame:
    if "all" in cond:
        out = None
        for item in cond["all"]:
            m = evaluate(item, ctx)
            out = m if out is None else (out & m)
        return out
    if "any" in cond:
        out = None
        for item in cond["any"]:
            m = evaluate(item, ctx)
            out = m if out is None else (out | m)
        return out
    if "not" in cond:
        return ~evaluate(cond["not"], ctx) & _known(cond["not"], ctx)
    if "between" in cond:
        name, lo, hi = cond["between"]
        x = ctx.feats[name]
        return (x >= lo) & (x <= hi)
    if "sector_any" in cond:
        text = (ctx.meta.get("sector", pd.Series(dtype=object)).fillna("").astype(str) + " "
                + ctx.meta.get("industry", pd.Series(dtype=object)).fillna("").astype(str))
        hit = text.apply(lambda t: any(w in t for w in cond["sector_any"]))
        return ctx.broadcast(hit)
    if "market_in" in cond:
        hit = ctx.meta.get("market", pd.Series(dtype=object)).isin(cond["market_in"])
        return ctx.broadcast(hit)
    x = ctx.feats[cond["f"]]
    return OPS[cond["op"]](x, cond["v"]) & x.notna()


def _known(cond: Mapping, ctx: Context) -> pd.DataFrame:
    out = None
    for name in _features_in(cond):
        k = ctx.feats[name].notna()
        out = k if out is None else (out & k)
    if out is None:   # pure metadata condition: known wherever the code exists
        return pd.DataFrame(True, index=ctx.index, columns=ctx.columns)
    return out


def _features_in(cond: Mapping) -> list[str]:
    if "all" in cond or "any" in cond:
        return [n for c in cond.get("all", cond.get("any")) for n in _features_in(c)]
    if "not" in cond:
        return _features_in(cond["not"])
    if "between" in cond:
        return [cond["between"][0]]
    if "f" in cond:
        return [cond["f"]]
    return []


def describe(cond: Mapping) -> list[str]:
    """Human-readable Korean lines for each leaf, for the UI and explanations."""
    if "all" in cond:
        return [line for c in cond["all"] for line in describe(c)]
    if "any" in cond:
        parts = [" 그리고 ".join(describe(c)) for c in cond["any"]]
        return ["다음 중 하나: " + " / ".join(parts)]
    if "not" in cond:
        return ["아님: " + " 그리고 ".join(describe(cond["not"]))]
    if "between" in cond:
        name, lo, hi = cond["between"]
        return [f"{_label(name)} {_fmt(name, lo)} ~ {_fmt(name, hi)}"]
    if "sector_any" in cond:
        return ["업종: " + ", ".join(cond["sector_any"])]
    if "market_in" in cond:
        return ["시장: " + ", ".join(cond["market_in"])]
    words = {">=": "이상", ">": "초과", "<=": "이하", "<": "미만", "==": "="}
    return [f"{_label(cond['f'])} {_fmt(cond['f'], cond['v'])} {words[cond['op']]}"]


def _label(name: str) -> str:
    d = REGISTRY.get(name)
    return d.label if d else name


def _fmt(name: str, v: float) -> str:
    unit = REGISTRY[name].unit if name in REGISTRY else ""
    if unit == "ratio":
        return f"{v * 100:+.1f}%" if name in ("r1", "gap_open", "r5", "r20", "r60", "mom_120_5", "dd20", "dd120",
                                               "gap_ma20", "gap_ma60", "gap_ma120", "rs_sector20") else f"{v * 100:.1f}%"
    if unit == "krw":
        return f"{v / 1e8:,.0f}억원" if v >= 1e8 else f"{v:,.0f}원"
    if unit == "x":
        return f"{v:g}배"
    return f"{v:g}"
