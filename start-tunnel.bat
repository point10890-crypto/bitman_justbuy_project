@echo off
chcp 65001 >nul
title BitMan JustBuy - Cloudflare Tunnel

echo ========================================
echo   Cloudflare Tunnel (port 8080)
echo ========================================
echo.
echo   터널 시작 후 표시되는 URL을 복사하세요.
echo   예: https://xxxxx.trycloudflare.com
echo.
echo   이 URL을 프론트엔드 API_URL로 사용합니다.
echo ========================================
echo.

cloudflared tunnel --url http://localhost:8080

pause
