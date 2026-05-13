@echo off
chcp 65001 >nul
cd /d C:\bitman_justbuy\backend

REM Load secrets from backend\.env (gitignored).
for /f "usebackq tokens=* delims=" %%L in ("C:\bitman_justbuy\backend\.env") do (
    echo %%L | findstr /r "^[A-Z]" >nul && (
        for /f "tokens=1,* delims==" %%A in ("%%L") do (
            if not "%%B"=="" set "%%A=%%B"
        )
    )
)

REM Channel bot token defaults to the same bot as personal (single-bot setup).
if not defined TELEGRAM_CHANNEL_BOT_TOKEN set "TELEGRAM_CHANNEL_BOT_TOKEN=%TELEGRAM_BOT_TOKEN%"

REM Ensure log directory and rotate existing logs (keep 2 backups).
if not exist "C:\bitman_justbuy\backend\logs" mkdir "C:\bitman_justbuy\backend\logs"
if exist "C:\bitman_justbuy\backend\logs\justbuy-api.log.2" del "C:\bitman_justbuy\backend\logs\justbuy-api.log.2"
if exist "C:\bitman_justbuy\backend\logs\justbuy-api.log.1" move /y "C:\bitman_justbuy\backend\logs\justbuy-api.log.1" "C:\bitman_justbuy\backend\logs\justbuy-api.log.2" >nul
if exist "C:\bitman_justbuy\backend\logs\justbuy-api.log"   move /y "C:\bitman_justbuy\backend\logs\justbuy-api.log"   "C:\bitman_justbuy\backend\logs\justbuy-api.log.1" >nul

REM Use an absolute Java path so the SYSTEM startup watchdog does not depend on user PATH.
set "JAVA_EXE=C:\Program Files\Eclipse Adoptium\jdk-21.0.10.7-hotspot\bin\java.exe"
if not exist "%JAVA_EXE%" set "JAVA_EXE=java"

REM Redirect stdout/stderr to log file for diagnostics.
"%JAVA_EXE%" -jar justbuy-api-1.0.0.jar > "C:\bitman_justbuy\backend\logs\justbuy-api.log" 2>&1
