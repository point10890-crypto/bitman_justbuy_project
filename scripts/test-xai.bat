@echo off
chcp 65001 >/dev/null
cd /d C:\bitman_justbuy_project
for /f "usebackq tokens=* delims=" %%L in (".env") do (
    echo %%L | findstr /r "^[A-Z]" >/dev/null ^&^& (
        for /f "tokens=1,* delims==" %%A in ("%%L") do (
            if not "%%B"=="" set "%%A=%%B"
        )
    )
)
echo XAI=[%XAI_API_KEY%]
