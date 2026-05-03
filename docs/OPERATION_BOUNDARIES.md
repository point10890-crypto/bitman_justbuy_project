# JustBuy / MarketFlow Operation Boundaries

Last verified: 2026-05-03 KST

This document is the source of truth for keeping JustBuy and MarketFlow separated on the MiniPC and in deployment scripts.

## Canonical Ownership

| Item | JustBuy | MarketFlow |
| --- | --- | --- |
| Project role | Membership, subscription, admin approval, JustBuy API | MarketFlow data pipeline and market service |
| MiniPC root | `C:\bitman_justbuy` | `C:\bitman_marketfloww` |
| Local dev root | `C:\bitman_justbuy_project` | Do not use this repo for MarketFlow deployment |
| Runtime port | `8080` | `5001` |
| Public API host | `api.bit-man.net` | `marketflow-api.bit-man.net` |
| Primary domain | None | `bitman.net`, `www.bitman.net` |
| Runtime process | `java -jar justbuy-api-1.0.0.jar` | `.venv\Scripts\python.exe flask_app.py` and scheduler tasks |
| Data ownership | `C:\bitman_justbuy\backend\data` | `C:\bitman_marketfloww\data` and MarketFlow output folders |

## Hard Rules

1. `bitman.net` and `www.bitman.net` must never route to JustBuy.
2. JustBuy must only use `api.bit-man.net` publicly and `localhost:8080` internally.
3. MarketFlow must only use `marketflow-api.bit-man.net` publicly and `localhost:5001` internally.
4. Do not copy files between `C:\bitman_justbuy` and `C:\bitman_marketfloww` unless the user explicitly asks for a one-time migration.
5. A JustBuy deployment must never modify `C:\bitman_marketfloww`.
6. A MarketFlow deployment must never modify `C:\bitman_justbuy`.
7. If a script contains both roots, treat it as suspicious and review it before running.

## MiniPC Folder Layout

### JustBuy

```text
C:\bitman_justbuy
├── backend
│   ├── justbuy-api-1.0.0.jar
│   ├── .env
│   ├── data
│   │   ├── justbuy-db.mv.db
│   │   └── .jwt-secret
│   └── logs
├── scripts
│   ├── start-springboot.bat
│   ├── backup-h2.ps1
│   └── autostart.vbs
└── backups
```

### MarketFlow

```text
C:\bitman_marketfloww
├── app
├── backend
├── data
├── deploy
├── frontend-react
├── logs
├── scripts
└── .venv
```

## Cloudflared Ingress Boundary

The MiniPC Cloudflare tunnel config must follow this shape:

```yaml
ingress:
  - hostname: api.bit-man.net
    service: http://localhost:8080
  - hostname: marketflow-api.bit-man.net
    service: http://localhost:5001
  - service: http_status:404
```

Forbidden JustBuy ingress examples:

```yaml
- hostname: bitman.net
  service: http://localhost:8080
- hostname: www.bitman.net
  service: http://localhost:8080
```

## Task Scheduler Boundary

Expected task names and roots:

| Task prefix | Must point to |
| --- | --- |
| `BitMan-JustBuy-*` | `C:\bitman_justbuy\...` only |
| `MarketFlow-*` | `C:\bitman_marketfloww\...` only |

If a `BitMan-JustBuy-*` task points to `C:\bitman_marketfloww`, or a `MarketFlow-*` task points to `C:\bitman_justbuy`, stop and fix the task before continuing.

## Quick Verification

Run from this repo:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\audit-minipc-boundaries.ps1
```

Expected service checks:

```text
https://api.bit-man.net/api/health              -> justbuy-api
https://marketflow-api.bit-man.net/api/health   -> MarketFlow API
https://bitman.net                              -> not JustBuy
https://www.bitman.net                          -> not JustBuy
```

