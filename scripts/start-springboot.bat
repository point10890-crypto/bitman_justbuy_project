@echo off
chcp 65001 >nul
cd /d C:\bitman_justbuy_project

REM .env 파일에서 환경변수 로드 (주석/빈줄 제외)
for /f "usebackq tokens=* delims=" %%L in (".env") do (
    echo %%L | findstr /r "^[A-Z]" >nul && (
        for /f "tokens=1,* delims==" %%A in ("%%L") do (
            if not "%%B"=="" set "%%A=%%B"
        )
    )
)

cd /d C:\bitman_justbuy_project\backend
"C:\Program Files\Microsoft\jdk-21.0.10.7-hotspot\bin\java.exe" -jar build\libs\justbuy-api-1.0.0.jar
