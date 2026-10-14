"""Candidate ledger: every screened name, every stage, labelled later.

The previous pipeline dropped AI-rejected names without a trace, so nobody could
ask whether the rejection was right. Here a run stores published, guard-excluded
and below-top-n names alike, and the scorecard compares them:

  selection edge = published - below_top_n   (does the ranking help?)
  guard edge     = published - guard_excluded (does the risk guard help?)
"""
from __future__ import annotations

import json
import sqlite3
import time
from datetime import datetime
from pathlib import Path

import numpy as np
import pandas as pd

from . import config

SCHEMA = """
CREATE TABLE IF NOT EXISTS runs (
  run_id TEXT PRIMARY KEY, strategy_id TEXT, title TEXT, dsl_hash TEXT, dsl_json TEXT,
  as_of TEXT, regime TEXT, data_version TEXT, created_at TEXT
);
CREATE TABLE IF NOT EXISTS observations (
  id INTEGER PRIMARY KEY AUTOINCREMENT, run_id TEXT, code TEXT, stage TEXT,
  rank REAL, reasons TEXT, snapshot_json TEXT,
  UNIQUE(run_id, code)
);
CREATE TABLE IF NOT EXISTS outcomes (
  observation_id INTEGER, h INTEGER, entry TEXT, ret REAL, excess REAL, index_excess REAL,
  status TEXT, labeled_at TEXT, PRIMARY KEY (observation_id, h, entry)
);
CREATE TABLE IF NOT EXISTS llm_calls (
  id INTEGER PRIMARY KEY AUTOINCREMENT, ts TEXT, role TEXT, provider TEXT, model TEXT,
  ok INTEGER, latency_ms INTEGER, note TEXT
);
CREATE INDEX IF NOT EXISTS ix_obs_run ON observations(run_id);
CREATE INDEX IF NOT EXISTS ix_runs_hash ON runs(dsl_hash, as_of);
"""

STAGES = ("PUBLISHED", "BELOW_TOP_N", "GUARD_EXCLUDED")


class Ledger:
    def __init__(self, path: Path | str | None = None):
        self.path = Path(path or config.LEDGER_DB)
        self.path.parent.mkdir(parents=True, exist_ok=True)
        with self._conn() as c:
            c.executescript(SCHEMA)

    def _conn(self) -> sqlite3.Connection:
        conn = sqlite3.connect(self.path, timeout=10)
        conn.row_factory = sqlite3.Row
        return conn

    # ---------------------------------------------------------------- record
    def record(self, screen: dict, dsl_hash: str) -> str:
        """Store one run. Re-recording the same strategy on the same day replaces it."""
        strat = screen["strategy"]
        run_id = f"{dsl_hash[:16]}:{screen['as_of']}"
        rows = [(r["code"], "PUBLISHED", r.get("rank"), None, json.dumps(r.get("snapshot"), ensure_ascii=False))
                for r in screen["results"]]
        preview = {r["code"]: r for r in screen.get("excluded", [])}
        for code, reasons in screen.get("excluded_codes", {}).items():
            snap = preview.get(code, {}).get("snapshot")
            rows.append((code, "GUARD_EXCLUDED", None, json.dumps(reasons),
                         json.dumps(snap, ensure_ascii=False) if snap else None))
        for i, code in enumerate(screen.get("below_top_n", []), start=len(screen["results"]) + 1):
            rows.append((code, "BELOW_TOP_N", float(i), None, None))
        with self._conn() as c:
            c.execute("DELETE FROM outcomes WHERE observation_id IN (SELECT id FROM observations WHERE run_id=?)", (run_id,))
            c.execute("DELETE FROM observations WHERE run_id=?", (run_id,))
            c.execute("INSERT OR REPLACE INTO runs VALUES (?,?,?,?,?,?,?,?,?)", (
                run_id, strat.get("id"), strat.get("title"), dsl_hash, json.dumps(strat, ensure_ascii=False),
                screen["as_of"], (screen.get("regime") or {}).get("code"), screen.get("data_version"),
                datetime.now(config.KST).isoformat()))
            c.executemany("INSERT INTO observations(run_id, code, stage, rank, reasons, snapshot_json) VALUES (?,?,?,?,?,?)",
                          [(run_id, *r) for r in rows])
        return run_id

    # ----------------------------------------------------------------- label
    def label(self, engine, horizons=config.HORIZONS, entry: str = "open1") -> dict:
        dates = engine.panel.dates
        pos = {d: i for i, d in enumerate(dates)}
        done = pending = missing = 0
        with self._conn() as c:
            todo = c.execute("""
                SELECT o.id, o.code, r.as_of FROM observations o JOIN runs r ON r.run_id=o.run_id
                WHERE (SELECT COUNT(*) FROM outcomes x WHERE x.observation_id=o.id AND x.status!='pending'
                       AND x.entry=?) < ?""", (entry, len(horizons))).fetchall()
            now = datetime.now(config.KST).isoformat()
            for obs in todo:
                t = pd.Timestamp(obs["as_of"])
                i = pos.get(t)
                for h in horizons:
                    fwd, exc, bench = engine.labels(h, entry)
                    status, ret, ex, ix = "pending", None, None, None
                    if i is not None and i + h + 1 < len(dates) and obs["code"] in fwd.columns:
                        r = fwd.at[t, obs["code"]]
                        if np.isfinite(r):
                            status, ret = "done", float(r)
                            e = exc.at[t, obs["code"]]
                            ex = float(e) if np.isfinite(e) else None
                            b = bench.get(t)
                            ix = float(r - b) if b is not None and np.isfinite(b) else None
                        else:
                            status = "missing"          # halted / corporate action — never filled in
                    c.execute("INSERT OR REPLACE INTO outcomes VALUES (?,?,?,?,?,?,?,?)",
                              (obs["id"], h, entry, ret, ex, ix, status, now))
                    done += status == "done"
                    pending += status == "pending"
                    missing += status == "missing"
        return {"observations": len(todo), "done": done, "pending": pending, "missing": missing}

    # ------------------------------------------------------------- scorecard
    def scorecard(self, dsl_hash: str | None = None, strategy_id: str | None = None,
                  entry: str = "open1") -> dict:
        where, args = [], [entry]
        if dsl_hash:
            where.append("r.dsl_hash = ?"); args.append(dsl_hash)
        if strategy_id:
            where.append("r.strategy_id = ?"); args.append(strategy_id)
        clause = (" AND " + " AND ".join(where)) if where else ""
        with self._conn() as c:
            rows = c.execute(f"""
                SELECT r.strategy_id, r.as_of, o.stage, x.h, x.excess, x.ret, x.status
                FROM outcomes x JOIN observations o ON o.id=x.observation_id JOIN runs r ON r.run_id=o.run_id
                WHERE x.entry=? {clause}""", args).fetchall()
            runs = c.execute(f"SELECT COUNT(*) n, MIN(as_of) a, MAX(as_of) b FROM runs r WHERE 1=1 {clause}",
                             args[1:]).fetchone()
        df = pd.DataFrame([dict(r) for r in rows])
        out = {"runs": runs["n"], "first": runs["a"], "last": runs["b"], "entry": entry, "horizons": {}}
        if df.empty:
            return out
        for h, g in df.groupby("h"):
            done = g[g.status == "done"]
            stage_stats = {}
            for stage in STAGES:
                s = done[done.stage == stage]["excess"].dropna()
                stage_stats[stage] = {"n": int(len(s)),
                                      "mean_excess_pct": round(float(s.mean()) * 100, 2) if len(s) else None,
                                      "hit_rate_pct": round(float((s > 0).mean()) * 100, 1) if len(s) else None}
            pub = stage_stats["PUBLISHED"]["mean_excess_pct"]
            below = stage_stats["BELOW_TOP_N"]["mean_excess_pct"]
            guard = stage_stats["GUARD_EXCLUDED"]["mean_excess_pct"]
            out["horizons"][str(int(h))] = {
                "stages": stage_stats,
                "selection_edge_pct": round(pub - below, 2) if pub is not None and below is not None else None,
                "guard_edge_pct": round(pub - guard, 2) if pub is not None and guard is not None else None,
                "pending": int((g.status == "pending").sum()), "missing": int((g.status == "missing").sum()),
            }
        return out

    # ------------------------------------------------------------ llm calls
    def log_llm(self, role: str, provider: str, model: str, ok: bool, latency_ms: int, note: str = "") -> None:
        with self._conn() as c:
            c.execute("INSERT INTO llm_calls(ts, role, provider, model, ok, latency_ms, note) VALUES (?,?,?,?,?,?,?)",
                      (datetime.now(config.KST).isoformat(), role, provider, model, int(ok), latency_ms, note[:200]))

    def llm_usage(self, days: int = 1) -> list[dict]:
        since = (pd.Timestamp.now(tz=config.KST) - pd.Timedelta(days=days)).isoformat()
        with self._conn() as c:
            rows = c.execute("""SELECT role, provider, model, COUNT(*) calls, SUM(ok) ok, AVG(latency_ms) latency
                                FROM llm_calls WHERE ts >= ? GROUP BY role, provider, model""", (since,)).fetchall()
        return [dict(r) for r in rows]
