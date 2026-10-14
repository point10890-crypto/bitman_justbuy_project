"""Condition DSL — the contract between the AI agent and the deterministic engine.

In the redesign the LLM never picks stocks and never emits prices. It translates
a member's request ("20일선 눌림인데 거래대금 붙은 종목") into this DSL. The engine
validates the DSL against a whitelist and evaluates it on point-in-time
features. A request the DSL cannot express is answered "not supported", not
approximated by free text.

Grammar (JSON):
  cond     := {"all": [cond...]} | {"any": [cond...]} | {"not": cond}
            | {"f": FEATURE, "op": OP, "v": NUMBER}
            | {"between": [FEATURE, LOW, HIGH]}
  strategy := {"id", "title", "when": cond, "rank_by": {"f": FEATURE, "desc": bool}, "top_n": int}
"""
from __future__ import annotations

import operator
from typing import Any, Mapping

import pandas as pd

OPS = {">=": operator.ge, ">": operator.gt, "<=": operator.le, "<": operator.lt, "==": operator.eq}
MAX_DEPTH = 6
MAX_TOP_N = 50


class DslError(ValueError):
    pass


def validate(cond: Any, features: set[str], depth: int = 0) -> None:
    if depth > MAX_DEPTH:
        raise DslError("condition nested too deeply")
    if not isinstance(cond, Mapping) or len(cond) == 0:
        raise DslError(f"condition must be a non-empty object: {cond!r}")
    if "all" in cond or "any" in cond:
        key = "all" if "all" in cond else "any"
        items = cond[key]
        if not isinstance(items, list) or not items:
            raise DslError(f"'{key}' needs a non-empty list")
        for item in items:
            validate(item, features, depth + 1)
        return
    if "not" in cond:
        validate(cond["not"], features, depth + 1)
        return
    if "between" in cond:
        name, lo, hi = cond["between"]
        _check_feature(name, features)
        if not all(isinstance(x, (int, float)) for x in (lo, hi)) or lo > hi:
            raise DslError(f"bad between bounds for {name}: {lo}, {hi}")
        return
    if {"f", "op", "v"} <= set(cond):
        _check_feature(cond["f"], features)
        if cond["op"] not in OPS:
            raise DslError(f"unsupported operator {cond['op']!r}")
        if not isinstance(cond["v"], (int, float)):
            raise DslError(f"value must be numeric: {cond['v']!r}")
        return
    raise DslError(f"unrecognised condition: {cond!r}")


def _check_feature(name: str, features: set[str]) -> None:
    if name not in features:
        raise DslError(f"unknown feature {name!r}")


def evaluate(cond: Mapping, feats: Mapping[str, pd.DataFrame]) -> pd.DataFrame:
    """Boolean [date x ticker]. Missing feature values evaluate to False."""
    if "all" in cond:
        out = None
        for item in cond["all"]:
            m = evaluate(item, feats)
            out = m if out is None else (out & m)
        return out
    if "any" in cond:
        out = None
        for item in cond["any"]:
            m = evaluate(item, feats)
            out = m if out is None else (out | m)
        return out
    if "not" in cond:
        inner = cond["not"]
        # NOT over a missing value stays False: absence of data is not evidence.
        return ~evaluate(inner, feats) & _known(inner, feats)
    if "between" in cond:
        name, lo, hi = cond["between"]
        x = feats[name]
        return (x >= lo) & (x <= hi)
    x = feats[cond["f"]]
    return OPS[cond["op"]](x, cond["v"]).fillna(False) & x.notna()


def _known(cond: Mapping, feats: Mapping[str, pd.DataFrame]) -> pd.DataFrame:
    names = _features_in(cond)
    out = None
    for n in names:
        k = feats[n].notna()
        out = k if out is None else (out & k)
    return out


def _features_in(cond: Mapping) -> list[str]:
    if "all" in cond or "any" in cond:
        return [n for c in cond.get("all", cond.get("any")) for n in _features_in(c)]
    if "not" in cond:
        return _features_in(cond["not"])
    if "between" in cond:
        return [cond["between"][0]]
    return [cond["f"]]


def validate_strategy(strategy: Mapping, features: set[str]) -> None:
    for key in ("id", "title", "when", "rank_by", "top_n"):
        if key not in strategy:
            raise DslError(f"strategy missing {key!r}")
    validate(strategy["when"], features)
    _check_feature(strategy["rank_by"]["f"], features)
    if not (1 <= int(strategy["top_n"]) <= MAX_TOP_N):
        raise DslError("top_n out of range")


def select(strategy: Mapping, feats: Mapping[str, pd.DataFrame], universe: pd.DataFrame) -> pd.DataFrame:
    """Boolean [date x ticker] of the top_n names passing the condition each day."""
    passing = evaluate(strategy["when"], feats) & universe
    key = feats[strategy["rank_by"]["f"]].where(passing)
    ascending = not strategy["rank_by"].get("desc", True)
    rank = key.rank(axis=1, ascending=ascending, method="first")
    return rank.le(int(strategy["top_n"])) & passing
