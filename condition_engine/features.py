"""Feature registry — the single catalogue the engine, the UI and the LLM translator share.

Rules:
  * A feature at date t reads rows <= t only.
  * A lookback window that spans a corporate-action jump yields NaN.
  * Every feature states what it means and, where the research measured it,
    what the evidence said (research/redesign_20261012).
"""
from __future__ import annotations

from dataclasses import dataclass, field
from typing import Callable

import numpy as np
import pandas as pd

from .panel import Panel, clean_lookback

F32 = "float32"


@dataclass(frozen=True)
class FeatureDef:
    name: str
    label: str            # Korean label shown to members
    unit: str             # ratio | x | krw | count | flag | score
    lookback: int
    description: str
    evidence: str = ""    # research verdict, empty when untested
    example: str = ""     # a DSL leaf a translator can copy


REGISTRY: dict[str, FeatureDef] = {}


def _reg(d: FeatureDef) -> None:
    REGISTRY[d.name] = d


for d in [
    FeatureDef("close", "종가", "krw", 1, "당일 종가", example='{"f":"close","op":">=","v":5000}'),
    FeatureDef("r1", "당일 등락률", "ratio", 2, "전일 종가 대비 당일 종가 변화율 (0.05 = +5%)",
               "당일 급등 추격은 검증한 전략 중 최악(5일 초과 -2.58% / -4.02%). 상승률은 조건보다 위험 신호로 쓴다",
               '{"f":"r1","op":">=","v":0.03}'),
    FeatureDef("gap_open", "시가 갭", "ratio", 2, "전일 종가 대비 당일 시가 변화율"),
    FeatureDef("clv", "종가 위치", "ratio", 1, "당일 고저 범위 안에서 종가 위치 (1 = 고가 마감, 0 = 저가 마감)"),
    FeatureDef("upper_wick", "윗꼬리 비율", "ratio", 1, "고가와 몸통 상단 사이 길이 / 고저 범위"),
    FeatureDef("up_streak", "연속 상승일", "count", 10, "연속으로 종가가 오른 일수 (최대 10)"),
    FeatureDef("r5", "5일 수익률", "ratio", 6, "5거래일 전 대비 수익률"),
    FeatureDef("r20", "20일 수익률", "ratio", 21, "20거래일 전 대비 수익률",
               "중기 모멘텀은 두 구간에서 재현되지 않음"),
    FeatureDef("r60", "60일 수익률", "ratio", 61, "60거래일 전 대비 수익률"),
    FeatureDef("mom_120_5", "중기 모멘텀", "ratio", 121, "최근 1주를 뺀 6개월 수익률 (5일 전 / 120일 전)",
               "재현되지 않음 — 단독 조건으로 쓰지 않는다"),
    FeatureDef("gap_ma20", "20일선 이격", "ratio", 20, "종가 / 20일 이동평균 - 1 (양수 = 20일선 위)",
               example='{"f":"gap_ma20","op":">","v":0}'),
    FeatureDef("gap_ma60", "60일선 이격", "ratio", 60, "종가 / 60일 이동평균 - 1",
               example='{"f":"gap_ma60","op":">","v":0}'),
    FeatureDef("gap_ma120", "120일선 이격", "ratio", 120, "종가 / 120일 이동평균 - 1"),
    FeatureDef("dd20", "20일 고가 대비", "ratio", 20, "종가 / 20일 최고가 - 1 (0 = 고가, -0.05 = 고가 대비 5% 아래)",
               "견고한 양의 신호: 고가에 가까울수록 이후 수익이 높았다 (5일 IC +0.053 / +0.083)",
               '{"between":["dd20",-0.08,-0.02]}'),
    FeatureDef("dd120", "120일 고가 대비", "ratio", 120, "종가 / 120일 최고가 - 1", "대체로 양의 신호"),
    FeatureDef("new_high120", "120일 신고가", "flag", 120, "종가가 직전 119일 최고 종가 이상이면 1",
               "거래량 동반 신고가 돌파는 음수(5일 -2.47%)"),
    FeatureDef("volr", "거래량 배수", "x", 21, "당일 거래량 / 직전 20일 평균 거래량",
               "단독으로는 재현되지 않음", '{"f":"volr","op":">=","v":1.5}'),
    FeatureDef("val_today", "당일 거래대금", "krw", 1, "종가 × 거래량 (원)", example='{"f":"val_today","op":">=","v":10000000000}'),
    FeatureDef("val_avg20", "20일 평균 거래대금", "krw", 20, "최근 20일 평균 거래대금 (원)"),
    FeatureDef("vol20", "20일 변동성", "ratio", 21, "일간 수익률 20일 표준편차",
               "가장 견고한 음의 신호: 변동성이 높을수록 이후 부진 (5일 IC -0.113 / -0.125)"),
    FeatureDef("atr14_pct", "ATR 비율", "ratio", 15, "14일 평균 진폭 / 종가",
               "가장 견고한 음의 신호 (5일 IC -0.117 / -0.137, 대형주에서도 동일)",
               '{"f":"atr14_pct","op":"<=","v":0.04}'),
    FeatureDef("rs_sector20", "업종 대비 20일 강도", "ratio", 21,
               "20일 수익률 - 같은 업종 종목 20일 수익률 중앙값 (업종 8종목 미만이면 없음)",
               "미검증 — 시장 대비 강도는 횡단면 정보가 0이라 업종 대비로 대체"),
    FeatureDef("is_kospi", "코스피 여부", "flag", 0, "코스피 상장이면 1"),
    FeatureDef("is_kosdaq", "코스닥 여부", "flag", 0, "코스닥 상장이면 1"),
    FeatureDef("quality_score", "엔진 기본 점수", "score", 20,
               "검증된 두 신호의 결합: 20일 고가 근접(가점) + 낮은 ATR(가점). 같은 날 백분위 평균, -1~1",
               "두 구성 신호 모두 두 구간·두 유니버스에서 같은 방향으로 재현"),
]:
    _reg(d)


def compute_features(panel: Panel) -> dict[str, pd.DataFrame]:
    c, o, h, l, v, val = panel.close, panel.open, panel.high, panel.low, panel.volume, panel.value
    prev_c = c.shift(1)
    g = lambda frame, w: frame.where(clean_lookback(panel, w))  # noqa: E731
    f: dict[str, pd.DataFrame] = {}

    f["close"] = c
    f["r1"] = g(c / prev_c - 1, 2)
    f["gap_open"] = g(o / prev_c - 1, 2)
    rng = (h - l).where((h - l) > 0)
    f["clv"] = (c - l) / rng
    f["upper_wick"] = (h - np.maximum(o, c)) / rng

    up = (c > prev_c).astype(float).where(c.notna() & prev_c.notna())
    streak = np.zeros(c.shape[1])
    rows = []
    up_np = up.to_numpy()
    for i in range(len(c)):
        row = up_np[i]
        streak = np.where(row == 1, streak + 1, 0.0)
        rows.append(np.where(np.isnan(row), np.nan, streak))
    f["up_streak"] = pd.DataFrame(np.minimum(np.array(rows), 10), index=c.index, columns=c.columns)

    f["r5"] = g(c / c.shift(5) - 1, 6)
    f["r20"] = g(c / c.shift(20) - 1, 21)
    f["r60"] = g(c / c.shift(60) - 1, 61)
    f["mom_120_5"] = g(c.shift(5) / c.shift(120) - 1, 121)
    for w in (20, 60, 120):
        f[f"gap_ma{w}"] = g(c / c.rolling(w, min_periods=w).mean() - 1, w)
    f["dd20"] = g(c / h.rolling(20, min_periods=20).max() - 1, 20)
    f["dd120"] = g(c / h.rolling(120, min_periods=120).max() - 1, 120)
    prior_hi = c.shift(1).rolling(119, min_periods=119).max()
    f["new_high120"] = g((c >= prior_hi).astype(float).where(prior_hi.notna() & c.notna()), 120)

    f["volr"] = v / v.shift(1).rolling(20, min_periods=15).mean()
    f["val_today"] = val
    f["val_avg20"] = val.rolling(20, min_periods=15).mean()

    ret = c / prev_c - 1
    f["vol20"] = g(ret.rolling(20, min_periods=15).std(), 21)
    tr = np.fmax(np.fmax((h - l).to_numpy(), (h - prev_c).abs().to_numpy()), (l - prev_c).abs().to_numpy())
    tr = pd.DataFrame(tr, index=c.index, columns=c.columns)
    f["atr14_pct"] = g(tr.rolling(14, min_periods=14).mean() / c, 15)

    f["rs_sector20"] = _sector_relative(f["r20"], panel)
    market = panel.meta["market"] if "market" in panel.meta else pd.Series(index=c.columns, dtype=object)
    f["is_kospi"] = pd.DataFrame(np.tile((market == "KOSPI").to_numpy(float), (len(c), 1)), index=c.index, columns=c.columns).where(c.notna())
    f["is_kosdaq"] = pd.DataFrame(np.tile((market == "KOSDAQ").to_numpy(float), (len(c), 1)), index=c.index, columns=c.columns).where(c.notna())
    return {k: frame.astype(F32) for k, frame in f.items()}


def add_quality_score(feats: dict[str, pd.DataFrame], universe: pd.DataFrame) -> None:
    """Default ranking built only from signals that held in both windows and both universes."""
    near_high = feats["dd20"].where(universe).rank(axis=1, pct=True)
    calm = 1 - feats["atr14_pct"].where(universe).rank(axis=1, pct=True)
    feats["quality_score"] = (near_high + calm - 1).astype(F32)


def _sector_relative(r20: pd.DataFrame, panel: Panel, min_peers: int = 8) -> pd.DataFrame:
    if "sector" not in panel.meta or panel.meta["sector"].isna().all():
        return pd.DataFrame(np.nan, index=r20.index, columns=r20.columns)
    sectors = panel.meta["sector"].reindex(r20.columns)
    out = pd.DataFrame(np.nan, index=r20.index, columns=r20.columns)
    for sector, cols in sectors.groupby(sectors).groups.items():
        cols = list(cols)
        if len(cols) < min_peers:
            continue
        block = r20[cols]
        median = block.median(axis=1)
        peers = block.notna().sum(axis=1)
        out[cols] = block.sub(median, axis=0).where(peers >= min_peers, axis=0)
    return out


def universe_mask(panel: Panel, feats: dict[str, pd.DataFrame], min_price: float = 1_000,
                  min_value_avg20: float = 1e9, min_history: int = 120) -> pd.DataFrame:
    seasoned = panel.close.notna().cumsum() >= min_history
    return (panel.close.ge(min_price) & feats["val_avg20"].ge(min_value_avg20)
            & seasoned & panel.volume.gt(0))


def catalogue() -> list[dict]:
    return [d.__dict__ for d in REGISTRY.values()]
