#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
MarketFlow 대시보드 동기화 모듈
data/ → frontend/data-snapshot/ 동기화 + Vercel 자동 배포

사용법:
  python sync_dashboard.py              # 전체 동기화 + 배포
  python sync_dashboard.py --kr         # KR 마켓만
  python sync_dashboard.py --us         # US 마켓만
  python sync_dashboard.py --crypto     # Crypto만
  python sync_dashboard.py --dry-run    # 동기화만 (배포 안 함)
  python sync_dashboard.py --no-deploy  # 동기화만 (배포 안 함)
"""

import os
import sys
import json
import glob
import shutil
import subprocess
import argparse
import math
from datetime import datetime, date
from typing import Optional

# ── 경로 설정 ──
SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
BASE_DIR = os.environ.get('KR_MARKET_DIR', SCRIPT_DIR)
DATA_DIR = os.path.join(BASE_DIR, 'data')
FRONTEND_DIR = os.path.join(BASE_DIR, 'frontend')
SNAPSHOT_DIR = os.path.join(FRONTEND_DIR, 'data-snapshot')


def log(msg: str):
    ts = datetime.now().strftime('%H:%M:%S')
    print(f"  [{ts}] {msg}")


def safe_float(val):
    """NaN/Inf를 None으로 변환"""
    if val is None:
        return None
    try:
        import numpy as np
        if isinstance(val, (float, np.floating)):
            if math.isnan(val) or math.isinf(val):
                return None
    except ImportError:
        if isinstance(val, float):
            if math.isnan(val) or math.isinf(val):
                return None
    return val


def clean_nans_and_numpy(obj):
    if isinstance(obj, dict):
        return {k: clean_nans_and_numpy(v) for k, v in obj.items()}
    elif isinstance(obj, list) or isinstance(obj, tuple):
        return [clean_nans_and_numpy(v) for v in obj]
    elif isinstance(obj, float):
        import math
        if math.isnan(obj) or math.isinf(obj):
            return None
        return obj
    else:
        try:
            import numpy as np
            if isinstance(obj, np.integer):
                return int(obj)
            elif isinstance(obj, np.floating):
                import math
                if math.isnan(obj) or math.isinf(obj):
                    return None
                return float(obj)
            elif isinstance(obj, np.ndarray):
                return clean_nans_and_numpy(obj.tolist())
        except ImportError:
            pass
        return obj

def safe_json_dump(data: dict, filepath: str):
    """JSON 파일을 안전하게 저장 (NaN 제거)"""
    cleaned = clean_nans_and_numpy(data)
    with open(filepath, 'w', encoding='utf-8') as f:
        json.dump(cleaned, f, ensure_ascii=False, indent=2)

def safe_copy_json(src: str, dst: str):
    if not src.endswith('.json'):
        shutil.copy2(src, dst)
        return
    try:
        with open(src, 'r', encoding='utf-8') as f:
            data = json.load(f)
        safe_json_dump(data, dst)
    except Exception as e:
        shutil.copy2(src, dst)


# ============================================================
# KR Market 스냅샷 생성
# ============================================================

def snapshot_kr_signals() -> bool:
    """kr_ai_analysis.json → kr-signals.json"""
    try:
        src = os.path.join(DATA_DIR, 'kr_ai_analysis.json')
        if not os.path.exists(src):
            log("⚠ kr_ai_analysis.json 없음 — signals 스킵")
            return False

        with open(src, 'r', encoding='utf-8') as f:
            data = json.load(f)

        signals = data.get('signals', [])

        # 종목명 매핑
        name_map, market_map = {}, {}
        map_csv = os.path.join(DATA_DIR, 'ticker_to_yahoo_map.csv')
        if not os.path.exists(map_csv):
            map_csv = os.path.join(BASE_DIR, 'ticker_to_yahoo_map.csv')
        if os.path.exists(map_csv):
            try:
                import pandas as pd
                df = pd.read_csv(map_csv, dtype={'ticker': str})
                df['ticker'] = df['ticker'].str.zfill(6)
                if 'name' in df.columns:
                    name_map = dict(zip(df['ticker'], df['name']))
                if 'market' in df.columns:
                    market_map = dict(zip(df['ticker'], df['market']))
            except Exception:
                pass

        for s in signals:
            t = str(s.get('ticker', '')).zfill(6)
            if not s.get('name') or s.get('name') == t:
                s['name'] = name_map.get(t, t)
            if not s.get('market'):
                s['market'] = market_map.get(t, '')

        # 점수순 정렬 + 중복 제거
        signals.sort(key=lambda x: x.get('score', 0), reverse=True)
        seen = set()
        unique = []
        for s in signals:
            t = str(s.get('ticker', '')).zfill(6)
            if t not in seen:
                seen.add(t)
                unique.append(s)

        result = {
            'signals': unique,
            'count': len(unique),
            'generated_at': data.get('generated_at', ''),
            'source': 'json_live'
        }
        dst = os.path.join(SNAPSHOT_DIR, 'kr-signals.json')
        safe_json_dump(result, dst)
        log(f"✓ kr-signals.json ({len(unique)}개 시그널)")
        return True
    except Exception as e:
        log(f"✗ kr-signals.json 실패: {e}")
        return False


def snapshot_kr_market_gate() -> bool:
    """market_gate.py 실행 → kr-market-gate.json"""
    try:
        sys.path.insert(0, BASE_DIR)
        from market_gate import run_kr_market_gate

        res = run_kr_market_gate()

        sectors_data = []
        for s in res.sectors:
            change = safe_float(s.change_1d)
            sectors_data.append({
                'name': s.name,
                'signal': s.signal.lower(),
                'change_pct': round(change, 2) if change is not None else 0,
                'score': s.score
            })

        label = "NEUTRAL"
        if res.gate == "GREEN":
            label = "BULLISH"
        elif res.gate == "RED":
            label = "BEARISH"

        safe_metrics = {}
        for k, v in res.metrics.items():
            safe_metrics[k] = safe_float(v)

        result = {
            'status': res.gate,
            'score': res.score,
            'label': label,
            'reasons': res.reasons,
            'sectors': sectors_data,
            'metrics': safe_metrics,
            'kospi_close': safe_metrics.get('kospi', 0),
            'kospi_change_pct': safe_metrics.get('kospi_change_pct', 0),
            'kosdaq_close': safe_metrics.get('kosdaq', 0),
            'kosdaq_change_pct': safe_metrics.get('kosdaq_change_pct', 0),
            'updated_at': datetime.now().isoformat()
        }

        dst = os.path.join(SNAPSHOT_DIR, 'kr-market-gate.json')
        safe_json_dump(result, dst)
        log(f"✓ kr-market-gate.json (score={res.score}, {label})")
        return True
    except Exception as e:
        log(f"✗ kr-market-gate.json 실패: {e}")
        # Fallback: 기존 파일이 있으면 유지
        return False


def snapshot_kr_backtest_summary() -> bool:
    """signals_log.csv + jongga_v2_results_*.json → kr-backtest-summary.json"""
    try:
        import pandas as pd

        summary = {
            'vcp': {'status': 'No Data', 'win_rate': 0, 'avg_return': 0, 'count': 0},
            'closing_bet': {'status': 'No Data', 'win_rate': 0, 'avg_return': 0, 'count': 0}
        }

        # 1. VCP Backtest
        vcp_candidates = [
            os.path.join(DATA_DIR, 'backtest', 'final_backtest_results.csv'),
            os.path.join(DATA_DIR, 'final_backtest_results.csv'),
            os.path.join(BASE_DIR, 'final_backtest_results.csv'),
        ]
        csv_path = None
        for p in vcp_candidates:
            if os.path.exists(p):
                csv_path = p
                break

        if csv_path:
            df = pd.read_csv(csv_path)
            if not df.empty:
                is_win_col = 'is_winner' if 'is_winner' in df.columns else 'is_win'
                return_col = next((c for c in ['net_return', 'return_pct', 'return'] if c in df.columns), None)

                total = len(df)
                wins = 0

                if is_win_col in df.columns:
                    first_val = df[is_win_col].iloc[0]
                    if df[is_win_col].dtype == object or isinstance(first_val, str):
                        wins = len(df[df[is_win_col].astype(str).str.lower() == 'true'])
                    else:
                        wins = int(df[is_win_col].sum())
                elif return_col:
                    wins = len(df[df[return_col] > 0])

                avg_ret = df[return_col].mean() if return_col else 0
                win_rate = (wins / total) * 100 if total > 0 else 0

                summary['vcp'] = {
                    'status': 'OK',
                    'count': int(total),
                    'win_rate': round(win_rate, 1),
                    'avg_return': round(float(avg_ret), 2)
                }

        # 2. Closing Bet (Jongga V2) Backtest
        history_files = glob.glob(os.path.join(DATA_DIR, 'jongga_v2_results_*.json'))

        if len(history_files) < 2:
            summary['closing_bet'] = {
                'status': 'Accumulating',
                'message': f'{len(history_files)}일 데이터 (최소 2일 필요)',
                'count': 0, 'win_rate': 0, 'avg_return': 0
            }
        else:
            all_signals = []
            today = datetime.now().strftime('%Y%m%d')

            for fp in sorted(history_files):
                if today in fp:
                    continue
                try:
                    with open(fp, 'r', encoding='utf-8') as f:
                        data = json.load(f)
                    for signal in data.get('signals', []):
                        signal['file_date'] = data.get('date', '')
                        all_signals.append(signal)
                except Exception:
                    continue

            if all_signals:
                wins = 0
                total_return = 0
                valid_count = 0

                for signal in all_signals:
                    entry_price = signal.get('entry_price', 0)
                    if entry_price <= 0:
                        continue

                    change_pct = signal.get('change_pct', 0)
                    if change_pct > 0:
                        est_return = min(change_pct, 5.0)
                        wins += 1
                    else:
                        est_return = max(change_pct, -3.0)

                    total_return += est_return
                    valid_count += 1

                if valid_count > 0:
                    summary['closing_bet'] = {
                        'status': 'OK',
                        'count': valid_count,
                        'win_rate': round((wins / valid_count) * 100, 1),
                        'avg_return': round(total_return / valid_count, 2)
                    }

        dst = os.path.join(SNAPSHOT_DIR, 'kr-backtest-summary.json')
        safe_json_dump(summary, dst)
        log(f"✓ kr-backtest-summary.json (VCP:{summary['vcp']['status']}, CB:{summary['closing_bet']['status']})")
        return True
    except Exception as e:
        log(f"✗ kr-backtest-summary.json 실패: {e}")
        return False


def snapshot_kr_simple_copies() -> int:
    """단순 파일 복사 (변환 불필요한 KR 데이터)"""
    copies = [
        ('kr_ai_analysis.json',      'kr-ai-analysis.json'),
        ('jongga_v2_latest.json',    'kr-jongga-v2-latest.json'),
    ]
    ok = 0
    for src_name, dst_name in copies:
        src = os.path.join(DATA_DIR, src_name)
        dst = os.path.join(SNAPSHOT_DIR, dst_name)
        if os.path.exists(src):
            safe_copy_json(src, dst)
            size = os.path.getsize(dst)
            log(f"✓ {dst_name:45s} {size:>10,} bytes")
            ok += 1
        else:
            log(f"⚠ {src_name} 없음 — 스킵")
    return ok


def snapshot_kr_jongga_dates() -> bool:
    """jongga_v2_results_*.json → kr-jongga-v2-dates.json"""
    try:
        files = glob.glob(os.path.join(DATA_DIR, 'jongga_v2_results_*.json'))
        dates = []
        for fp in sorted(files, reverse=True):
            basename = os.path.basename(fp)
            # jongga_v2_results_20260224.json → 20260224
            date_part = basename.replace('jongga_v2_results_', '').replace('.json', '')
            if len(date_part) == 8 and date_part.isdigit():
                # 빈 파일 제외
                try:
                    with open(fp, 'r', encoding='utf-8') as f:
                        data = json.load(f)
                    if data.get('signals'):
                        dates.append(date_part)
                except Exception:
                    continue

        result = {'dates': dates[:30], 'count': len(dates)}
        dst = os.path.join(SNAPSHOT_DIR, 'kr-jongga-v2-dates.json')
        safe_json_dump(result, dst)
        log(f"✓ kr-jongga-v2-dates.json ({len(dates)}개 날짜)")
        return True
    except Exception as e:
        log(f"✗ kr-jongga-v2-dates.json 실패: {e}")
        return False


def snapshot_kr_vcp_stats() -> bool:
    """signals_log.csv → kr-vcp-stats.json"""
    try:
        import pandas as pd
        csv_path = os.path.join(DATA_DIR, 'signals_log.csv')
        if not os.path.exists(csv_path):
            result = {'total_signals': 0, 'open_signals': 0, 'avg_score': 0}
        else:
            df = pd.read_csv(csv_path, encoding='utf-8-sig')
            total = len(df)
            open_count = len(df[df['status'] == 'OPEN']) if 'status' in df.columns else 0
            avg_score = float(df['score'].mean()) if 'score' in df.columns and not df.empty else 0

            result = {
                'total_signals': total,
                'open_signals': open_count,
                'avg_score': round(avg_score, 1)
            }

        dst = os.path.join(SNAPSHOT_DIR, 'kr-vcp-stats.json')
        safe_json_dump(result, dst)
        log(f"✓ kr-vcp-stats.json")
        return True
    except Exception as e:
        log(f"✗ kr-vcp-stats.json 실패: {e}")
        return False


def snapshot_kr_vcp_history() -> bool:
    """signals_log.csv → kr-vcp-history.json (최근 90일)"""
    try:
        import pandas as pd
        csv_path = os.path.join(DATA_DIR, 'signals_log.csv')
        if not os.path.exists(csv_path):
            safe_json_dump({'history': [], 'count': 0}, os.path.join(SNAPSHOT_DIR, 'kr-vcp-history.json'))
            return True

        df = pd.read_csv(csv_path, encoding='utf-8-sig', dtype={'ticker': str})
        df['ticker'] = df['ticker'].str.zfill(6)

        # 종목명 매핑
        name_map = {}
        map_csv = os.path.join(DATA_DIR, 'ticker_to_yahoo_map.csv')
        if not os.path.exists(map_csv):
            map_csv = os.path.join(BASE_DIR, 'ticker_to_yahoo_map.csv')
        if os.path.exists(map_csv):
            try:
                mdf = pd.read_csv(map_csv, dtype={'ticker': str})
                mdf['ticker'] = mdf['ticker'].str.zfill(6)
                if 'name' in mdf.columns:
                    name_map = dict(zip(mdf['ticker'], mdf['name']))
            except Exception:
                pass

        records = []
        for _, row in df.iterrows():
            t = str(row.get('ticker', '')).zfill(6)
            records.append({
                'ticker': t,
                'name': row.get('name', '') or name_map.get(t, t),
                'signal_date': str(row.get('signal_date', '')),
                'entry_price': float(row.get('entry_price', 0)),
                'score': float(row.get('score', 0)),
                'foreign_5d': int(row.get('foreign_5d', 0)),
                'inst_5d': int(row.get('inst_5d', 0)),
                'contraction_ratio': float(row.get('contraction_ratio', 0)),
                'status': str(row.get('status', '')),
                'return_pct': float(row.get('return_pct', 0)) if 'return_pct' in row.index else 0,
            })

        result = {'history': records, 'count': len(records)}
        dst = os.path.join(SNAPSHOT_DIR, 'kr-vcp-history.json')
        safe_json_dump(result, dst)
        log(f"✓ kr-vcp-history.json ({len(records)}개 레코드)")
        return True
    except Exception as e:
        log(f"✗ kr-vcp-history.json 실패: {e}")
        return False


# ============================================================
# US / Crypto / Economy 스냅샷
# ============================================================

def _fetch_flask_endpoint(endpoint: str, dst_name: str, port: int = 5001) -> bool:
    """Flask API 엔드포인트에서 변환된 데이터를 가져와 스냅샷으로 저장"""
    try:
        import requests
        resp = requests.get(f'http://localhost:{port}{endpoint}', timeout=15)
        if resp.status_code == 200:
            data = resp.json()
            dst = os.path.join(SNAPSHOT_DIR, dst_name)
            safe_json_dump(data, dst)
            size = os.path.getsize(dst)
            log(f"✓ {dst_name:45s} {size:>10,} bytes (Flask)")
            return True
        else:
            log(f"✗ {dst_name:45s} HTTP {resp.status_code}")
            return False
    except Exception as e:
        log(f"✗ {dst_name:45s} {str(e)[:50]}")
        return False


def snapshot_us_copies() -> int:
    """US Market 스냅샷 — Flask 변환 필요 엔드포인트 + 단순 파일 복사"""
    us_output = os.path.join(BASE_DIR, 'us_market_preview', 'output')
    ok = 0

    # ── Flask API 경유 (데이터 변환 필요한 엔드포인트) ──
    flask_endpoints = [
        ('/api/us/market-briefing',   'us-market-briefing.json'),
        ('/api/us/heatmap-data',      'us-heatmap-data.json'),
        ('/api/us/earnings-impact',   'us-earnings-impact.json'),
    ]
    for endpoint, dst_name in flask_endpoints:
        if _fetch_flask_endpoint(endpoint, dst_name):
            ok += 1

    # ── 단순 파일 복사 (변환 불필요) ──
    copies = [
        (os.path.join(us_output, 'market_gate.json'),          'us-market-gate.json'),
        (os.path.join(us_output, 'smart_money_picks.json'),    'us-smart-money.json'),
        (os.path.join(us_output, 'decision_signal.json'),      'us-decision-signal.json'),
        (os.path.join(us_output, 'macro_analysis.json'),       'us-macro-analysis.json'),
        (os.path.join(us_output, 'market_regime.json'),        'us-market-regime.json'),
        (os.path.join(us_output, 'index_prediction.json'),     'us-index-prediction.json'),
        (os.path.join(us_output, 'risk_alerts.json'),          'us-risk-alerts.json'),
        (os.path.join(us_output, 'sector_rotation.json'),      'us-sector-rotation.json'),
        (os.path.join(us_output, 'backtest.json'),             'us-backtest.json'),
        (os.path.join(us_output, 'top_picks.json'),            'us-top-picks-report.json'),
        (os.path.join(us_output, 'cumulative_performance.json'), 'us-cumulative-performance.json'),
        (os.path.join(us_output, 'earnings_analysis.json'),    'us-earnings-analysis.json'),
        (os.path.join(us_output, 'etf_flow_analysis.json'),    'us-etf-flow-analysis.json'),
        (os.path.join(us_output, 'portfolio.json'),            'us-portfolio.json'),
    ]
    for src, dst_name in copies:
        dst = os.path.join(SNAPSHOT_DIR, dst_name)
        if os.path.exists(src):
            safe_copy_json(src, dst)
            size = os.path.getsize(dst)
            log(f"✓ {dst_name:45s} {size:>10,} bytes")
            ok += 1
    return ok


def snapshot_crypto_copies() -> int:
    """Crypto 관련 정적 파일 복사"""
    crypto_output = os.path.join(BASE_DIR, 'crypto-analytics', 'output')
    copies = [
        ('crypto_overview.json',     'crypto-overview.json'),
        ('crypto_dominance.json',    'crypto-dominance.json'),
        ('market_gate.json',         'crypto-market-gate.json'),
        ('crypto_briefing.json',     'crypto-briefing.json'),
        ('crypto_prediction.json',   'crypto-prediction.json'),
        ('crypto_risk.json',         'crypto-risk.json'),
        ('lead_lag_results.json',    'crypto-lead-lag.json'),
        ('vcp_signals.json',         'crypto-vcp-signals.json'),
        ('backtest_summary.json',    'crypto-backtest-summary.json'),
    ]
    ok = 0
    for src_name, dst_name in copies:
        src = os.path.join(crypto_output, src_name)
        dst = os.path.join(SNAPSHOT_DIR, dst_name)
        if os.path.exists(src):
            safe_copy_json(src, dst)
            size = os.path.getsize(dst)
            log(f"✓ {dst_name:45s} {size:>10,} bytes")
            ok += 1
    return ok


def snapshot_econ_copies() -> int:
    """Economy 관련 정적 파일 복사"""
    econ_output = os.path.join(BASE_DIR, 'econ_indicators', 'data')
    copies = [
        ('econ_overview.json',    'econ-overview.json'),
        ('yield_curve.json',      'econ-yield-curve.json'),
        ('fear_greed.json',       'econ-fear-greed.json'),
    ]
    ok = 0
    for src_name, dst_name in copies:
        src = os.path.join(econ_output, src_name)
        dst = os.path.join(SNAPSHOT_DIR, dst_name)
        if os.path.exists(src):
            safe_copy_json(src, dst)
            size = os.path.getsize(dst)
            log(f"✓ {dst_name:45s} {size:>10,} bytes")
            ok += 1
    return ok


# ============================================================
# Health 메타 생성
# ============================================================

def snapshot_health(ok_count: int, fail_count: int):
    """health.json 메타 파일 생성"""
    meta = {
        'status': 'ok',
        'mode': 'static-snapshot',
        'updated_at': datetime.now().isoformat(),
        'endpoints_ok': ok_count,
        'endpoints_fail': fail_count
    }
    dst = os.path.join(SNAPSHOT_DIR, 'health.json')
    safe_json_dump(meta, dst)


def snapshot_data_version():
    """data-version.json 생성 — 스냅샷 파일들의 수정 시간 기록"""
    import time
    versions = {}
    watch_files = [
        'daily_prices.csv', 'daily_report.json', 'jongga_v2_latest.json',
        'kr_ai_analysis.json', 'signals_log.csv'
    ]
    for fname in watch_files:
        fpath = os.path.join(DATA_DIR, fname)
        if os.path.exists(fpath):
            versions[fname] = os.path.getmtime(fpath)
        else:
            versions[fname] = 0
    
    meta = {
        'timestamp': time.time(),
        'versions': versions
    }
    dst = os.path.join(SNAPSHOT_DIR, 'data-version.json')
    safe_json_dump(meta, dst)
    log(f"  📋 data-version.json 생성")


# ============================================================
# Vercel 배포
# ============================================================

def deploy_vercel() -> bool:
    """Git push를 통한 Vercel 자동 배포 (GitHub 연동)"""
    log("🚀 Git commit + push → Vercel 자동 배포...")
    try:
        # 1. data-snapshot 파일 스테이징
        result = subprocess.run(
            ['git', 'add', 'frontend/data-snapshot/'],
            cwd=BASE_DIR,
            capture_output=True, text=True, timeout=30
        )
        if result.returncode != 0:
            log(f"❌ git add 실패: {result.stderr[:150]}")
            return False

        # 2. 변경사항 확인
        result = subprocess.run(
            ['git', 'diff', '--cached', '--stat', '--', 'frontend/data-snapshot/'],
            cwd=BASE_DIR,
            capture_output=True, text=True, timeout=10
        )
        if not result.stdout.strip():
            log("ℹ️ 변경사항 없음 — 배포 스킵")
            return True

        # 3. 커밋
        now = datetime.now().strftime('%Y-%m-%d %H:%M')
        msg = f"📊 Dashboard data sync ({now})"
        result = subprocess.run(
            ['git', 'commit', '-m', msg, '--', 'frontend/data-snapshot/'],
            cwd=BASE_DIR,
            capture_output=True, text=True, timeout=30
        )
        if result.returncode != 0:
            log(f"❌ git commit 실패: {result.stderr[:150]}")
            return False

        # 4. Push → Vercel 자동 배포 트리거
        result = subprocess.run(
            ['git', 'push', 'origin', 'main'],
            cwd=BASE_DIR,
            capture_output=True, text=True, timeout=120
        )
        if result.returncode != 0:
            # main이 아닌 다른 브랜치일 수 있음
            result = subprocess.run(
                ['git', 'push', 'origin', 'HEAD'],
                cwd=BASE_DIR,
                capture_output=True, text=True, timeout=120
            )

        if result.returncode == 0:
            log("✅ Git push 완료 → Vercel 자동 배포 트리거됨")
            return True
        else:
            log(f"❌ git push 실패: {result.stderr[:200]}")
            return False

    except subprocess.TimeoutExpired:
        log("❌ Git 작업 타임아웃")
        return False
    except Exception as e:
        log(f"❌ 배포 에러: {e}")
        return False


# ============================================================
# 메인 동기화 함수
# ============================================================

def sync_dashboard(
    scope: str = 'all',
    deploy: bool = True
) -> dict:
    """
    데이터를 frontend/data-snapshot/ 으로 동기화하고 Vercel에 배포

    Args:
        scope: 'all', 'kr', 'us', 'crypto', 'econ'
        deploy: True면 Vercel 배포까지 실행

    Returns:
        {'ok': int, 'fail': int, 'deployed': bool}
    """
    os.makedirs(SNAPSHOT_DIR, exist_ok=True)

    print("")
    print("╔══════════════════════════════════════════════╗")
    print("║  MarketFlow 대시보드 동기화                    ║")
    print("╚══════════════════════════════════════════════╝")
    print(f"  Scope: {scope}")
    print(f"  Deploy: {'Yes' if deploy else 'No (dry-run)'}")
    print(f"  Time: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}")
    print("")

    ok_count = 0
    fail_count = 0

    # ── KR Market ──
    if scope in ('all', 'kr'):
        print("  ── KR Market ──")
        # 변환이 필요한 파일들
        if snapshot_kr_signals():
            ok_count += 1
        else:
            fail_count += 1

        if snapshot_kr_market_gate():
            ok_count += 1
        else:
            fail_count += 1

        if snapshot_kr_backtest_summary():
            ok_count += 1
        else:
            fail_count += 1

        if snapshot_kr_vcp_stats():
            ok_count += 1
        else:
            fail_count += 1

        if snapshot_kr_vcp_history():
            ok_count += 1
        else:
            fail_count += 1

        if snapshot_kr_jongga_dates():
            ok_count += 1
        else:
            fail_count += 1

        # 단순 복사
        ok_count += snapshot_kr_simple_copies()

    # ── US Market ──
    if scope in ('all', 'us'):
        print("  ── US Market ──")
        ok_count += snapshot_us_copies()

    # ── Crypto ──
    if scope in ('all', 'crypto'):
        print("  ── Crypto ──")
        ok_count += snapshot_crypto_copies()

    # ── Economy ──
    if scope in ('all', 'econ'):
        print("  ── Economy ──")
        ok_count += snapshot_econ_copies()

    # ── Health & Data Version ──
    snapshot_health(ok_count, fail_count)
    snapshot_data_version()

    print("")
    print(f"  Total: {ok_count} OK, {fail_count} failed")

    # ── Vercel 배포 ──
    deployed = False
    if deploy and ok_count > 0:
        deployed = deploy_vercel()

    print("")
    print("╔══════════════════════════════════════════════╗")
    print(f"║  스냅샷: {ok_count} 파일 동기화 완료")
    print(f"║  Vercel: {'✅ 배포됨' if deployed else '⏭ 스킵'}")
    print(f"║  URL:    https://closing-bet.vercel.app")
    print("╚══════════════════════════════════════════════╝")
    print("")

    return {'ok': ok_count, 'fail': fail_count, 'deployed': deployed}


# ============================================================
# CLI
# ============================================================

def main():
    parser = argparse.ArgumentParser(description='MarketFlow 대시보드 동기화')
    parser.add_argument('--kr', action='store_true', help='KR 마켓만')
    parser.add_argument('--us', action='store_true', help='US 마켓만')
    parser.add_argument('--crypto', action='store_true', help='Crypto만')
    parser.add_argument('--econ', action='store_true', help='Economy만')
    parser.add_argument('--dry-run', '--no-deploy', action='store_true', help='배포 없이 동기화만')
    args = parser.parse_args()

    scope = 'all'
    if args.kr:
        scope = 'kr'
    elif args.us:
        scope = 'us'
    elif args.crypto:
        scope = 'crypto'
    elif args.econ:
        scope = 'econ'

    result = sync_dashboard(scope=scope, deploy=not args.dry_run)

    sys.exit(0 if result['fail'] == 0 else 1)


if __name__ == '__main__':
    main()
