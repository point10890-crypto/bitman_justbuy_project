"""Section detectors — the five home sections, designed and validated in
research/detectors_20261014 (see docs/SECTION_DETECTION_DESIGN_2026_10_14.md).

Every rule here is the frozen research design; a parity test pins them to the harness.
Timing matters as much as the rule:

  leaders / swing   signal at a final close, buy the next open, hold 20 / 10 sessions
  short_term        watchlist at a final close; it triggers only if the next session opens
                    with a gap below -0.5% (checked with the live open), buy that open,
                    sell the same day's close
  themes            the sectors that ignited (breadth + money); their money leader is shown
                    as information — chasing it lost money in every research window
  closing_bet       laggards of an ignited sector, bought at the close and sold at the next
                    close

These masks run on a final-close panel here (the end-of-day list) and on a live panel in
scanner.py, the intraday condition screener that the home cards show.
"""
from __future__ import annotations

import logging
import threading
import time
from concurrent.futures import ThreadPoolExecutor
from datetime import date, datetime

import numpy as np
import pandas as pd

from . import config
from .engine import Engine
from .store import BarStore, fdr_fetch, now_kst

log = logging.getLogger(__name__)

LIQUID_VALUE = 5e9               # 20-day average trading value, 50억
TOP_N = 5

DESIGNS = {
    "short_term": {
        "title": "단타", "thesis": "상승 추세가 살아 있는 종목이 과매도로 밀린 뒤 갭 하락으로 출발하면 장중에 되돌아온다",
        "entry": "다음 거래일 시가가 전일 종가보다 0.5% 이상 낮게 시작하면 시가 매수", "exit": "당일 종가 청산",
        "evidence": {"A": 0.77, "B": 0.13, "C": 0.63, "unit": "1일 장중, 같은 날 대비 초과 %"},
        "caution": "비용(약 0.2%)을 빼면 얇은 우위다. 분봉 검증 전까지 조건부 설계",
        "avoid": "전날 오른 종목이 +3% 이상 갭 상승할 때 시가에 사면 당일 -2.21% / -1.04% / -1.95% (세 구간 모두)",
    },
    "swing": {
        "title": "스윙", "thesis": "검증된 주도주가 3~10% 눌렸을 때만 산다 — 추세가 살아 있는 리더의 일시 조정",
        "entry": "다음 거래일 시가 매수", "exit": "10거래일 보유 후 청산",
        "evidence": {"A": 1.47, "B": 0.74, "C": 2.18, "unit": "10일, 같은 날 대비 초과 %"},
        "caution": "보류 구간을 두 번째로 확인한 설계 — 운영 원장으로 실측 중",
    },
    "leaders": {
        "title": "주도주", "thesis": "상대강도 상위 · 52주 고가 근처 · 추세 정렬 · 과열 아님 — 시장을 이끄는 종목은 오래 이끈다",
        "entry": "다음 거래일 시가 매수", "exit": "20거래일 보유 후 청산",
        "evidence": {"A": 2.60, "B": 3.35, "C": 6.04, "unit": "20일, 같은 날 대비 초과 %"},
        "caution": "하락 국면에서는 약했다(보류 구간 -2.13%)",
    },
    "themes": {
        "title": "테마주", "thesis": "업종이 함께 급등한 날을 테마 점화로 본다. 대장주 추격은 손실, 후발주가 따라잡는다",
        "entry": "대장주는 매수 대상이 아니다 — 같은 테마의 후발주는 종가매매에서 제공", "exit": "-",
        "evidence": {"A": -1.05, "B": 0.22, "C": None, "unit": "점화 다음 날 대장주 시가 매수 1일 초과 %"},
        "caution": "테마는 업종(KSIC)으로 근사 — 업종을 넘나드는 테마는 잡지 못한다",
    },
    "closing_bet": {
        "title": "종가매매", "thesis": "테마가 점화된 업종에서 아직 반응하지 않은 후발주가 다음 날 따라잡는다",
        "entry": "당일 종가(15:20~15:30 동시호가) 매수", "exit": "다음 거래일 종가 청산",
        "evidence": {"A": 0.77, "B": 0.45, "C": 0.67, "unit": "1일, 같은 날 대비 초과 %"},
        "caution": "점화 업종이 있는 날만 신호가 나온다(연 60~80일)",
    },
}


# ------------------------------------------------------------------- features
def _rsi(c: pd.DataFrame, n: int) -> pd.DataFrame:
    diff = c.diff()
    up = diff.clip(lower=0).ewm(alpha=1 / n, adjust=False, min_periods=n).mean()
    dn = (-diff.clip(upper=0)).ewm(alpha=1 / n, adjust=False, min_periods=n).mean()
    return 100 - 100 / (1 + up / dn.replace(0, np.nan))


def section_features(eng: Engine) -> dict:
    """The extra features of the research harness, computed the same way."""
    p, f, uni = eng.panel, eng.feats, eng.universe
    c, h = p.close, p.high
    x: dict[str, pd.DataFrame] = {}
    x["ma20"], x["ma60"], x["ma120"] = (c.rolling(w, min_periods=w).mean() for w in (20, 60, 120))
    x["ma60_slope20"] = x["ma60"] / x["ma60"].shift(20) - 1
    x["rsi2"] = _rsi(c, 2)
    x["hi250"] = h.rolling(250, min_periods=200).max()
    rs_raw = 0.4 * f["r20"] + 0.3 * f["r60"] + 0.3 * (c / c.shift(120) - 1)
    x["rs_pct"] = rs_raw.where(uni).rank(axis=1, pct=True)
    x.update(_sector_aggregates(eng))
    x["liquid"] = uni & (f["val_avg20"] >= LIQUID_VALUE)
    return x


def _sector_aggregates(eng: Engine) -> dict:
    p, f, uni = eng.panel, eng.feats, eng.universe
    sec = p.meta["sector"].reindex(p.close.columns)
    r1 = f["r1"].where(uni)
    r60 = f["r60"].where(uni)
    shape = p.close.shape
    breadth, count, surge, sec_r60 = (np.full(shape, np.nan) for _ in range(4))
    cols = {code: i for i, code in enumerate(p.close.columns)}
    for _, codes in sec.dropna().groupby(sec.dropna()).groups.items():
        codes = [code for code in codes if code in cols]
        if len(codes) < 4:
            continue
        idx = [cols[code] for code in codes]
        m = uni[codes]
        n = m.sum(axis=1)
        ok = n >= 4
        b = ((r1[codes] >= 0.03).sum(axis=1) / n).where(ok)
        vs = (p.value[codes].where(m).sum(axis=1) / f["val_avg20"][codes].where(m).sum(axis=1)).where(ok)
        med = r60[codes].median(axis=1).where(ok)
        for arr, series in ((breadth, b), (count, n.where(ok)), (surge, vs), (sec_r60, med)):
            arr[:, idx] = series.to_numpy()[:, None]
    mk = lambda a: pd.DataFrame(a, index=p.close.index, columns=p.close.columns)  # noqa: E731
    sec_r60_df = mk(sec_r60)
    return {"sec_breadth": mk(breadth), "sec_n": mk(count), "sec_value_surge": mk(surge),
            "sec_r60_pct": sec_r60_df.where(uni).rank(axis=1, pct=True)}


# ------------------------------------------------------------------- rules (frozen)
def leader_mask(eng: Engine, x: dict) -> pd.DataFrame:
    p, f = eng.panel, eng.feats
    c = p.close
    return ((x["rs_pct"] >= 0.85) & (c >= 0.9 * x["hi250"]) & (c > x["ma60"]) & (x["ma60"] > x["ma120"])
            & (f["gap_ma20"] < 0.15) & x["liquid"] & eng.universe)


def swing_mask(eng: Engine, x: dict, leaders: pd.DataFrame) -> pd.DataFrame:
    f, c = eng.feats, eng.panel.close
    return (leaders.shift(5, fill_value=False) & (f["dd20"] >= -0.10) & (f["dd20"] <= -0.03)
            & (c > x["ma60"]) & x["liquid"] & eng.universe)


def short_term_watch(eng: Engine, x: dict) -> pd.DataFrame:
    c = eng.panel.close
    trend = (c > x["ma60"]) & (x["ma60_slope20"] > 0)
    return trend & x["liquid"] & (x["rsi2"] < 10) & eng.universe


def ignition_mask(x: dict) -> pd.DataFrame:
    return (x["sec_breadth"] >= 0.4) & (x["sec_value_surge"] >= 2) & (x["sec_n"] >= 5)


def laggard_mask(eng: Engine, x: dict) -> pd.DataFrame:
    f, c = eng.feats, eng.panel.close
    return ignition_mask(x) & x["liquid"] & (f["r1"] < 0.01) & (c > x["ma60"]) & eng.universe


# ------------------------------------------------------------------- live quotes
class LiveQuotes:
    """Today's partial bars from FinanceDataReader (cached briefly)."""

    def __init__(self, fetcher=fdr_fetch, ttl: float = 60.0, threads: int = 8):
        self.fetcher, self.ttl, self.threads = fetcher, ttl, threads
        self._cache: dict[str, tuple[float, dict | None]] = {}
        self._lock = threading.Lock()

    def get(self, codes: list[str], today: date) -> dict[str, dict]:
        now = time.time()
        need = [c for c in codes if c not in self._cache or now - self._cache[c][0] > self.ttl]

        def one(code):
            try:
                df = self.fetcher(code, today, today)
                if df is None or df.empty:
                    return code, None
                row = df.iloc[-1]
                if pd.Timestamp(row["date"]).date() != today:
                    return code, None
                return code, {k: float(row[k]) for k in ("open", "high", "low", "close", "volume")}
            except Exception:
                return code, None

        if need:
            with ThreadPoolExecutor(max_workers=self.threads) as pool:
                for code, bar in pool.map(one, need):
                    with self._lock:
                        self._cache[code] = (now, bar)
        return {c: self._cache[c][1] for c in codes if c in self._cache and self._cache[c][1]}


# ------------------------------------------------------------------- service
def _fmt_pct(v: float | None, digits: int = 1) -> str:
    return "-" if v is None or not np.isfinite(v) else f"{v * 100:+.{digits}f}%"


class SectionDetectors:
    """Computes the five sections for the latest session (and live overlays)."""

    def __init__(self, engine: Engine, store: BarStore | None = None, live: LiveQuotes | None = None,
                 clock=now_kst):
        self.eng = engine
        self.store = store
        self.live = live or LiveQuotes()
        self.clock = clock
        self.x = section_features(engine)
        self.leaders = leader_mask(engine, self.x)

    # ---- helpers
    def _t(self) -> pd.Timestamp:
        return self.eng.panel.dates[-1]

    def _meta(self, code: str) -> dict:
        m = self.eng.panel.meta
        row = m.loc[code] if code in m.index else None
        get = (lambda k: (None if row is None or pd.isna(row.get(k)) else str(row.get(k))))
        return {"code": code, "name": get("name"), "market": get("market"), "sector": get("sector")}

    def _top(self, mask_row: pd.Series, score_row: pd.Series, n: int = TOP_N) -> list[str]:
        s = score_row.where(mask_row.fillna(False).astype(bool)).dropna()
        return list(s.sort_values(ascending=False, kind="mergesort").index[:n])

    def _risk(self, eng: Engine, codes: list[str], t) -> dict:
        if not codes:
            return {}
        items = eng.risk_check(codes, str(pd.Timestamp(t).date()))["items"]
        return {i["code"]: i for i in items if i.get("found")}

    def _row(self, eng: Engine, code: str, t, reasons: list[str], risk: dict, **extra) -> dict:
        f = eng.feats
        r = risk.get(code, {})
        return {**self._meta(code), "ref_price": float(eng.panel.close.at[t, code]),
                "r1": float(f["r1"].at[t, code]) if pd.notna(f["r1"].at[t, code]) else None,
                "reasons": reasons, "risk": r.get("risk"), **extra}

    # ---- sections from the last final session
    def leaders_section(self) -> dict:
        eng, x, t = self.eng, self.x, self._t()
        codes = self._top(self.leaders.loc[t], -eng.feats["vol20"].loc[t])
        risk = self._risk(eng, codes, t)
        rows = []
        for code in codes:
            rows.append(self._row(eng, code, t, [
                f"상대강도 상위 {max(1, round((1 - x['rs_pct'].at[t, code]) * 100))}%",
                f"52주 고가 대비 {_fmt_pct(eng.panel.close.at[t, code] / x['hi250'].at[t, code] - 1)}",
                f"20일선 이격 {_fmt_pct(eng.feats['gap_ma20'].at[t, code])} (과열 아님)",
                "종가 > 60일선 > 120일선",
            ], risk, rs_top_pct=max(1, round((1 - x["rs_pct"].at[t, code]) * 100))))
        return self._section("leaders", t, rows, status="SIGNAL")

    def swing_section(self) -> dict:
        eng, x, t = self.eng, self.x, self._t()
        mask = swing_mask(eng, x, self.leaders)
        codes = self._top(mask.loc[t], x["rs_pct"].loc[t])
        risk = self._risk(eng, codes, t)
        rows = [self._row(eng, code, t, [
            "5거래일 전 주도주 조건 충족",
            f"20일 고가 대비 {_fmt_pct(eng.feats['dd20'].at[t, code])} 눌림",
            f"상대강도 상위 {max(1, round((1 - x['rs_pct'].at[t, code]) * 100))}%",
            "종가 > 60일선",
        ], risk, pullback=float(eng.feats["dd20"].at[t, code])) for code in codes]
        return self._section("swing", t, rows, status="SIGNAL")

    def short_term_section(self) -> dict:
        eng, x, t = self.eng, self.x, self._t()
        watch = short_term_watch(eng, x)
        codes = self._top(watch.loc[t], -x["rsi2"].loc[t], n=10)
        risk = self._risk(eng, codes, t)
        now = self.clock()
        in_session = now.date() > t.date() and (now.hour, now.minute) >= (9, 0) and (now.hour, now.minute) <= (15, 30)
        live = self.live.get(codes, now.date()) if (codes and in_session) else {}
        rows = []
        for code in codes:
            prev = float(eng.panel.close.at[t, code])
            bar = live.get(code)
            gap = (bar["open"] / prev - 1) if bar and bar.get("open") else None
            state = ("TRIGGERED" if gap is not None and gap < -0.005 else "NOT_TRIGGERED") if gap is not None else "WAITING"
            rows.append(self._row(eng, code, t, [
                f"RSI(2) {x['rsi2'].at[t, code]:.0f} — 단기 과매도",
                "60일선 위 · 60일선 상승 중",
                f"전일 등락 {_fmt_pct(eng.feats['r1'].at[t, code])}",
            ], risk, open_gap=gap, open_price=bar["open"] if bar else None,
                since_open=(bar["close"] / bar["open"] - 1) if bar and bar.get("open") else None,
                live_price=bar["close"] if bar else None, trigger=state))
        triggered = [r for r in rows if r["trigger"] == "TRIGGERED"]
        if in_session and live:
            status = "TRIGGERED" if triggered else "NO_TRIGGER"
            shown = triggered[:TOP_N]
        else:
            status = "WATCHLIST"
            shown = rows[:TOP_N]
        # the buy decision is made at today's open, not at the signal close
        observed = f"{now.date()}T09:00:00+09:00" if status != "WATCHLIST" else None
        return self._section("short_term", t, shown, status=status, watchlist=len(rows), observed_at=observed)

    # ---- themes and closing bet (near-close snapshot when available)
    def _theme_rows(self, eng: Engine, x: dict, t) -> tuple[list[dict], list[dict]]:
        f = eng.feats
        ign = ignition_mask(x).loc[t]
        sec = eng.panel.meta["sector"].reindex(eng.panel.close.columns)
        ignited = sorted({s for s in sec[ign.fillna(False).astype(bool)].dropna()},
                         key=lambda s: -float(x["sec_value_surge"].loc[t][sec == s].iloc[0]))
        movers = x["liquid"].loc[t] & eng.universe.loc[t] & (f["r1"].loc[t] >= 0.03)
        theme_rows = []
        for s in ignited:
            members = sec.index[(sec == s)]
            cand = f["val_today"].loc[t, members].where(movers.reindex(members, fill_value=False)).dropna()
            if cand.empty:
                continue
            leader = cand.idxmax()
            one = members[0]
            theme_rows.append({"sector": s, "leader": leader,
                               "breadth": float(x["sec_breadth"].at[t, one]),
                               "value_surge": float(x["sec_value_surge"].at[t, one]),
                               "members": int(x["sec_n"].at[t, one])})
        lag = laggard_mask(eng, x).loc[t]
        lag_codes = self._top(lag, -f["atr14_pct"].loc[t])
        lag_rows = [{"code": c, "sector": sec.get(c)} for c in lag_codes]
        return theme_rows, lag_rows

    def themes_and_closing(self) -> tuple[dict, dict]:
        return self._themes_and_closing(self.eng, self.x, self._t(), status="SIGNAL")

    def _themes_and_closing(self, eng: Engine, x: dict, t, status: str) -> tuple[dict, dict]:
        f = eng.feats
        theme_rows, lag_rows = self._theme_rows(eng, x, t)
        codes = [r["leader"] for r in theme_rows] + [r["code"] for r in lag_rows]
        risk = self._risk(eng, codes, t)
        themes = [self._row(eng, r["leader"], t, [
            f"{r['sector']} 테마 점화 — 업종 {r['members']}종목 중 {round(r['breadth'] * 100)}% 가 +3% 이상",
            f"업종 거래대금 20일 평균의 {r['value_surge']:.1f}배",
            f"대장주(업종 내 거래대금 1위) · 당일 {_fmt_pct(f['r1'].at[t, r['leader']])}",
            "대장주 추격은 과거 손실 — 같은 업종 후발주는 종가매매 카드 참고",
        ], risk, role="LEADER", theme=r["sector"], breadth=r["breadth"], value_surge=r["value_surge"])
            for r in theme_rows[:TOP_N]]
        closing = [self._row(eng, r["code"], t, [
            f"{r['sector']} 테마 점화 업종의 후발주",
            f"당일 {_fmt_pct(f['r1'].at[t, r['code']])} — 아직 반응 전",
            "종가 > 60일선 · 거래대금 50억 이상",
            "종가 매수 → 다음 거래일 종가 청산",
        ], risk, role="LAGGARD", theme=r["sector"]) for r in lag_rows]
        return (self._section("themes", t, themes, status=status),
                self._section("closing_bet", t, closing, status=status))

    # ---- assembly
    def _section(self, key: str, t, rows: list[dict], status: str, observed_at: str | None = None, **extra) -> dict:
        """`as_of` is the signal session; `observed_at` is when the card's decision was made
        (the signal close, today's 09:00 open for short-term, or the near-close snapshot time)."""
        day = str(pd.Timestamp(t).date())
        return {"key": key, **DESIGNS[key], "as_of": day, "observed_at": observed_at or f"{day}T15:30:00+09:00",
                "status": status, "rows": rows, **extra}

    def _in_session(self) -> bool:
        now = self.clock()
        return now.weekday() < 5 and now.date() > self._t().date() and (9, 0) <= (now.hour, now.minute) <= (15, 30)

    def _attach_live(self, sections: dict) -> None:
        """During the session every row also carries its current price and change from the signal price."""
        if not self._in_session():
            return
        rows = [r for sec in sections.values() for r in sec["rows"] if r.get("live_price") is None]
        live = self.live.get(sorted({r["code"] for r in rows}), self.clock().date()) if rows else {}
        for r in rows:
            bar = live.get(r["code"])
            if bar:
                r["live_price"] = bar["close"]
        for sec in sections.values():
            for r in sec["rows"]:
                if r.get("live_price") and r.get("ref_price"):
                    r["since_signal"] = r["live_price"] / r["ref_price"] - 1

    def all_sections(self) -> dict:
        themes, closing = self.themes_and_closing()
        sections = {
            "short_term": self.short_term_section(),
            "swing": self.swing_section(),
            "leaders": self.leaders_section(),
            "themes": themes,
            "closing_bet": closing,
        }
        self._attach_live(sections)
        return {
            "as_of": str(self._t().date()),
            "regime": {k: v for k, v in self.eng.regime_now().items() if k != "history"},
            "data_version": self.eng.data_version,
            "sections": sections,
        }
