@echo off
chcp 65001 >nul
title BitMan JustBuy - Local Server

echo ========================================
echo   BitMan JustBuy Local Server
echo ========================================
echo.

:: Load .env file from project root
if exist ".env" (
    for /f "usebackq tokens=1,* delims==" %%a in (".env") do (
        if not "%%a"=="" (
            echo %%a | findstr /r "^#" >nul || (
                set "%%a=%%b"
            )
        )
    )
) else (
    echo [WARN] .env file not found, using system environment variables
)

echo [OK] Environment variables loaded
echo [OK] Starting Spring Boot on port 8080...
echo.

cd backend
call gradlew.bat bootRun --args="--server.port=8080"

pause
