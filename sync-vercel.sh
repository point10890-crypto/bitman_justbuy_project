#!/bin/bash
# ============================================================
# MarketFlow 로컬 → Vercel 직접 배포
# Flask API 응답을 스냅샷으로 저장 → Vercel CLI로 직접 배포
# GitHub 불필요 — 로컬에서 바로 프로덕션 배포
# ============================================================

PROJECT="/c/bitman_service"
PYTHON="$PROJECT/.venv/Scripts/python.exe"
FRONTEND="$PROJECT/frontend"
SNAPSHOT_DIR="$FRONTEND/data-snapshot"
FLASK_PORT=5001

mkdir -p "$SNAPSHOT_DIR"

echo ""
echo "╔══════════════════════════════════════════════╗"
echo "║  MarketFlow → Vercel 직접 배포 (CLI)         ║"
echo "╚══════════════════════════════════════════════╝"
echo ""

# ── 0. Flask 서버 확인 ──
if ! netstat -ano | grep -q ":${FLASK_PORT}.*LISTENING"; then
    echo "[ERROR] Flask 서버가 포트 ${FLASK_PORT}에서 실행 중이 아닙니다!"
    echo "        먼저 서버를 시작하세요: start-local.sh"
    exit 1
fi
echo "[OK] Flask 서버 확인 (포트 ${FLASK_PORT})"
echo ""

# ── 1. Flask API에서 스냅샷 추출 ──
echo "[1/3] Flask API → 스냅샷 추출 중..."

cd "$PROJECT" && PYTHONIOENCODING=utf-8 "$PYTHON" -c "
import json, os, sys, time
from datetime import datetime

try:
    import requests
except ImportError:
    print('  [ERROR] requests 패키지 없음')
    sys.exit(1)

SNAPSHOT_DIR = '${SNAPSHOT_DIR}'.replace('/c/', 'C:/')
FLASK = 'http://localhost:${FLASK_PORT}'

ok_count = 0
fail_count = 0

def snapshot(endpoint, filename):
    global ok_count, fail_count
    try:
        resp = requests.get(f'{FLASK}{endpoint}', timeout=15)
        if resp.status_code == 200:
            data = resp.json()
            path = os.path.join(SNAPSHOT_DIR, filename)
            with open(path, 'w', encoding='utf-8') as f:
                json.dump(data, f, ensure_ascii=False, indent=2, default=str)
            size = os.path.getsize(path)
            ok_count += 1
            print(f'  ✓ {filename:45s} {size:>10,} bytes')
        else:
            fail_count += 1
            print(f'  ✗ {filename:45s} HTTP {resp.status_code}')
    except Exception as e:
        fail_count += 1
        print(f'  ✗ {filename:45s} {str(e)[:50]}')

# ── KR Market ──
print('  ── KR Market ──')
snapshot('/api/kr/signals',             'kr-signals.json')
snapshot('/api/kr/ai-analysis',         'kr-ai-analysis.json')
snapshot('/api/kr/vcp-stats',           'kr-vcp-stats.json')
snapshot('/api/kr/vcp-history?days=90', 'kr-vcp-history.json')
snapshot('/api/kr/market-gate',         'kr-market-gate.json')
snapshot('/api/kr/backtest-summary',    'kr-backtest-summary.json')
snapshot('/api/kr/jongga-v2/latest',    'kr-jongga-v2-latest.json')
snapshot('/api/kr/jongga-v2/dates',     'kr-jongga-v2-dates.json')

# ── US Market ──
print('  ── US Market ──')
snapshot('/api/us/portfolio',               'us-portfolio.json')
snapshot('/api/us/market-gate',             'us-market-gate.json')
snapshot('/api/us/smart-money',             'us-smart-money.json')
snapshot('/api/us/decision-signal',         'us-decision-signal.json')
snapshot('/api/us/market-briefing',         'us-market-briefing.json')
snapshot('/api/us/macro-analysis',          'us-macro-analysis.json')
snapshot('/api/us/market-regime',           'us-market-regime.json')
snapshot('/api/us/index-prediction',        'us-index-prediction.json')
snapshot('/api/us/risk-alerts',             'us-risk-alerts.json')
snapshot('/api/us/sector-rotation',         'us-sector-rotation.json')
snapshot('/api/us/backtest',                'us-backtest.json')
snapshot('/api/us/top-picks-report',        'us-top-picks-report.json')
snapshot('/api/us/cumulative-performance',  'us-cumulative-performance.json')
snapshot('/api/us/earnings-impact',         'us-earnings-impact.json')
snapshot('/api/us/heatmap-data',            'us-heatmap-data.json')
snapshot('/api/us/institutional',           'us-institutional.json')
snapshot('/api/us/etf-flows',               'us-etf-flows.json')
snapshot('/api/us/insider-trading',         'us-insider-trading.json')
snapshot('/api/us/options-flow',            'us-options-flow.json')
snapshot('/api/us/news-analysis',           'us-news-analysis.json')
snapshot('/api/us/super-performance',       'us-super-performance.json')
snapshot('/api/us/track-record',            'us-track-record.json')
snapshot('/api/us/calendar',                'us-calendar.json')

# ── Crypto ──
print('  ── Crypto ──')
snapshot('/api/crypto/overview',        'crypto-overview.json')
snapshot('/api/crypto/dominance',       'crypto-dominance.json')
snapshot('/api/crypto/market-gate',     'crypto-market-gate.json')
snapshot('/api/crypto/briefing',        'crypto-briefing.json')
snapshot('/api/crypto/prediction',      'crypto-prediction.json')
snapshot('/api/crypto/risk',            'crypto-risk.json')
snapshot('/api/crypto/lead-lag',        'crypto-lead-lag.json')
snapshot('/api/crypto/vcp-signals',     'crypto-vcp-signals.json')
snapshot('/api/crypto/backtest-summary','crypto-backtest-summary.json')

# ── Economy ──
print('  ── Economy ──')
snapshot('/api/econ/overview',          'econ-overview.json')
snapshot('/api/econ/yield-curve',       'econ-yield-curve.json')
snapshot('/api/econ/fear-greed',        'econ-fear-greed.json')

# ── Health/Meta ──
meta = {
    'status': 'ok',
    'mode': 'static-snapshot',
    'updated_at': datetime.now().isoformat(),
    'endpoints_ok': ok_count,
    'endpoints_fail': fail_count
}
path = os.path.join(SNAPSHOT_DIR, 'health.json')
with open(path, 'w', encoding='utf-8') as f:
    json.dump(meta, f, indent=2)

print()
print(f'  Total: {ok_count} OK, {fail_count} failed, {ok_count + fail_count} endpoints')
"

SNAPSHOT_RESULT=$?
if [ $SNAPSHOT_RESULT -ne 0 ]; then
    echo "[ERROR] 스냅샷 추출 실패!"
    exit 1
fi

echo ""

# ── 2. Vercel CLI 직접 배포 ──
echo "[2/3] Vercel 프로덕션 배포 중..."
cd "$FRONTEND"

# --prod: 프로덕션 배포
# --yes: 프롬프트 없이 자동 승인
npx vercel deploy --prod --yes 2>&1 | tee /tmp/vercel-deploy.log

DEPLOY_RESULT=$?
if [ $DEPLOY_RESULT -ne 0 ]; then
    echo ""
    echo "[ERROR] Vercel 배포 실패!"
    echo "로그: /tmp/vercel-deploy.log"
    exit 1
fi

echo ""

# ── 3. 결과 확인 ──
echo "[3/3] 배포 완료!"
echo ""
echo "╔══════════════════════════════════════════════╗"
echo "║  스냅샷: $(ls "$SNAPSHOT_DIR"/*.json 2>/dev/null | wc -l) 파일"
echo "║  Vercel: https://closing-bet.vercel.app      ║"
echo "║  로컬:   http://localhost:4001/dashboard      ║"
echo "╚══════════════════════════════════════════════╝"
echo ""
