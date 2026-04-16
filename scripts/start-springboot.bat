@echo off
chcp 65001 >nul
cd /d C:\bitman_justbuy_project

REM Load environment variables from .env (public/shared keys, not secrets)
for /f "usebackq tokens=* delims=" %%L in (".env") do (
    echo %%L | findstr /r "^[A-Z]" >nul && (
        for /f "tokens=1,* delims==" %%A in ("%%L") do (
            if not "%%B"=="" set "%%A=%%B"
        )
    )
)

REM Load secrets from backend/.env (gitignored — NEVER commit this file)
REM This overrides any stale values from the root .env
for /f "usebackq tokens=* delims=" %%L in ("backend\.env") do (
    echo %%L | findstr /r "^[A-Z]" >nul && (
        for /f "tokens=1,* delims==" %%A in ("%%L") do (
            if not "%%B"=="" set "%%A=%%B"
        )
    )
)

REM Channel bot token defaults to the same bot as personal (single-bot setup)
if not defined TELEGRAM_CHANNEL_BOT_TOKEN set "TELEGRAM_CHANNEL_BOT_TOKEN=%TELEGRAM_BOT_TOKEN%"

cd /d C:\bitman_justbuy_project\backend

REM Ensure log directory and rotate existing logs (keep 2 backups)
if not exist "C:\bitman_justbuy_project\logs" mkdir "C:\bitman_justbuy_project\logs"
if exist "C:\bitman_justbuy_project\logs\justbuy-api.log.2" del "C:\bitman_justbuy_project\logs\justbuy-api.log.2"
if exist "C:\bitman_justbuy_project\logs\justbuy-api.log.1" move /y "C:\bitman_justbuy_project\logs\justbuy-api.log.1" "C:\bitman_justbuy_project\logs\justbuy-api.log.2" >nul
if exist "C:\bitman_justbuy_project\logs\justbuy-api.log"   move /y "C:\bitman_justbuy_project\logs\justbuy-api.log"   "C:\bitman_justbuy_project\logs\justbuy-api.log.1" >nul

REM Redirect stdout/stderr to log file for diagnostics
"C:\Program Files\Microsoft\jdk-21.0.10.7-hotspot\bin\java.exe" -jar build\libs\justbuy-api-1.0.0.jar > "C:\bitman_justbuy_project\logs\justbuy-api.log" 2>&1
