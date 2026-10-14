"""Real-time condition screeners (조건검색기) for the five home sections.

During the session the whole market is re-scanned every few minutes with live prices.
Each section's condition (the validated designs in detectors.py) is evaluated on a panel
whose last row is today's live bar; a stock that newly satisfies a condition is captured
(편입) with its capture time and capture price, exactly like an HTS condition screener.
Captures persist per day in var/scans/YYYY-MM-DD.json so a restart keeps the day's list.

Live data: Naver mobile market lists (price, change, accumulated volume and value for every
listed stock, ~45 requests). Today's open is not in those lists, so the short-term screen
reads the opens of its small watchlist from FinanceDataReader.
"""
from __future__ import annotations

import json
import logging
import threading
import time
from concurrent.futures import ThreadPoolExecutor
from datetime import date, datetime
from pathlib import Path

import numpy as np
import pandas as pd

from . import config
from . import detectors as D
from .engine import Engine
from .panel import build_panel
from .store import BarStore, now_kst

log = logging.getLogger(__name__)

SCAN_EVERY_SECONDS = 180
SESSION = ((9, 0), (15, 30))
NAVER_LIST = "https://m.stock.naver.com/api/stocks/marketValue/{market}"


def _num(text) -> float | None:
    try:
        return float(str(text).replace(",", ""))
    except (TypeError, ValueError):
        return None


def naver_snapshot(markets=("KOSPI", "KOSDAQ"), page_size: int = 100, threads: int = 4) -> pd.DataFrame:
    """Every listed stock's live price, change, volume and value. One row per code."""
    import httpx

    headers = {"User-Agent": "Mozilla/5.0"}

    def page(market, n):
        r = httpx.get(NAVER_LIST.format(market=market), params={"page": n, "pageSize": page_size},
                      headers=headers, timeout=10)
        r.raise_for_status()
        return r.json()

    first = {m: page(m, 1) for m in markets}
    jobs = [(m, n) for m in markets for n in range(2, int(np.ceil(first[m]["totalCount"] / page_size)) + 1)]
    payloads = list(first.values())
    with ThreadPoolExecutor(max_workers=threads) as pool:
        payloads += list(pool.map(lambda j: page(*j), jobs))
    rows = []
    for p in payloads:
        for s in p.get("stocks", []):
            price = _num(s.get("closePrice"))
            if not price:
                continue
            rows.append({
                "code": str(s.get("itemCode", "")).zfill(6),
                "close": price,
                "change_pct": _num(s.get("fluctuationsRatio")),
                "volume": _num(s.get("accumulatedTradingVolume")),
                "value": (_num(s.get("accumulatedTradingValue")) or 0) * 1e6,
                "traded_at": s.get("localTradedAt"),
                "market_status": s.get("marketStatus"),
            })
    return pd.DataFrame(rows).drop_duplicates("code")


class SectionScanner:
    """Holds the day's captures and re-evaluates every section on each scan."""

    SECTIONS = ("short_term", "swing", "leaders", "themes", "closing_bet")

    def __init__(self, store: BarStore, detectors: D.SectionDetectors, snapshot=naver_snapshot,
                 clock=now_kst, scans_dir: Path | None = None):
        self.store, self.det, self.snapshot, self.clock = store, detectors, snapshot, clock
        self.dir = Path(scans_dir or config.VAR_DIR / "scans")
        self.dir.mkdir(parents=True, exist_ok=True)
        self.day: str | None = None
        self.captures: dict[str, dict[str, dict]] = {s: {} for s in self.SECTIONS}
        self.last_scan: dict = {"state": "idle"}
        self._lock = threading.Lock()
        self._load_latest()

    # ---------------------------------------------------------------- persistence
    def _file(self, day: str) -> Path:
        return self.dir / f"{day}.json"

    def _load_latest(self) -> None:
        files = sorted(self.dir.glob("*.json"))
        if files:
            data = json.loads(files[-1].read_text(encoding="utf-8"))
            self.day = data["day"]
            self.captures = {s: data["captures"].get(s, {}) for s in self.SECTIONS}
            self.last_scan = data.get("last_scan", {"state": "loaded"})

    def _save(self) -> None:
        tmp = self._file(self.day).with_suffix(".tmp")
        tmp.write_text(json.dumps({"day": self.day, "captures": self.captures, "last_scan": self.last_scan},
                                  ensure_ascii=False, default=str), encoding="utf-8")
        tmp.replace(self._file(self.day))

    # ---------------------------------------------------------------- scanning
    def in_session(self, now: datetime | None = None) -> bool:
        now = now or self.clock()
        return now.weekday() < 5 and SESSION[0] <= (now.hour, now.minute) <= SESSION[1]

    def live_engine(self, snap: pd.DataFrame, today: date) -> Engine:
        """The final history plus today's live bar (open/high/low unknown -> the live price)."""
        base = self.store.final_bars()
        known = set(base["code"].unique())
        live = snap[snap["code"].isin(known)].copy()
        rows = pd.DataFrame({
            "code": live["code"], "date": pd.Timestamp(today),
            "open": live["close"], "high": live["close"], "low": live["close"], "close": live["close"],
            "volume": live["volume"], "as_of": pd.Timestamp(self.clock()), "final": True,
        })
        merged = pd.concat([base, rows[base.columns]], ignore_index=True)
        return Engine(build_panel(merged, self.store.load_listing(), self.store.load_names()))

    def scan(self) -> dict:
        with self._lock:
            now = self.clock()
            today = now.date()
            started = time.time()
            snap = self.snapshot()
            fresh = snap["traded_at"].astype(str).str.startswith(str(today)).mean() if len(snap) else 0
            if len(snap) < 500 or fresh < 0.5:
                self.last_scan = {"state": "skipped", "reason": f"snapshot {len(snap)} rows, {fresh:.0%} traded today",
                                  "at": now.isoformat(timespec="seconds")}
                return self.last_scan
            if self.day != str(today):
                self.day, self.captures = str(today), {s: {} for s in self.SECTIONS}

            eng = self.live_engine(snap, today)
            x = D.section_features(eng)
            t = eng.panel.dates[-1]
            hits = self._evaluate(eng, x, t, today)
            price = snap.set_index("code")["close"]
            stamp = now.isoformat(timespec="seconds")
            for section, ranked in hits.items():
                book = self.captures[section]
                for code in book:
                    book[code]["active"] = code in ranked
                for rank, (code, info) in enumerate(ranked.items(), start=1):
                    p = float(price.get(code, np.nan))
                    entry = book.get(code)
                    if entry is None:
                        entry = book[code] = {"code": code, "captured_at": stamp, "capture_price": p, **info}
                    entry.update({"active": True, "rank": rank, "last_price": p, "last_seen": stamp,
                                  "reasons": info["reasons"], "risk": info.get("risk"),
                                  **{k: v for k, v in info.items() if k not in ("reasons", "risk")}})
            self.last_scan = {"state": "done", "at": stamp, "seconds": round(time.time() - started, 1),
                              "stocks": int(len(snap)),
                              "active": {s: sum(1 for v in self.captures[s].values() if v.get("active")) for s in self.SECTIONS}}
            self._save()
            return self.last_scan

    def _evaluate(self, eng: Engine, x: dict, t, today: date) -> dict[str, dict[str, dict]]:
        f, c = eng.feats, eng.panel.close
        meta = eng.panel.meta
        out: dict[str, dict[str, dict]] = {}

        def pick(mask_row, score_row, n=D.TOP_N * 4):
            s = score_row.where(mask_row.fillna(False).astype(bool)).dropna()
            return list(s.sort_values(ascending=False, kind="mergesort").index[:n])

        def risk_of(codes):
            if not codes:
                return {}
            return {i["code"]: i.get("risk") for i in eng.risk_check(codes, str(t.date()))["items"] if i.get("found")}

        def base(code):
            m = meta.loc[code] if code in meta.index else {}
            get = lambda k: None if not len(m) or pd.isna(m.get(k)) else str(m.get(k))  # noqa: E731
            return {"name": get("name"), "market": get("market"), "sector": get("sector")}

        rs = lambda code: max(1, round((1 - x["rs_pct"].at[t, code]) * 100))  # noqa: E731

        leaders = D.leader_mask(eng, x)
        codes = pick(leaders.loc[t], -f["vol20"].loc[t])
        risk = risk_of(codes)
        out["leaders"] = {code: {**base(code), "risk": risk.get(code), "rs_top_pct": rs(code), "reasons": [
            f"상대강도 상위 {rs(code)}%",
            f"52주 고가 대비 {D._fmt_pct(c.at[t, code] / x['hi250'].at[t, code] - 1)}",
            f"20일선 이격 {D._fmt_pct(f['gap_ma20'].at[t, code])} (과열 아님)",
            "종가 > 60일선 > 120일선"]} for code in codes}

        swing = D.swing_mask(eng, x, leaders)
        codes = pick(swing.loc[t], x["rs_pct"].loc[t])
        risk = risk_of(codes)
        out["swing"] = {code: {**base(code), "risk": risk.get(code), "pullback": float(f["dd20"].at[t, code]), "reasons": [
            "5거래일 전 주도주 조건 충족",
            f"20일 고점 대비 {D._fmt_pct(f['dd20'].at[t, code])} 눌림",
            f"상대강도 상위 {rs(code)}%"]} for code in codes}

        # short term: yesterday's final-close watchlist, captured when today opens with a gap down
        det_x, det_t = self.det.x, self.det.eng.panel.dates[-1]
        watch = pick(D.short_term_watch(self.det.eng, det_x).loc[det_t], -det_x["rsi2"].loc[det_t], n=10)
        opens = self.det.live.get(watch, today) if watch else {}
        hits = []
        for code in watch:
            bar = opens.get(code)
            prev = float(self.det.eng.panel.close.at[det_t, code])
            if bar and bar.get("open") and bar["open"] / prev - 1 < -0.005:
                hits.append((code, bar["open"] / prev - 1))
        risk = risk_of([h[0] for h in hits])
        out["short_term"] = {code: {**base(code), "risk": risk.get(code), "open_gap": gap, "reasons": [
            f"시가 갭 {D._fmt_pct(gap)} — 과매도 반등 자리",
            f"RSI(2) {det_x['rsi2'].at[det_t, code]:.0f} (전일) · 60일선 위 상승 추세"]} for code, gap in hits}

        ign = D.ignition_mask(x).loc[t]
        sec = meta["sector"].reindex(c.columns)
        movers = x["liquid"].loc[t] & eng.universe.loc[t] & (f["r1"].loc[t] >= 0.03)
        themes = {}
        for s_name in sorted({s for s in sec[ign.fillna(False).astype(bool)].dropna()},
                             key=lambda s: -float(x["sec_value_surge"].loc[t][sec == s].iloc[0])):
            members = sec.index[sec == s_name]
            cand = f["val_today"].loc[t, members].where(movers.reindex(members, fill_value=False)).dropna()
            if cand.empty:
                continue
            code, one = cand.idxmax(), members[0]
            themes[code] = {**base(code), "theme": s_name, "role": "LEADER",
                            "breadth": float(x["sec_breadth"].at[t, one]),
                            "reasons": [f"{s_name} 테마 점화 — 업종 {int(x['sec_n'].at[t, one])}종목 중 "
                                        f"{round(x['sec_breadth'].at[t, one] * 100)}% 가 +3% 이상",
                                        f"업종 거래대금 20일 평균의 {x['sec_value_surge'].at[t, one]:.1f}배",
                                        "대장주 — 추격 주의, 같은 업종 후발주는 종가매매"]}
        risk = risk_of(list(themes))
        for code in themes:
            themes[code]["risk"] = risk.get(code)
        out["themes"] = themes

        lag = D.laggard_mask(eng, x)
        codes = pick(lag.loc[t], -f["atr14_pct"].loc[t])
        risk = risk_of(codes)
        out["closing_bet"] = {code: {**base(code), "risk": risk.get(code), "theme": sec.get(code), "role": "LAGGARD", "reasons": [
            f"{sec.get(code)} 테마 점화 업종의 후발주",
            f"현재 {D._fmt_pct(f['r1'].at[t, code])} — 아직 반응 전",
            "종가(동시호가) 매수 → 다음 날 종가 청산"]} for code in codes}
        return out

    # ---------------------------------------------------------------- output
    def sections(self) -> dict:
        """Today's (or the latest session's) captures, active ones first in design rank order."""
        out = {}
        live_day = self.day == str(self.clock().date())
        for s in self.SECTIONS:
            book = list(self.captures.get(s, {}).values())
            active = sorted([v for v in book if v.get("active")], key=lambda v: v.get("rank", 99))
            rows = []
            for v in active:
                cp, lp = v.get("capture_price"), v.get("last_price")
                rows.append({**v, "ref_price": cp, "live_price": lp,
                             "since_capture": (lp / cp - 1) if cp and lp else None})
            out[s] = {"key": s, **D.DESIGNS[s], "status": "LIVE_SCAN" if live_day and self.in_session() else "LAST_SCAN",
                      "as_of": self.day, "observed_at": self.last_scan.get("at"), "rows": rows,
                      "captured_today": len(book)}
        return out

    # ---------------------------------------------------------------- loop
    def run_forever(self, stop: threading.Event) -> None:
        while not stop.is_set():
            try:
                if self.in_session():
                    self.scan()
            except Exception as exc:                 # one failed scan must not stop the screener
                log.error("scan failed: %s", exc, exc_info=True)
                self.last_scan = {"state": "error", "error": f"{type(exc).__name__}: {exc}",
                                  "at": self.clock().isoformat(timespec="seconds")}
            stop.wait(SCAN_EVERY_SECONDS)
