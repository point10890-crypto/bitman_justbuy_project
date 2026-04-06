#!/bin/bash
# BitMan JustBuy — 코드 변경 후 배포 스크립트
# 프론트엔드 빌드 → static 복사 → JAR 빌드 → Spring Boot 재시작
#
# 사용법: bash scripts/deploy.sh

set -e
PROJECT="/c/bitman_justbuy_project"
FRONTEND="$PROJECT/frontend"
BACKEND="$PROJECT/backend"

echo "=== [1/4] Frontend build ==="
cd "$FRONTEND" && npm run build
echo ""

echo "=== [2/4] Copy dist → static ==="
rm -rf "$BACKEND/src/main/resources/static/"*
cp -r "$FRONTEND/dist/"* "$BACKEND/src/main/resources/static/"
echo "Done"
echo ""

echo "=== [3/4] Spring Boot JAR build ==="
cd "$BACKEND" && ./gradlew bootJar -x test
echo ""

echo "=== [4/4] Restart Spring Boot ==="
# 기존 프로세스 종료
netstat -ano | grep 8080 | awk '{print $5}' | sort -u | xargs -I{} taskkill //F //PID {} 2>/dev/null || true
sleep 2

# JAR 실행
cd "$BACKEND" && "C:\Program Files\Microsoft\jdk-21.0.10.7-hotspot\bin\java.exe" -jar build/libs/justbuy-api-1.0.0.jar &
sleep 15

# 확인
STATUS=$(curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/)
if [ "$STATUS" = "200" ]; then
    echo "✅ Deploy complete — http://localhost:8080 OK"
else
    echo "❌ Deploy failed — status $STATUS"
fi
