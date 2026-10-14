"""Section condition screeners — 단타 · 스윙 · 주도주 · 테마주 · 종가매매.

Each section is an HTS-style real-time condition screener with its own trading intent:

  단타     today's money is arriving now: the first leg of an intraday run
  스윙     an aligned uptrend that pulled back on drying volume and is turning up again
  주도주   the leaders of the market: big money, near the 52-week high, earnings and flows behind it
  테마주   a group of related stocks moving together on money and news; its leader and runner-up
  종가매매 strong into the close on heavy money — bought at the close for a gap tomorrow

Every scan (3 minutes in the session):
  1. the whole market's live price / change / accumulated value (Naver market lists)
  2. the section's trading-value pool — every stock where money is attaching, by that
     section's own definition (단타 = value pace now, 스윙 = liquid name with returning volume, ...)
  3. technical conditions for the section's intent (daily history + the live bar)
  4. 재료 (today's DART filings, news), 기본 (quarterly operating profit, consensus PER),
     수급 (foreign + institution net buying) and risk filings
  5. a section-weighted score with the reasons, then capture (편입 time and price) and tracking
     of the move after capture (max and current) — the screener's own report card.
"""
from __future__ import annotations

import json
import logging
import re
import threading
import time
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass, field
from datetime import date, datetime, timedelta
from pathlib import Path

import numpy as np
import pandas as pd

from . import config
from .engine import Engine
from .scanner import naver_snapshot
from .store import now_kst

log = logging.getLogger(__name__)

SCAN_EVERY_SECONDS = 180
SESSION = ((9, 0), (15, 30))
HEADERS = {"User-Agent": "Mozilla/5.0"}
NAVER = "https://m.stock.naver.com/api/stock/{code}/{path}"

# Share of a normal day's trading value done by a given time (KRX intraday U-shape; an assumption
# used to compare today's value so far with the 20-day average at the same time of day).
VALUE_CURVE = [(9 * 60, 0.0), (9 * 60 + 30, 0.22), (10 * 60, 0.33), (11 * 60, 0.47), (12 * 60, 0.57),
               (13 * 60, 0.66), (14 * 60, 0.77), (15 * 60, 0.90), (15 * 60 + 30, 1.0)]

POSITIVE = re.compile(r"단일판매|공급계약|수주|특허|임상|품목허가|허가|기술이전|라이선스|자기주식취득|자기주식 취득|무상증자|"
                      r"영업\(잠정\)실적|매출액또는손익구조|투자판단관련|신규시설투자|합작|MOU|양해각서")
NEGATIVE = re.compile(r"유상증자|전환사채|신주인수권부사채|교환사채|감자|소송|횡령|배임|회생|파산|상장폐지|"
                      r"관리종목|투자주의환기|불성실공시|감사의견|거래정지|최대주주변경")
RISK = re.compile(r"관리종목|투자주의환기|불성실공시|감사의견|횡령|배임|회생|파산|상장폐지|거래정지")


def expected_share(now: datetime) -> float:
    m = now.hour * 60 + now.minute
    xs, ys = zip(*VALUE_CURVE)
    return float(max(0.05, np.interp(m, xs, ys)))


def _num(text) -> float | None:
    if text is None:
        return None
    t = re.sub(r"[^0-9.\-+]", "", str(text))
    try:
        return float(t) if t not in ("", "-", "+") else None
    except ValueError:
        return None


# ------------------------------------------------------------------ enrichment sources
class Facts:
    """Per-stock facts with TTL caches: intraday bar & valuation, quarters, flows, news."""

    def __init__(self, get=None, threads: int = 8):
        self._get = get or self._http
        self.threads = threads
        self._cache: dict[tuple[str, str], tuple[float, object]] = {}
        self._lock = threading.Lock()

    @staticmethod
    def _http(url, params=None):
        import httpx
        r = httpx.get(url, params=params, headers=HEADERS, timeout=8)
        r.raise_for_status()
        return r.json()

    def _cached(self, kind: str, code: str, ttl: float, fn):
        key = (kind, code)
        now = time.time()
        with self._lock:
            hit = self._cache.get(key)
        if hit and now - hit[0] < ttl:
            return hit[1]
        try:
            value = fn()
        except Exception as exc:              # a missing fact never blocks the screen
            log.debug("fact %s %s failed: %s", kind, code, exc)
            value = None
        with self._lock:
            self._cache[key] = (now, value)
        return value

    def bar(self, code: str) -> dict | None:
        def fetch():
            d = self._get(NAVER.format(code=code, path="integration"))
            info = {i.get("code"): i.get("value") for i in d.get("totalInfos", [])}
            return {"open": _num(info.get("openPrice")), "high": _num(info.get("highPrice")),
                    "low": _num(info.get("lowPrice")), "hi52": _num(info.get("highPriceOf52Weeks")),
                    "per": _num(info.get("per")), "cns_per": _num(info.get("cnsPer")),
                    "pbr": _num(info.get("pbr"))}
        return self._cached("bar", code, 170, fetch)

    def quarters(self, code: str) -> dict | None:
        def fetch():
            d = self._get(NAVER.format(code=code, path="finance/quarter"))
            info = d.get("financeInfo", {})
            periods = [(t["key"], t.get("isConsensus") == "Y") for t in info.get("trTitleList", [])]
            rows = {r.get("title"): r.get("columns", {}) for r in info.get("rowList", [])}
            op = rows.get("영업이익", {})
            actual = [(k, _num(op.get(k, {}).get("value"))) for k, cns in periods if not cns]
            actual = [(k, v) for k, v in actual if v is not None]
            cns = [(k, _num(op.get(k, {}).get("value"))) for k, c in periods if c]
            return {"op": actual[-5:], "op_consensus": next((v for _, v in cns if v is not None), None)}
        return self._cached("quarters", code, 6 * 3600, fetch)

    def flows(self, code: str) -> dict | None:
        def fetch():
            rows = self._get(NAVER.format(code=code, path="trend"), {"pageSize": 20})
            f = [(_num(r.get("foreignerPureBuyQuant")) or 0) for r in rows]
            o = [(_num(r.get("organPureBuyQuant")) or 0) for r in rows]
            px = [(_num(r.get("closePrice")) or 0) for r in rows]
            val = lambda q, n: float(sum(a * b for a, b in zip(q[:n], px[:n])))  # noqa: E731
            return {"foreign5": val(f, 5), "organ5": val(o, 5), "foreign20": val(f, 20), "organ20": val(o, 20),
                    "both_days5": int(sum(1 for a, b in zip(f[:5], o[:5]) if a > 0 and b > 0))}
        return self._cached("flows", code, 6 * 3600, fetch)

    def news(self, code: str, today: date, name: str | None = None) -> dict | None:
        def fetch():
            groups = self._get(f"https://m.stock.naver.com/api/news/stock/{code}", {"pageSize": 10, "page": 1})
            items = [it for g in groups for it in g.get("items", [])]
            day = today.strftime("%Y%m%d")
            todays = [it for it in items if str(it.get("datetime", "")).startswith(day)]
            if name:   # the feed mixes in market-wide stories; keep the ones about this stock
                todays = [it for it in todays if name in (it.get("titleFull") or it.get("title") or "") + (it.get("body") or "")]
            titles = [re.sub(r"<[^>]+>|&quot;", "", it.get("titleFull") or it.get("title") or "") for it in todays]
            return {"today": len(todays), "titles": [t for t in titles if t][:3]}
        return self._cached("news", code, 600, fetch)

    def enrich(self, codes: list[str], today: date, kinds=("bar", "quarters", "flows", "news"),
               names: dict | None = None) -> dict[str, dict]:
        def one(code):
            out = {}
            if "bar" in kinds:
                out["bar"] = self.bar(code)
            if "quarters" in kinds:
                out["quarters"] = self.quarters(code)
            if "flows" in kinds:
                out["flows"] = self.flows(code)
            if "news" in kinds:
                out["news"] = self.news(code, today, (names or {}).get(code))
            return code, out
        if not codes:
            return {}
        with ThreadPoolExecutor(max_workers=self.threads) as pool:
            return dict(pool.map(one, codes))


class Disclosures:
    """OpenDART filings: today's list (재료) and the last five days (risk)."""

    def __init__(self, api_key: str | None, get=None):
        self.key = api_key
        self._get = get or Facts._http
        self._today: tuple[float, str, dict] | None = None
        self._risk: tuple[str, dict] | None = None

    def _list(self, start: date, end: date, max_pages: int = 20) -> list[dict]:
        if not self.key:
            return []
        out, page = [], 1
        while page <= max_pages:
            d = self._get("https://opendart.fss.or.kr/api/list.json",
                          {"crtfc_key": self.key, "bgn_de": start.strftime("%Y%m%d"), "end_de": end.strftime("%Y%m%d"),
                           "page_count": 100, "page_no": page})
            if d.get("status") != "000":
                break
            out += d.get("list", [])
            if page >= int(d.get("total_page", 1)):
                break
            page += 1
        return out

    def today(self, day: date) -> dict[str, list[str]]:
        now = time.time()
        if self._today and self._today[1] == str(day) and now - self._today[0] < 300:
            return self._today[2]
        by_code: dict[str, list[str]] = {}
        try:
            for it in self._list(day, day):
                if it.get("stock_code"):
                    by_code.setdefault(it["stock_code"], []).append(re.sub(r"\s+", "", it.get("report_nm", "")))
        except Exception as exc:
            log.warning("DART today failed: %s", exc)
        self._today = (now, str(day), by_code)
        return by_code

    def risky(self, day: date) -> dict[str, str]:
        if self._risk and self._risk[0] == str(day):
            return self._risk[1]
        risky: dict[str, str] = {}
        try:
            for it in self._list(day - timedelta(days=7), day):
                name = re.sub(r"\s+", "", it.get("report_nm", ""))
                if it.get("stock_code") and RISK.search(name):
                    risky[it["stock_code"]] = name
        except Exception as exc:
            log.warning("DART risk window failed: %s", exc)
        self._risk = (str(day), risky)
        return risky


# ------------------------------------------------------------------ daily context
@dataclass
class Daily:
    """Per-stock context from the final daily history (computed once per session)."""
    frame: pd.DataFrame
    sector: pd.Series
    as_of: str


def daily_context(eng: Engine) -> Daily:
    p, f = eng.panel, eng.feats
    c, h, v = p.close, p.high, p.value
    t = p.dates[-1]
    ma = lambda w: c.rolling(w, min_periods=w).mean().loc[t]  # noqa: E731
    val20 = v.rolling(20, min_periods=20).mean().loc[t]
    val5 = v.rolling(5, min_periods=5).mean().loc[t]
    r60 = f["r60"].loc[t]
    frame = pd.DataFrame({
        "name": p.meta["name"], "market": p.meta["market"],
        "prev_close": c.loc[t], "prev_high": h.loc[t],
        "ma5": ma(5), "ma20": ma(20), "ma60": ma(60), "ma120": ma(120),
        "hi20": h.rolling(20, min_periods=20).max().loc[t], "hi250": h.rolling(250, min_periods=200).max().loc[t],
        "val20": val20, "val5": val5, "atr": f["atr14_pct"].loc[t],
        "rs_pct": (0.4 * f["r20"].loc[t] + 0.3 * r60 + 0.3 * (c.loc[t] / c.shift(120).loc[t] - 1)).rank(pct=True),
        # every common stock that traded on the last session — quiet names whose money arrives today included
        "in_universe": c.loc[t].notna() & (c.loc[t] >= 1000),
    })
    return Daily(frame=frame, sector=p.meta["sector"].reindex(c.columns), as_of=str(t.date()))


# ------------------------------------------------------------------ section definitions
SECTION_TITLES = {"short_term": "단타", "swing": "스윙", "leaders": "주도주", "themes": "테마주", "closing_bet": "종가매매"}
INTENT = {
    "short_term": "지금 돈이 붙으며 시세가 나기 시작하는 종목 — 오늘 단타매매 유력 후보",
    "swing": "정배열 상승 추세에서 거래가 마르며 눌렸다가 다시 돈이 붙으며 돌아서는 종목 — 3~10일 스윙 후보",
    "leaders": "큰돈이 몰리고 52주 신고가 근처에서 실적·수급이 받쳐 주는 시장 주도 종목",
    "themes": "같은 업종이 돈과 뉴스로 함께 움직이는 테마와 그 대장주·2등주",
    "closing_bet": "큰 거래대금으로 고가권에서 마감하는 종목 — 종가 매수 후 다음 날 시세 기대",
}
WEIGHTS = {   # 거래대금, 기술, 재료, 기본, 수급 (sum 100)
    "short_term": (30, 30, 25, 5, 10),
    "swing": (15, 35, 10, 20, 20),
    "leaders": (15, 30, 5, 25, 25),
    "themes": (30, 25, 30, 0, 15),
    "closing_bet": (30, 30, 20, 5, 15),
}


def _clip01(x):
    return float(min(1.0, max(0.0, x)))


@dataclass
class Candidate:
    code: str
    section: str
    parts: dict = field(default_factory=dict)      # component -> 0..1
    reasons: list = field(default_factory=list)
    info: dict = field(default_factory=dict)

    def score(self) -> float:
        w = dict(zip(("value", "tech", "news", "fund", "flow"), WEIGHTS[self.section]))
        return round(sum(w[k] * self.parts.get(k, 0.0) for k in w), 1)


class LiveScreeners:
    SECTIONS = tuple(SECTION_TITLES)

    def __init__(self, engine: Engine, facts: Facts | None = None, dart: Disclosures | None = None,
                 snapshot=naver_snapshot, clock=now_kst, out_dir: Path | None = None):
        self.daily = daily_context(engine)
        self.facts = facts or Facts()
        self.dart = dart or Disclosures(_dart_key())
        self.snapshot, self.clock = snapshot, clock
        self.dir = Path(out_dir or config.VAR_DIR / "screeners")
        self.dir.mkdir(parents=True, exist_ok=True)
        self.day: str | None = None
        self.book: dict[str, dict[str, dict]] = {s: {} for s in self.SECTIONS}
        self.prev_value: dict[str, float] = {}
        self.last_scan: dict = {"state": "idle"}
        self._lock = threading.Lock()
        self._load_latest()

    def reload_daily(self, engine: Engine) -> None:
        self.daily = daily_context(engine)

    # ---------------------------------------------------------------- persistence
    def _load_latest(self) -> None:
        files = sorted(self.dir.glob("*.json"))
        if files:
            data = json.loads(files[-1].read_text(encoding="utf-8"))
            self.day, self.last_scan = data["day"], data.get("last_scan", {})
            self.book = {s: data["book"].get(s, {}) for s in self.SECTIONS}

    def _save(self) -> None:
        path = self.dir / f"{self.day}.json"
        tmp = path.with_suffix(".tmp")
        tmp.write_text(json.dumps({"day": self.day, "book": self.book, "last_scan": self.last_scan},
                                  ensure_ascii=False, default=str), encoding="utf-8")
        tmp.replace(path)

    def in_session(self, now: datetime | None = None) -> bool:
        now = now or self.clock()
        return now.weekday() < 5 and SESSION[0] <= (now.hour, now.minute) <= SESSION[1]

    # ---------------------------------------------------------------- scan
    def tape(self, snap: pd.DataFrame, now: datetime) -> pd.DataFrame:
        d = self.daily.frame
        # A market-wide freshness percentage cannot validate each individual quote.
        snap = snap[snap["traded_at"].astype(str).str.startswith(str(now.date()))]
        live = snap.set_index("code")[["close", "change_pct", "value", "volume", "traded_at"]]
        t = live.join(d, how="inner")
        t = t[t["in_universe"].fillna(False).astype(bool)]
        share = expected_share(now)
        t["pace"] = t["value"] / (t["val20"] * share)                 # today's value vs a normal day so far
        prev = pd.Series(self.prev_value)
        dv = (t["value"] - prev.reindex(t.index)).clip(lower=0)
        # last interval vs a normal 3-minute slice; the day's first scan has no interval yet, so its pace stands in
        t["velocity"] = (dv / (t["val20"] / 130)).fillna(t["pace"])
        t["chg"] = t["change_pct"] / 100
        return t

    def scan(self) -> dict:
        with self._lock:
            now = self.clock()
            today = now.date()
            started = time.time()
            snap = self.snapshot()
            fresh = snap["traded_at"].astype(str).str.startswith(str(today)).mean() if len(snap) else 0
            if len(snap) < 500 or fresh < 0.5:
                self.last_scan = {"state": "skipped", "reason": f"snapshot {len(snap)} rows, {fresh:.0%} today",
                                  "at": now.isoformat(timespec="seconds")}
                return self.last_scan
            if self.day != str(today):
                self.day, self.book, self.prev_value = str(today), {s: {} for s in self.SECTIONS}, {}
            t = self.tape(snap, now)
            pools = self.pools(t, now)
            union = sorted({c for codes in pools.values() for c in codes})
            facts = self.facts.enrich(union, today, names=self.daily.frame["name"].to_dict())
            filings = self.dart.today(today)
            risky = self.dart.risky(today)
            results = {
                "short_term": self.short_term(t, pools["short_term"], facts, filings, risky),
                "swing": self.swing(t, pools["swing"], facts, filings, risky),
                "leaders": self.leaders(t, pools["leaders"], facts, filings, risky),
                "themes": self.themes(t, pools["themes"], facts, filings, risky),
                "closing_bet": self.closing(t, pools["closing_bet"], facts, filings, risky, now),
            }
            stamp = now.isoformat(timespec="seconds")
            for section, cands in results.items():
                self._capture(section, cands, t, stamp)
            self.prev_value = t["value"].to_dict()
            self.last_scan = {"state": "done", "at": stamp, "seconds": round(time.time() - started, 1),
                              "stocks": int(len(t)), "pools": {k: len(v) for k, v in pools.items()},
                              "active": {s: sum(1 for v in self.book[s].values() if v.get("active")) for s in self.SECTIONS}}
            self._save()
            return self.last_scan

    def pools(self, t: pd.DataFrame, now: datetime) -> dict[str, list[str]]:
        """Approved absolute thresholds. Historical averages remain explicitly estimated in shadow mode.

        Mirrors ConditionLiquidityPolicy; an estimate here is NOT a production liquidity pass.
        The home feed still requires KIS acml_tr_pbmn for the actual 20-session average.
        """
        late = (now.hour, now.minute) >= (14, 30)
        early = 9 <= now.hour < 11
        short_min = (3e9 if now.hour == 9 else 5e9) if early else 1e10
        leader_min = (15e9 if now.hour == 9 else 25e9) if early else 5e10
        return {
            "short_term": list(t.index[(t["value"] >= short_min) & (t["pace"] >= 3.0) & (t["velocity"].fillna(0) >= 3.0)
                                        & (t["chg"] >= 0.03) & (t["chg"] <= 0.15)]),
            "swing": list(t.index[(t["val20"] >= 5e9) & (t["val5"] <= 0.8 * t["val20"]) & (t["pace"] >= 1.2)
                                   & (t["chg"] >= 0.01)]),
            "leaders": list(t.index[(t["val20"] >= 2e10) & (t["value"] >= leader_min)
                                     & (t["close"] >= 0.9 * t["hi250"])]),
            "themes": list(t.index[(t["pace"] >= 2.0) & (t["chg"] >= 0.03) & (t["value"] >= short_min)]),
            "closing_bet": list(t.index[(t["value"] >= 1e10) & (t["pace"] >= 2.0) & (t["chg"] >= 0.05)
                                         & (t["chg"] <= 0.15)]) if late else [],
        }

    # ---------------------------------------------------------------- shared scoring parts
    def _news_part(self, code, facts, filings) -> tuple[float, list[str], bool]:
        titles = filings.get(code, [])
        good = [x for x in titles if POSITIVE.search(x)]
        bad = [x for x in titles if NEGATIVE.search(x)]
        news = (facts.get(code, {}) or {}).get("news") or {}
        reasons = []
        if good:
            reasons.append(f"재료: 당일 공시 {good[0]}")
        if news.get("today"):
            reasons.append(f"재료: 오늘 뉴스 {news['today']}건" + (f" — {news['titles'][0][:40]}" if news.get("titles") else ""))
        if bad:
            reasons.append(f"재료(악재): {bad[0]}")
        part = _clip01(0.6 * bool(good) + 0.15 * min(news.get("today", 0), 3)) * (0.3 if bad else 1.0)
        return part, reasons, bool(bad)

    def _fund_part(self, code, facts) -> tuple[float, list[str], bool]:
        q = (facts.get(code, {}) or {}).get("quarters") or {}
        bar = (facts.get(code, {}) or {}).get("bar") or {}
        op = [v for _, v in q.get("op", [])]
        if len(op) < 4 or not all(v is not None and np.isfinite(v) for v in op):
            return 0.0, ["기본: 최근 4분기 영업이익 미확인 (가점 없음)"], False
        last4 = sum(op[-4:])
        loss = last4 < 0
        growth = len(op) >= 5 and op[-5] not in (0, None) and op[-1] > op[-5]
        part = (0.0 if loss else 0.5) + (0.3 if growth else 0.0) + (0.2 if (bar.get("cns_per") or 99) < 20 else 0.0)
        reasons = [f"기본: 최근 4분기 영업이익 {'적자' if loss else '흑자'}" + (" · 전년 동기 대비 증가" if growth else "")]
        if bar.get("cns_per"):
            reasons.append(f"기본: 추정 PER {bar['cns_per']:.1f}배")
        return _clip01(part), reasons, loss

    def _flow_part(self, code, facts, horizon: int = 5) -> tuple[float, list[str]]:
        fl = (facts.get(code, {}) or {}).get("flows") or {}
        if not fl:
            return 0.0, []
        f, o = fl.get(f"foreign{horizon}", 0), fl.get(f"organ{horizon}", 0)
        part = 0.4 * (f > 0) + 0.4 * (o > 0) + 0.2 * (fl.get("both_days5", 0) >= 2)
        tag = lambda v: f"{v / 1e8:+,.0f}억"  # noqa: E731
        return _clip01(part), [f"수급: {horizon}일 외국인 {tag(f)} · 기관 {tag(o)}"]

    def _value_reason(self, row) -> str:
        return f"거래대금: {row['value'] / 1e8:,.0f}억 (종가×거래량·가정 누적곡선 대비 추정 {row['pace']:.1f}배)"

    # ---------------------------------------------------------------- sections
    def short_term(self, t, pool, facts, filings, risky) -> list[Candidate]:
        out = []
        for code in pool:
            r = t.loc[code]
            bar = (facts.get(code) or {}).get("bar") or {}
            if code in risky or not bar.get("open") or r["close"] < bar["open"]:
                continue                                          # 음봉 진행 · 위험 공시 제외
            near_high = bar.get("high") and r["close"] >= 0.97 * bar["high"]
            breakout = r["close"] > r["prev_high"]
            if not (near_high or breakout) or r["close"] < r["ma20"]:
                continue
            c = Candidate(code, "short_term")
            c.parts["value"] = _clip01((r["pace"] - 3) / 7 * 0.6 + min(r["velocity"], 10) / 10 * 0.4)
            c.parts["tech"] = _clip01(0.4 * breakout + 0.3 * bool(near_high) + 0.3 * (r["close"] > r["ma5"] > r["ma20"]))
            if r["chg"] >= 0.12:
                c.parts["tech"] *= 0.6                             # 과열 구간 감점
            c.parts["news"], nr, bad = self._news_part(code, facts, filings)
            c.parts["fund"], fr, _ = self._fund_part(code, facts)
            c.parts["flow"], wr = self._flow_part(code, facts)
            tech = f"기술: {'전일 고가 돌파' if breakout else '당일 고가권'} · 당일 {r['chg'] * 100:+.1f}%"
            if r["close"] > r["ma5"] > r["ma20"]:
                tech += " · 5일선>20일선"
            c.reasons = [self._value_reason(r), tech] + nr + wr + fr[:1]
            c.info = {"pace": float(r["pace"]), "velocity": float(r["velocity"]), "change": float(r["chg"])}
            out.append(c)
        return out

    def swing(self, t, pool, facts, filings, risky) -> list[Candidate]:
        out = []
        for code in pool:
            r = t.loc[code]
            aligned = r["ma5"] > r["ma20"] > r["ma60"]
            pull = r["prev_close"] / r["hi20"] - 1
            if code in risky or not aligned or not (-0.12 <= pull <= -0.03) or r["close"] < 0.98 * r["ma20"]:
                continue
            bar = (facts.get(code) or {}).get("bar") or {}
            if bar.get("open") and r["close"] < bar["open"]:
                continue                                          # 반등 양봉이어야 한다
            c = Candidate(code, "swing")
            c.parts["fund"], fr, loss = self._fund_part(code, facts)
            if loss:
                continue                                          # 스윙은 적자 기업 제외
            c.parts["value"] = _clip01((r["pace"] - 1.2) / 1.8 * 0.6 + (1 - r["val5"] / r["val20"]) * 0.8)
            c.parts["tech"] = _clip01(0.4 + 0.3 * (r["close"] > r["prev_high"]) + 0.3 * (-0.08 <= pull <= -0.04))
            c.parts["news"], nr, _ = self._news_part(code, facts, filings)
            c.parts["flow"], wr = self._flow_part(code, facts, 5)
            c.reasons = [f"기술: 정배열(5>20>60) · 20일 고점 대비 {pull * 100:.1f}% 눌림 후 반등 {r['chg'] * 100:+.1f}%",
                         f"거래대금: 눌림 기간 평소의 {r['val5'] / r['val20'] * 100:.0f}% → 오늘 {r['pace']:.1f}배"] + fr + wr + nr
            c.info = {"pullback": float(pull), "pace": float(r["pace"])}
            out.append(c)
        return out

    def leaders(self, t, pool, facts, filings, risky) -> list[Candidate]:
        out = []
        sec_rank = t["value"].groupby(self.daily.sector.reindex(t.index)).rank(ascending=False)
        for code in pool:
            r = t.loc[code]
            if code in risky or not (r["close"] > r["ma60"] > r["ma120"]) or r["rs_pct"] < 0.8:
                continue
            c = Candidate(code, "leaders")
            c.parts["fund"], fr, loss = self._fund_part(code, facts)
            if loss:
                continue
            rank_in_sector = sec_rank.get(code)
            c.parts["value"] = _clip01(0.5 * (rank_in_sector == 1) + 0.25 * (rank_in_sector == 2) + 0.5 * min(r["pace"], 2) / 2)
            c.parts["tech"] = _clip01(0.5 * r["rs_pct"] + 0.5 * (r["close"] / r["hi250"]))
            c.parts["flow"], wr = self._flow_part(code, facts, 20)
            c.parts["news"], nr, _ = self._news_part(code, facts, filings)
            sec = self.daily.sector.get(code)
            c.reasons = [f"기술: 52주 고가 대비 {(r['close'] / r['hi250'] - 1) * 100:+.1f}% · 상대강도 상위 {max(1, round((1 - r['rs_pct']) * 100))}%",
                         f"거래대금: {r['value'] / 1e8:,.0f}억 · {sec or '업종'} 내 {int(rank_in_sector or 0)}위"] + wr + fr + nr
            c.info = {"rs_pct": float(r["rs_pct"]), "sector_rank": int(rank_in_sector or 0)}
            out.append(c)
        return out

    def themes(self, t, pool, facts, filings, risky) -> list[Candidate]:
        sector = self.daily.sector.reindex(t.index)
        movers = t.loc[pool]
        out = []
        for sec, members in movers.groupby(sector.reindex(movers.index)):
            group = t[sector == sec]
            if len(members) < 3 or group["value"].sum() < 2.0 * (group["val20"] * expected_share(self.clock())).sum():
                continue
            ranked = members.drop(index=list(risky), errors="ignore").sort_values(["value", "chg"], ascending=False)
            for position, code in enumerate(ranked.index):
                role = ("업종 거래대금 1위", "업종 거래대금 2위")[position] if position < 2 else "동반 상승주"
                r = t.loc[code]
                c = Candidate(code, "themes")
                c.parts["value"] = _clip01(min(r["pace"], 6) / 6)
                c.parts["tech"] = _clip01(0.5 + 0.5 * (r["close"] > r["prev_high"]))
                c.parts["news"], nr, _ = self._news_part(code, facts, filings)
                c.parts["flow"], wr = self._flow_part(code, facts, 5)
                c.reasons = [f"업종 동반 상승: {sec} — {len(members)}종목 · 뉴스·공시 기반 테마 연결 미확인",
                             f"{role} · 당일 {r['chg'] * 100:+.1f}% · {self._value_reason(r)[6:]}"] + nr + wr
                c.info = {"sector": sec, "theme_status": "UNKNOWN", "role": role, "members": int(len(members))}
                out.append(c)
        return out

    def closing(self, t, pool, facts, filings, risky, now) -> list[Candidate]:
        out = []
        for code in pool:
            r = t.loc[code]
            bar = (facts.get(code) or {}).get("bar") or {}
            if code in risky or not bar.get("high") or r["close"] < 0.97 * bar["high"] or r["close"] < r["ma20"]:
                continue
            c = Candidate(code, "closing_bet")
            c.parts["value"] = _clip01((r["pace"] - 2) / 4 * 0.6 + min(r["value"] / 5e10, 1) * 0.4)
            near52 = r["close"] >= 0.95 * r["hi250"]
            c.parts["tech"] = _clip01(0.4 * (r["close"] >= 0.99 * bar["high"]) + 0.3 * near52 + 0.3 * (r["close"] > r["hi20"]))
            c.parts["news"], nr, _ = self._news_part(code, facts, filings)
            c.parts["fund"], fr, _ = self._fund_part(code, facts)
            c.parts["flow"], wr = self._flow_part(code, facts, 5)
            c.reasons = [f"기술: 당일 고가권 진행 {r['chg'] * 100:+.1f}% (마감 미확정)" + (" · 52주 신고가 근접" if near52 else ""),
                         self._value_reason(r)] + nr + wr + fr[:1]
            c.info = {"pace": float(r["pace"]), "change": float(r["chg"])}
            out.append(c)
        return out

    # ---------------------------------------------------------------- capture & tracking
    def _capture(self, section: str, cands: list[Candidate], t: pd.DataFrame, stamp: str) -> None:
        book = self.book[section]
        ranked = sorted(cands, key=lambda c: -c.score())
        active = {c.code for c in ranked}
        for code, entry in book.items():
            price = float(t["close"].get(code, np.nan)) if code in t.index else None
            if price and price == price:
                entry["last_price"] = price
                entry["max_price"] = max(entry.get("max_price", price), price)
            entry["active"] = code in active
        for rank, c in enumerate(ranked, start=1):
            price = float(t.at[c.code, "close"])
            entry = book.get(c.code)
            if entry is None:
                entry = book[c.code] = {"code": c.code, "name": self.daily.frame.at[c.code, "name"],
                                        "captured_at": stamp, "capture_price": price, "max_price": price}
            entry.update({"active": True, "rank": rank, "score": c.score(), "parts": c.parts,
                          "reasons": c.reasons, "info": c.info, "last_price": price, "last_seen": stamp})

    def sections(self) -> dict:
        out = {}
        for s in self.SECTIONS:
            entries = list(self.book.get(s, {}).values())
            rows = []
            for v in sorted([e for e in entries if e.get("active")], key=lambda e: e.get("rank", 99)):
                cp, lp, mp = v.get("capture_price"), v.get("last_price"), v.get("max_price")
                rows.append({**v, "since_capture": (lp / cp - 1) if cp and lp else None,
                             "max_since_capture": (mp / cp - 1) if cp and mp else None})
            out[s] = {"key": s, "title": SECTION_TITLES[s], "intent": INTENT[s], "weights": dict(zip(
                ("거래대금", "기술", "재료", "기본", "수급"), WEIGHTS[s])), "day": self.day,
                "observed_at": self.last_scan.get("at"), "rows": rows, "captured_today": len(entries),
                "performance_basis": "SCAN_SAMPLED_AFTER_CAPTURE",
                "average_turnover_basis": "CLOSE_TIMES_VOLUME_ESTIMATE",
                "pace_basis": "ASSUMED_INTRADAY_CURVE", "publication_eligible": False}
        return out

    def run_forever(self, stop: threading.Event) -> None:
        while not stop.is_set():
            try:
                if self.in_session():
                    self.scan()
            except Exception as exc:
                log.error("screen failed: %s", exc, exc_info=True)
                self.last_scan = {"state": "error", "error": f"{type(exc).__name__}: {exc}",
                                  "at": self.clock().isoformat(timespec="seconds")}
            stop.wait(SCAN_EVERY_SECONDS)


def _dart_key() -> str | None:
    import os
    try:
        from dotenv import dotenv_values
    except ImportError:
        return os.getenv("DART_API_KEY")
    for path in (config.PROJECT_DIR / ".env", config.PROJECT_DIR / "backend" / ".env"):
        if path.exists():
            v = dotenv_values(path).get("DART_API_KEY")
            if v:
                return v
    return os.getenv("DART_API_KEY")
