"""Local HTTP API for the condition engine (binds 127.0.0.1 only).

In production this would sit behind the Spring Boot auth boundary; locally the
Vite dev server proxies /engine to it.
"""
from __future__ import annotations

import logging
import threading
from contextlib import asynccontextmanager
import time
import traceback
from typing import Any

from fastapi import FastAPI, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel, Field

from . import config, dsl
from .agent import build_translator
from .detectors import SectionDetectors
from .scanner import SectionScanner
from .engine import Engine
from .features import catalogue
from .ledger import Ledger
from .risk import FLAG_INFO, REGIME_INFO
from .store import BarStore
from .strategies import BY_ID, EVIDENCE_BASIS, public_view

log = logging.getLogger("condition_engine")


class EngineService:
    """Owns the store, the in-memory engine, the ledger and the translator."""

    def __init__(self, store: BarStore | None = None, ledger: Ledger | None = None,
                 translator=None, engine: Engine | None = None):
        config.ensure_var_dir()
        self.store = store or BarStore()
        self.ledger = ledger or Ledger()
        self.translator = translator or build_translator(ledger=self.ledger)
        self.engine = engine
        self.state = "ready" if engine else "idle"
        self.error: str | None = None
        self.loaded_at: float | None = time.time() if engine else None
        self.update_job: dict = {"state": "idle"}
        self.scanner: SectionScanner | None = None
        self.autoscan = False                     # the served app turns this on; tests do not
        self._stop = threading.Event()
        self._detectors: SectionDetectors | None = None
        self._sections_cache: tuple[float, str, dict] | None = None
        self._lock = threading.Lock()

    def load(self) -> None:
        with self._lock:
            self.state = "loading"
            try:
                started = time.time()
                self.engine = Engine.from_store(self.store)
                self._detectors = SectionDetectors(self.engine, self.store)
                self._sections_cache = None
                self.loaded_at = time.time()
                self.state = "ready"
                log.info("engine ready in %.1fs (%s)", self.loaded_at - started, self.engine.data_version)
            except Exception as exc:
                self.state, self.error = "error", f"{type(exc).__name__}: {exc}"
                log.error("engine load failed: %s", traceback.format_exc())
                return
        # the screener needs a ready engine; a screener failure must not take the engine down
        if self.autoscan:
            try:
                self.start_scanner()
            except Exception:
                log.error("screener start failed: %s", traceback.format_exc())

    def load_async(self) -> None:
        threading.Thread(target=self.load, daemon=True).start()

    def require(self) -> Engine:
        if self.state != "ready" or self.engine is None:
            raise HTTPException(503, {"state": self.state, "error": self.error,
                                      "message": "엔진이 데이터를 불러오는 중입니다"})
        return self.engine

    def detectors(self) -> SectionDetectors:
        eng = self.require()
        if self._detectors is None or self._detectors.eng is not eng:
            self._detectors = SectionDetectors(eng, self.store)
        return self._detectors

    def sections(self, ttl: float = 60.0) -> dict:
        """The home sections: the intraday screener's captures; the end-of-day list as fallback."""
        det = self.detectors()
        scanner = self.scanner
        if scanner is not None and scanner.det is not det:
            scanner.det = det                       # the engine reloaded after the daily update
        cached = self._sections_cache
        key = f"{det.eng.data_version}|{scanner.last_scan.get('at') if scanner else None}"
        if cached and cached[1] == key and time.time() - cached[0] < ttl:
            return cached[2]
        fallback = det.all_sections()
        if scanner is not None and scanner.day:
            live = scanner.sections()
            sections = {k: (live[k] if live[k]["rows"] or scanner.day >= fallback["as_of"] else fallback["sections"][k])
                        for k in live}
        else:
            sections = fallback["sections"]
        payload = {**fallback, "sections": sections,
                   "scanner": scanner.last_scan if scanner is not None else {"state": "off"}}
        self._sections_cache = (time.time(), key, payload)
        return payload

    def start_scanner(self) -> None:
        """Intraday condition screener: re-scan the market every few minutes during the session."""
        if self.scanner is not None:
            return
        self.scanner = SectionScanner(self.store, self.detectors())
        threading.Thread(target=self.scanner.run_forever, args=(self._stop,), daemon=True).start()

    def run_update(self) -> None:
        self.update_job = {"state": "running", "started": time.time()}
        try:
            if self.store.load_listing().empty:
                self.store.refresh_listing()
            report = self.store.update()
            self.update_job = {"state": "done", "report": report, "finished": time.time()}
            self.load()
        except Exception as exc:
            self.update_job = {"state": "error", "error": f"{type(exc).__name__}: {exc}"}


class StrategyRequest(BaseModel):
    strategy: dict[str, Any] | None = None
    strategy_id: str | None = None
    as_of: str | None = None
    record: bool = True
    entry: str = Field("open1", pattern="^(open1|close)$")


class TranslateRequest(BaseModel):
    text: str = Field(..., min_length=1, max_length=300)


class RiskRequest(BaseModel):
    codes: list[str] = Field(..., min_length=1, max_length=50)
    as_of: str | None = None


def create_app(service: EngineService | None = None, autoload: bool = True) -> FastAPI:
    svc = service or EngineService()

    @asynccontextmanager
    async def lifespan(_app):
        if autoload and svc.state == "idle":
            svc.autoscan = True
            svc.load_async()          # load in the background; endpoints answer 503 until ready
        yield
        svc._stop.set()

    app = FastAPI(title="BitMan Condition Engine", version="0.1.0", lifespan=lifespan)
    app.state.service = svc
    app.add_middleware(CORSMiddleware, allow_origins=["http://localhost:3000", "http://127.0.0.1:3000"],
                       allow_methods=["GET", "POST"], allow_headers=["*"])

    def resolve(req: StrategyRequest) -> dict:
        if req.strategy_id:
            if req.strategy_id not in BY_ID:
                raise HTTPException(404, f"전략을 찾을 수 없습니다: {req.strategy_id}")
            return dict(BY_ID[req.strategy_id])
        if req.strategy:
            return req.strategy
        raise HTTPException(422, "strategy 또는 strategy_id 가 필요합니다")

    def guarded(fn):
        try:
            return fn()
        except dsl.DslError as exc:
            raise HTTPException(422, str(exc))

    @app.get("/engine/health")
    def health():
        eng = svc.engine
        quality = svc.store.quality_report() if svc.store.bars_file.exists() else {"rows": 0}
        return {
            "state": svc.state, "error": svc.error, "data_version": eng.data_version if eng else None,
            "last_session": str(eng.panel.dates[-1].date()) if eng and len(eng.panel.dates) else None,
            "codes": int(eng.panel.close.shape[1]) if eng else 0,
            "provisional_dates": quality.get("provisional_dates", []),
            "unrepairable_provisional": quality.get("unrepairable_provisional"),
            "last_final_date": quality.get("last_final_date"),
            "translator": getattr(svc.translator, "chain", "rules"),
            "update_job": {k: v for k, v in svc.update_job.items() if k != "report"},
        }

    @app.get("/engine/features")
    def features():
        return {"features": catalogue(), "risk_flags": FLAG_INFO, "regimes": REGIME_INFO}

    @app.get("/engine/strategies")
    def strategies():
        return {"strategies": public_view(), "evidence_basis": EVIDENCE_BASIS}

    @app.post("/engine/translate")
    def translate(req: TranslateRequest):
        return svc.translator.translate(req.text).as_dict()

    @app.post("/engine/screen")
    def screen(req: StrategyRequest):
        eng = svc.require()
        strategy = resolve(req)
        if strategy.get("status") == "retired":
            raise HTTPException(409, "검증에서 탈락한 전략은 실행하지 않습니다. 백테스트로 근거를 확인하세요.")
        result = guarded(lambda: eng.screen(strategy, req.as_of))
        if req.record:
            validated = dsl.validate_strategy(strategy, eng.features)
            result["run_id"] = svc.ledger.record(result, dsl.dsl_hash(validated))
        return result

    @app.post("/engine/backtest")
    def backtest(req: StrategyRequest):
        eng = svc.require()
        strategy = resolve(req)
        return guarded(lambda: eng.backtest(strategy, entry=req.entry))

    @app.post("/engine/risk")
    def risk(req: RiskRequest):
        return guarded(lambda: svc.require().risk_check(req.codes, req.as_of))

    @app.get("/engine/sections")
    def sections():
        return svc.sections()

    @app.post("/engine/admin/scan")
    def scan_now():
        if svc.scanner is None:
            raise HTTPException(409, "scanner is not running")
        return svc.scanner.scan()

    @app.get("/engine/regime")
    def regime():
        return svc.require().regime_now()

    @app.get("/engine/scorecard")
    def scorecard(strategy_id: str | None = None, dsl_hash: str | None = None):
        return svc.ledger.scorecard(dsl_hash=dsl_hash, strategy_id=strategy_id)

    @app.get("/engine/usage")
    def usage(days: int = 1):
        return {"llm_calls": svc.ledger.llm_usage(days)}

    @app.post("/engine/admin/label")
    def label():
        return svc.ledger.label(svc.require())

    @app.post("/engine/admin/update")
    def update():
        if svc.update_job.get("state") == "running":
            return svc.update_job
        threading.Thread(target=svc.run_update, daemon=True).start()
        return {"state": "running"}

    @app.get("/engine/admin/update")
    def update_status():
        return svc.update_job

    return app
