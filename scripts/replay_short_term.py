#!/usr/bin/env python3
"""Replay 01c81c2's pre-AI short-term order on the newer scanner's recorded inputs.

Example: python scripts/replay_short_term.py 2026-11-30-breakout.jsonl --day 2026-11-30 --max-scans 5
The original file and its numbered continuations are read as one stream.
Uses the liquidity decisions measured in the same scan. It does not rerun KIS or AI.
"""

import argparse
import json
import math
import re
from collections import Counter
from dataclasses import dataclass, field
from datetime import date, datetime, time, timedelta, timezone
from pathlib import Path


KST = timezone(timedelta(hours=9))
RANKINGS = ("volume", "gainers", "foreignNetBuy", "turnover")
CODE = re.compile(r"[0-9]{6}")
CAPTURE_NAME = re.compile(r"(?P<stem>[0-9]{4}-[0-9]{2}-[0-9]{2}(?:-breakout)?)(?:-part-[0-9]{6})?\.jsonl")
PRICE = re.compile(r"(?:[0-9]+|[0-9]{1,3}(?:,[0-9]{3})+)\s*원?")
LEGACY_EXCLUDED_PREFIXES = (
    "KODEX", "TIGER", "ACE", "KBSTAR", "RISE", "ARIRANG", "HANARO", "SOL", "KOSEF",
    "TIMEFOLIO", "TREX", "WOORI", "PLUS", "히어로즈", "마이티",
)
LEGACY_EXCLUDED_TOKENS = (
    "ETF", "ETN", "인버스", "레버리지", "선물", "합성", "커버드콜", "액티브", "채권",
    "국고채", "국채", "회사채", "통안채", "원유", "금현물", "금선물", "은선물", "구리",
    "달러", "나스닥", "NASDAQ", "S&P", "다우", "NIKKEI", "차이나", "미국", "일본", "중국",
    "인도", "베트남", "스팩", "SPAC",
)
VERIFICATION_LIMIT = 6
PUBLICATION_LIMIT = 3


def instant(value):
    if not isinstance(value, str):
        return None
    try:
        parsed = datetime.fromisoformat(value)
        return parsed if parsed.tzinfo else None
    except ValueError:
        return None


def valid_code(value):
    return isinstance(value, str) and CODE.fullmatch(value) is not None


def data(event):
    payload = event.get("data")
    return payload if isinstance(payload, dict) else {}


def signal_codes(values):
    if not isinstance(values, list):
        return None
    codes = []
    for signal in values:
        if not isinstance(signal, dict) or not valid_code(code := signal.get("stockCode")) or code in codes:
            return None
        codes.append(code)
    return codes


def nonblank(value):
    return value if isinstance(value, str) and value.strip() else ""


def whole_number(value):
    try:
        number = int(re.sub(r"[^0-9-]", "", str(value)))
        return number if -(2**63) <= number < 2**63 else 0
    except ValueError:
        return 0


def change_rate(value):
    try:
        return float(str(value).replace("%", "").replace("+", "").replace(",", "").strip())
    except ValueError:
        return 0.0


def legacy_rank_points(rank, maximum):
    return math.floor(maximum * ((31 - min(rank, 30)) / 30) + 0.5) if rank else 0


@dataclass
class LegacyCandidate:
    code: str
    name: str = ""
    price: str = ""
    change: float = 0.0
    volume: int = 0
    ranks: dict = field(default_factory=dict)

    def accept(self, ranking, rank, row):
        self.ranks[ranking] = rank
        self.name = nonblank(row.get("name")) or self.name
        self.price = nonblank(row.get("currentPrice")) or self.price
        change = change_rate(row.get("changeRate"))
        # The previous release kept the largest change and volume from any rank response.
        self.change = math.nan if math.isnan(change) else max(self.change, change)
        self.volume = max(self.volume, whole_number(row.get("volume")))

    def eligible_identity(self):
        name = "".join(self.name.upper().split())
        price = self.price.strip()
        return (self.change > 0 and bool(name) and PRICE.fullmatch(price) is not None
                and 0 < whole_number(price) and not name.startswith(LEGACY_EXCLUDED_PREFIXES)
                and not any(token in name for token in LEGACY_EXCLUDED_TOKENS))

    def score(self):
        volume = self.ranks.get("volume", 0)
        gainers = self.ranks.get("gainers", 0)
        foreign = self.ranks.get("foreignNetBuy", 0)
        volume_points = 5 if self.volume >= 1_000_000 else 3 if self.volume >= 300_000 else int(self.volume > 0)
        # On all realistic KIS rates the cap precedes any numeric overflow.
        momentum = math.floor(min(self.change * 2.5, 25) + 0.5) if math.isfinite(self.change) else 0
        risk = 10 if self.change >= 20 else 5 if self.change >= 15 else 0
        score = (40 + legacy_rank_points(volume, 18) + legacy_rank_points(gainers, 18)
                 + legacy_rank_points(foreign, 12) + momentum + volume_points + (8 if volume and gainers else 0)
                 - risk - (6 if 0 < self.volume < 100_000 else 0))
        return max(0, min(99, score))


def legacy_candidates(payload):
    merged = {}
    for ranking in RANKINGS:
        rows = payload.get(ranking)
        if not isinstance(rows, list):
            return None
        for rank, row in enumerate(rows, 1):
            if isinstance(row, dict) and isinstance(code := row.get("code"), str) and valid_code(code := code.strip()):
                merged.setdefault(code, LegacyCandidate(code)).accept(ranking, rank, row)
    return sorted((candidate for candidate in merged.values() if candidate.eligible_identity()
                   and (candidate.score() >= 58 or ("turnover" in candidate.ranks and 3 <= candidate.change <= 15))),
                  key=lambda candidate: -candidate.score())


def measured_liquidity(selection):
    measurements = {}
    duplicates = set()
    for decision in selection.get("decisions") or []:
        if not isinstance(decision, dict) or not valid_code(code := decision.get("code")):
            continue
        if code in measurements:
            duplicates.add(code)
        status, bonus = decision.get("status"), decision.get("bonus")
        if status == "FAIL":
            measurements[code] = (status, 0)
        elif status == "PASS" and type(bonus) is int and bonus in (0, 5):
            # The newer `eligible` flag also includes the new breakout check. Do not use it here.
            measurements[code] = (status, bonus)
        else:
            measurements[code] = ("UNKNOWN", 0)
    for code in duplicates:
        measurements.pop(code)
    return measurements


@dataclass
class Scan:
    source: dict
    selections: list = field(default_factory=list)
    verification: list = field(default_factory=list)
    publications: list = field(default_factory=list)
    snapshots: list = field(default_factory=list)

    def add(self, event):
        kind = event.get("kind")
        if kind == "candidate_selection" and data(event).get("algorithm") == "CONFIRMED_BREAKOUT_V1":
            self.selections.append(event)
        elif kind == "verified_publication":
            self.verification.append(event)
        elif kind == "publication" and data(event).get("sourceStatus") in {"REALTIME_SCAN", "REALTIME_EMPTY"}:
            self.publications.append(event)
        elif kind == "realtime_snapshot" and instant(data(event).get("scanInputAt")) == instant(self.source.get("observedAt")):
            self.snapshots.append(event)

    def compare(self):
        seconds = instant(self.source.get("observedAt"))
        result = {"sourceAtKst": seconds.astimezone(KST).isoformat()}
        payload = data(self.source)
        candidates = legacy_candidates(payload)
        if candidates is None or len(self.selections) != 1:
            return {**result, "status": "MISSING_OR_AMBIGUOUS_INPUTS", "eligibleSelectionRecords": len(self.selections)}
        selection = data(self.selections[0])
        current_pool = signal_codes(selection.get("selected"))
        if current_pool is None or len(current_pool) > VERIFICATION_LIMIT:
            return {**result, "status": "INVALID_CURRENT_POOL"}
        liquidity = measured_liquidity(selection)
        passed, failed, unmeasured = [], [], []
        for item in candidates:
            status, bonus = liquidity.get(item.code, ("UNKNOWN", 0))
            if status == "PASS":
                passed.append({"code": item.code, "ruleScore": item.score(), "liquidityBonus": bonus})
            elif status == "FAIL":
                failed.append(item.code)
            else:
                unmeasured.append(item.code)
        passed.sort(key=lambda item: -(item["ruleScore"] + item["liquidityBonus"]))
        legacy_pool = [item["code"] for item in passed[:VERIFICATION_LIMIT]]
        current = self.current_results(current_pool)
        status = "COMPARED_MEASURED_CANDIDATES" if current["published"] is not None else "CURRENT_PUBLICATION_UNVERIFIED"
        return {**result, "status": status, "rankingRowCounts": {key: len(payload[key]) for key in RANKINGS},
                "kisSourceCoverage": payload.get("sourceCoverage"),
                "legacy": {"triggeredCandidates": len(candidates), "liquidityPass": len(passed),
                           "liquidityFail": len(failed), "liquidityUnmeasuredCodes": unmeasured,
                           "poolOfMeasuredSubsetPreAi": passed[:VERIFICATION_LIMIT],
                           "fullPoolPreAiMeasured": not unmeasured, "actualAiPublication": None},
                "current": current, "sharedPoolCodes": [code for code in legacy_pool if code in current_pool],
                "legacyMeasuredPoolOutsideCurrent": [code for code in legacy_pool if code not in current_pool],
                "currentPoolOutsideLegacyMeasuredSubset": [code for code in current_pool if code not in legacy_pool]}

    def current_results(self, pool):
        result = {"verificationPool": pool, "verified": None, "published": None, "mainResponseConfirmed": False}
        if len(self.verification) == 1:
            record = self.verification[0]
            verification = data(record)
            verified = signal_codes(verification.get("verified"))
            published = signal_codes(verification.get("published"))
            if (signal_codes(verification.get("verificationPool")) == pool and verified is not None
                    and set(verified) <= set(pool) and published == verified[:PUBLICATION_LIMIT]):
                source_time = instant(record.get("observedAt"))
                return {**result, "verified": verified, "published": published,
                        "mainResponseConfirmed": any(source_time is not None and instant(data(row).get("sourceAsOf")) == source_time
                            and signal_codes(data(row).get("signals")) == published for row in self.publications)}
        selected_at = instant(self.selections[0].get("observedAt"))
        if not pool and not self.verification and selected_at is not None and any(
                data(row).get("sourceStatus") == "REALTIME_EMPTY" and data(row).get("signals") == []
                and (at := instant(data(row).get("sourceAsOf"))) is not None and at >= selected_at
                for row in self.publications):
            return {**result, "verified": [], "published": [], "mainResponseConfirmed": True}
        if len(self.snapshots) == 1 and not pool and not self.verification:
            snapshot = data(self.snapshots[0])
            if (snapshot.get("sourceStatus") == "REALTIME_EMPTY" and snapshot.get("signals") == []
                    and snapshot.get("sourcesComplete") is True and snapshot.get("selectionComplete") is True):
                return {**result, "verified": [], "published": []}
        return result


def capture_streams(paths):
    seen = set()
    for given in paths:
        path = given.resolve(strict=True)
        match = CAPTURE_NAME.fullmatch(path.name)
        if not match:
            yield [path]
            continue
        stem = match["stem"]
        first = path.with_name(stem + ".jsonl")
        if first in seen:
            continue
        seen.add(first)
        parts = sorted(part for part in path.parent.glob(stem + "-part-*.jsonl")
                       if part.is_file() and CAPTURE_NAME.fullmatch(part.name))
        yield ([first] if first.is_file() else []) + parts


def scan_lines(parts):
    for path in parts:
        with path.open("rb") as stream:
            yield from stream


def scans(paths, requested_day, start, end, counts):
    for parts in capture_streams(paths):
        current = None
        for line in scan_lines(parts):
            if b'"BREAKOUT"' not in line:
                continue
            try:
                event = json.loads(line)
            except (ValueError, UnicodeError):
                counts["invalidBreakoutLines"] += 1
                continue
            if not isinstance(event, dict) or event.get("mode") != "BREAKOUT" or event.get("code") != "pool":
                continue
            kind = event.get("kind")
            counts[kind] += 1
            if kind == "source":
                if current is not None:
                    yield current.compare()
                at = instant(event.get("observedAt"))
                kst = at.astimezone(KST) if at else None
                current = Scan(event) if (kst and (not requested_day or kst.date() == requested_day)
                                         and start <= kst.time() <= end) else None
            elif current is not None:
                current.add(event)
        if current is not None:
            yield current.compare()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("captures", nargs="+", type=Path, help="Saved market-only condition capture JSONL files")
    parser.add_argument("--day", type=date.fromisoformat, help="KST market date, YYYY-MM-DD")
    parser.add_argument("--start-kst", type=time.fromisoformat, default=time(9))
    parser.add_argument("--end-kst", type=time.fromisoformat, default=time(15, 20))
    parser.add_argument("--max-scans", type=int, help="Stop after this many matching source scans")
    args = parser.parse_args()
    if (args.start_kst.tzinfo or args.end_kst.tzinfo or args.start_kst > args.end_kst
            or (args.max_scans is not None and args.max_scans < 1)):
        parser.error("choose an increasing local KST time range and a positive scan limit")
    counts = Counter()
    results = []
    try:
        for result in scans(args.captures, args.day, args.start_kst, args.end_kst, counts):
            results.append(result)
            if len(results) == args.max_scans:
                break
    except OSError as error:
        parser.exit(1, f"Could not read capture file: {error.filename}\n")
    report = {"status": "OK" if any(row["status"] == "COMPARED_MEASURED_CANDIDATES" for row in results) else "NO_COMPARABLE_SCAN",
              "baselineCommit": "01c81c2", "currentCaptureAlgorithm": "CONFIRMED_BREAKOUT_V1",
              "limitations": ["Legacy pre-AI ranking reuses same-run measured liquidity decisions for covered codes only.",
                              "Unmeasured legacy candidates can change the full result. Legacy AI was not run.",
                              "Current captures do not reproduce upstream outages or counterfactual network timing.",
                              "This report compares candidate sets and actual current publication, not realized returns."],
              "parsedBreakoutEvents": dict(counts), "scans": results}
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return 0 if report["status"] == "OK" else 2


if __name__ == "__main__":
    raise SystemExit(main())
