"""Command line entry points.

    python -m condition_engine.cli import-legacy      # one-off: seed from daily_prices.csv
    python -m condition_engine.cli update             # fetch new sessions (FinanceDataReader)
    python -m condition_engine.cli quality
    python -m condition_engine.cli screen <strategy_id>
    python -m condition_engine.cli backtest <strategy_id>
    python -m condition_engine.cli label
    python -m condition_engine.cli serve [--port 8090]
"""
from __future__ import annotations

import argparse
import json
import logging
import sys

from . import config


def main(argv: list[str] | None = None) -> int:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")
    p = argparse.ArgumentParser(prog="condition_engine")
    sub = p.add_subparsers(dest="cmd", required=True)
    imp = sub.add_parser("import-legacy")
    imp.add_argument("--csv", default=None)
    up = sub.add_parser("update")
    up.add_argument("--threads", type=int, default=8)
    sub.add_parser("quality")
    sc = sub.add_parser("screen")
    sc.add_argument("strategy_id")
    sc.add_argument("--as-of", default=None)
    bt = sub.add_parser("backtest")
    bt.add_argument("strategy_id")
    sub.add_parser("label")
    sv = sub.add_parser("serve")
    sv.add_argument("--port", type=int, default=config.ENGINE_PORT)
    args = p.parse_args(argv)

    from .store import BarStore
    store = BarStore()
    config.ensure_var_dir()

    if args.cmd == "import-legacy":
        path = args.csv or next((str(c) for c in config.LEGACY_CSV_CANDIDATES if c.exists()), None)
        if not path:
            print("legacy CSV not found", file=sys.stderr)
            return 2
        _print(store.import_legacy_csv(path))
        return 0
    if args.cmd == "update":
        if store.load_listing().empty:
            print("listing:", store.refresh_listing())
        _print(store.update(threads=args.threads))
        return 0
    if args.cmd == "quality":
        _print(store.quality_report())
        return 0
    if args.cmd == "serve":
        import uvicorn
        from .api import create_app
        uvicorn.run(create_app(), host="127.0.0.1", port=args.port, log_level="info")
        return 0

    from .engine import Engine
    from .ledger import Ledger
    from .strategies import BY_ID
    from . import dsl
    engine = Engine.from_store(store)
    if args.cmd == "screen":
        s = BY_ID[args.strategy_id]
        res = engine.screen(s, args.as_of)
        Ledger().record(res, dsl.dsl_hash(dsl.validate_strategy(s, engine.features)))
        _print({k: res[k] for k in ("as_of", "regime", "counts", "conditions")})
        for r in res["results"]:
            print(f"  {r['code']} {r['name'] or '':14} {' · '.join(r['explanation'])} | 위험 {r['risk']['grade']}")
        return 0
    if args.cmd == "backtest":
        res = engine.backtest(BY_ID[args.strategy_id])
        _print({h: {w: v for w, v in st.items()} for h, st in res["horizons"].items()})
        return 0
    if args.cmd == "label":
        _print(Ledger().label(engine))
        return 0
    return 1


def _print(obj) -> None:
    print(json.dumps(obj, ensure_ascii=False, indent=2, default=str))


if __name__ == "__main__":
    raise SystemExit(main())
