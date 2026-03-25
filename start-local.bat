@echo off
chcp 65001 >nul
title BitMan JustBuy - Local Server

echo ========================================
echo   BitMan JustBuy Local Server
echo ========================================
echo.

:: Load .env file
for /f "usebackq tokens=1,* delims==" %%a in ("backend\.env") do (
    if not "%%a"=="" (
        echo %%a | findstr /r "^#" >nul || (
            set "%%a=%%b"
        )
    )
)

echo [OK] Environment variables loaded
echo [OK] Starting Spring Boot on port 8080...
echo.

cd backend
call gradlew.bat bootRun --args="--server.port=8080"

pause
