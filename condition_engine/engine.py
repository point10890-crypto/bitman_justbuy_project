"""The engine facade: one object, one code path for screening and backtesting."""
from __future__ import annotations

import math
from collections import OrderedDict
from typing import Mapping

import numpy as np
import pandas as pd

from . import config, dsl
from .evaluation import (benchmark_forward, excess_vs_universe, forward_returns, split_windows,
                         verdict, window_stats)
from .explain import explain_row
from .features import REGISTRY, add_quality_score, compute_features, universe_mask
from .panel import Panel, build_panel
from .risk import FLAG_INFO, REGIME_INFO, describe_row, regime_series, risk_flags

SNAPSHOT_FEATURES = ["close", "r1", "volr", "dd20", "dd120", "gap_ma20", "gap_ma60",
                     "atr14_pct", "vol20", "val_today", "val_avg20", "rs_sector20", "quality_score"]
EXCLUDED_PREVIEW = 20


class Engine:
    def __init__(self, panel: Panel, **universe_kwargs):
        self.panel = panel
        self.feats = compute_features(panel)
        params = {**config.UNIVERSE, **universe_kwargs}
        self.universe = universe_mask(panel, self.feats, **params)
        add_quality_score(self.feats, self.universe)
        self.flags = risk_flags(self.feats, self.universe)
        self.regime = regime_series(panel.benchmark["KOSPI200"])
        self.ctx = dsl.Context(self.feats, panel.meta)
        self.features = set(self.feats)
        self._labels: dict = {}
        self._cache: OrderedDict = OrderedDict()
        last = panel.dates[-1] if len(panel.dates) else None
        self.data_version = f"{last.date() if last is not None else 'empty'}:{panel.close.shape[1]}"

    @classmethod
    def from_store(cls, store) -> "Engine":
        return cls(build_panel(store.final_bars(), store.load_listing(), store.load_names()))

    # ------------------------------------------------------------ selection
    def _stages(self, strategy: Mapping) -> dict[str, pd.DataFrame]:
        passing = dsl.evaluate(strategy["when"], self.ctx) & self.universe
        guard = strategy.get("guard", {})
        excluded = pd.DataFrame(False, index=passing.index, columns=passing.columns)
        reasons = {}
        for flag in guard.get("exclude_risk", []):
            hit = passing & self.flags[flag].fillna(False)
            reasons[flag] = hit
            excluded |= hit
        regime_ok = pd.Series(True, index=passing.index)
        if guard.get("regime_in"):
            regime_ok = self.regime.isin(guard["regime_in"])
        eligible = passing & ~excluded & pd.DataFrame(
            np.repeat(regime_ok.to_numpy()[:, None], passing.shape[1], axis=1),
            index=passing.index, columns=passing.columns)
        key = self.feats[strategy["rank_by"]["f"]].where(eligible)
        rank = key.rank(axis=1, ascending=not strategy["rank_by"].get("desc", True), method="first")
        published = rank.le(int(strategy["top_n"])) & eligible
        return {"passing": passing, "excluded": excluded, "reasons": reasons, "regime_ok": regime_ok,
                "eligible": eligible, "rank": rank, "published": published}

    # ------------------------------------------------------------ screening
    def screen(self, strategy: Mapping, as_of: str | None = None) -> dict:
        strategy = dsl.validate_strategy(strategy, self.features)
        t = self._resolve_date(as_of)
        st = self._stages(strategy)
        regime = self.regime.get(t)
        row_rank = st["rank"].loc[t]

        def row(code: str, stage: str, reason: list[str] | None = None) -> dict:
            snap = {f: _num(self.feats[f].at[t, code]) for f in SNAPSHOT_FEATURES}
            flags_today = {k: bool(self.flags[k].at[t, code]) for k in FLAG_INFO}
            meta = self.panel.meta.loc[code] if code in self.panel.meta.index else pd.Series(dtype=object)
            return {
                "code": code, "name": _s(meta.get("name")), "market": _s(meta.get("market")),
                "sector": _s(meta.get("sector")), "industry": _s(meta.get("industry")),
                "stage": stage, "excluded_by": reason or [],
                "rank": _num(row_rank.get(code)), "snapshot": snap,
                "explanation": explain_row(snap),
                "risk": describe_row(flags_today, snap.get("atr14_pct"), snap.get("close")),
            }

        published_codes = row_rank[st["published"].loc[t]].sort_values().index.tolist()
        below_codes = row_rank[st["eligible"].loc[t] & ~st["published"].loc[t]].sort_values().index.tolist()
        excluded_today = st["excluded"].loc[t]
        excluded_codes = excluded_today[excluded_today].index.tolist()
        # Show excluded names in the order they would have ranked, so a member sees what the guard removed.
        key = self.feats[strategy["rank_by"]["f"]].loc[t]
        desc = strategy["rank_by"].get("desc", True)
        excluded_codes.sort(key=lambda c: (pd.isna(key.get(c)), -key.get(c) if desc else key.get(c)))

        reasons_today = {code: [f for f, m in st["reasons"].items() if bool(m.at[t, code])] for code in excluded_codes}
        regime_blocked = not bool(st["regime_ok"].get(t, True))
        return {
            "strategy": {k: strategy[k] for k in ("id", "title", "when", "rank_by", "top_n", "guard")},
            "conditions": dsl.describe(strategy["when"]),
            "as_of": str(t.date()),
            "regime": {"code": regime, "label": REGIME_INFO.get(regime, "판단 불가")},
            "regime_blocked": regime_blocked,
            "counts": {
                "universe": int(self.universe.loc[t].sum()),
                "passing": int(st["passing"].loc[t].sum()),
                "guard_excluded": len(excluded_codes),
                "eligible": int(st["eligible"].loc[t].sum()),
                "published": len(published_codes),
            },
            "results": [row(c, "PUBLISHED") for c in published_codes],
            "excluded": [row(c, "GUARD_EXCLUDED", reasons_today[c]) for c in excluded_codes[:EXCLUDED_PREVIEW]],
            "below_top_n": below_codes,
            "excluded_codes": {c: reasons_today[c] for c in excluded_codes},
            "data_version": self.data_version,
        }

    # ------------------------------------------------------------ backtest
    def labels(self, h: int, entry: str):
        key = (h, entry)
        if key not in self._labels:
            fwd = forward_returns(self.panel, h, entry)
            self._labels[key] = (fwd, excess_vs_universe(fwd, self.universe), benchmark_forward(self.panel, h))
        return self._labels[key]

    def backtest(self, strategy: Mapping, horizons=config.HORIZONS, entry: str = "open1") -> dict:
        strategy = dsl.validate_strategy(strategy, self.features)
        cache_key = (dsl.dsl_hash(strategy), tuple(horizons), entry, self.data_version)
        if cache_key in self._cache:
            self._cache.move_to_end(cache_key)
            return self._cache[cache_key]

        selected = self._stages(strategy)["published"]
        windows = split_windows(self.panel.dates)
        result = {
            "strategy": {k: strategy[k] for k in ("id", "title", "when", "rank_by", "top_n", "guard")},
            "conditions": dsl.describe(strategy["when"]),
            "entry": entry,
            "period": {w: [str(ix[0].date()), str(ix[-1].date()), int(len(ix))] for w, ix in windows.items() if len(ix)},
            "horizons": {},
            "data_version": self.data_version,
            "method": "같은 날 유니버스 평균 대비 초과수익. 탐색 70% / 확인 30%. 왕복 비용 0.23%. "
                      "보유기간이 겹치는 표본은 h일 간격으로 t값 계산.",
        }
        for h in horizons:
            fwd, exc, bench = self.labels(h, entry)
            stats = {w: window_stats(selected, fwd, exc, bench, h, ix) for w, ix in windows.items()}
            stats["verdict"] = verdict(stats["discovery"], stats["confirm"])
            result["horizons"][str(h)] = stats

        fwd5, exc5, bench5 = self.labels(5, entry)
        day_exc = exc5.where(selected).mean(axis=1)
        day_raw = fwd5.where(selected).mean(axis=1)
        result["by_regime"] = {
            reg: _summ(day_exc[(self.regime == reg).to_numpy()]) for reg in REGIME_INFO
        }
        result["by_year"] = {str(y): _summ(s) for y, s in day_exc.groupby(day_exc.index.year)}
        result["curve"] = _curve(day_exc, day_raw, self.panel.benchmark["KOSPI200"], hold=5, step=5)

        if strategy["guard"].get("exclude_risk"):
            unguarded = {**strategy, "guard": {k: v for k, v in strategy["guard"].items() if k != "exclude_risk"}}
            sel_u = self._stages(unguarded)["published"]
            with_g = window_stats(selected, fwd5, exc5, bench5, 5, windows["all"])
            without = window_stats(sel_u, fwd5, exc5, bench5, 5, windows["all"])
            result["guard_effect"] = {
                "excluded_flags": strategy["guard"]["exclude_risk"],
                "with_guard_excess_pct": with_g.get("excess_vs_universe_pct"),
                "without_guard_excess_pct": without.get("excess_vs_universe_pct"),
            }

        self._cache[cache_key] = result
        if len(self._cache) > 64:
            self._cache.popitem(last=False)
        return result

    # ------------------------------------------------------------ risk check
    def risk_check(self, codes: list[str], as_of: str | None = None) -> dict:
        t = self._resolve_date(as_of)
        out = []
        for code in codes:
            code = str(code).zfill(6)
            if code not in self.panel.close.columns:
                out.append({"code": code, "found": False})
                continue
            snap = {f: _num(self.feats[f].at[t, code]) for f in SNAPSHOT_FEATURES}
            flags_today = {k: bool(self.flags[k].at[t, code]) for k in FLAG_INFO}
            meta = self.panel.meta.loc[code]
            out.append({"code": code, "found": True, "name": _s(meta.get("name")),
                        "in_universe": bool(self.universe.at[t, code]), "snapshot": snap,
                        "explanation": explain_row(snap),
                        "risk": describe_row(flags_today, snap.get("atr14_pct"), snap.get("close"))})
        return {"as_of": str(t.date()), "items": out}

    def regime_now(self, history: int = 120) -> dict:
        s = self.regime.dropna()
        last = s.index[-1] if len(s) else None
        return {"as_of": str(last.date()) if last is not None else None,
                "code": s.iloc[-1] if len(s) else None,
                "label": REGIME_INFO.get(s.iloc[-1]) if len(s) else None,
                "history": [{"date": str(d.date()), "code": v} for d, v in s.tail(history).items()]}

    def _resolve_date(self, as_of: str | None) -> pd.Timestamp:
        if as_of is None:
            return self.panel.dates[-1]
        t = pd.Timestamp(as_of)
        valid = self.panel.dates[self.panel.dates <= t]
        if len(valid) == 0:
            raise dsl.DslError(f"{as_of} 이전 데이터가 없습니다")
        return valid[-1]


def _num(x):
    try:
        x = float(x)
    except (TypeError, ValueError):
        return None
    return x if math.isfinite(x) else None


def _s(x):
    return x if isinstance(x, str) else None


def _summ(series: pd.Series) -> dict:
    s = series.dropna()
    return {"signal_days": int(len(s)), "excess_vs_universe_pct": round(float(s.mean()) * 100, 2) if len(s) else None}


def _curve(day_exc: pd.Series, day_raw: pd.Series, index_close: pd.Series, hold: int, step: int) -> list[dict]:
    """Cumulative excess, strategy and index from the first signal day.

    The strategy is a staggered book: each day 1/hold of the capital buys that day's picks
    and holds them `hold` sessions, so a day contributes its hold-day return / hold.
    Sampling one day in `hold` instead would follow a single phase of the calendar, and
    one lucky phase can show +100% for a signal whose daily mean is +0.1%.
    Days without a signal count as cash. The index line is the KOSPI200 ETF close.
    """
    first = day_exc.first_valid_index()
    if first is None:
        return []
    exc = day_exc.loc[first:].fillna(0.0) / hold
    raw = day_raw.loc[first:].fillna(0.0) / hold
    ce, cr = (1 + exc).cumprod(), (1 + raw).cumprod()
    ix = index_close.reindex(exc.index).ffill().bfill()
    cb = ix / ix.iloc[0] if ix.notna().any() and ix.iloc[0] else pd.Series(1.0, index=exc.index)
    picks = list(range(0, len(exc), step))
    if picks[-1] != len(exc) - 1:
        picks.append(len(exc) - 1)
    out = []
    for i in picks:
        d = exc.index[i]
        out.append({"date": str(d.date()), "excess_pct": round((ce.iloc[i] - 1) * 100, 2),
                    "strategy_pct": round((cr.iloc[i] - 1) * 100, 2), "index_pct": round((cb.iloc[i] - 1) * 100, 2)})
    return out
