# app/__init__.py
"""Flask 애플리케이션 팩토리 (KR Market + Auth + Stripe)"""

import logging
import os
import sys
import traceback
from flask import Flask, jsonify, make_response
from flask.json.provider import DefaultJSONProvider

logger = logging.getLogger(__name__)

# 패키지 루트 경로 추가
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))


class ApiException(Exception):
    """Spring Boot ApiException과 유사한 Flask용 예외 클래스"""

    def __init__(self, message, status_code=400):
        super().__init__(message)
        self.message = message
        self.status_code = status_code


class SafeJSONProvider(DefaultJSONProvider):
    """NaN/Infinity → null 변환 (JSON 표준 준수)"""
    def dumps(self, obj, **kwargs):
        kwargs.setdefault("default", self.default)
        raw = super().dumps(obj, **kwargs)
        return raw.replace("NaN", "null").replace("Infinity", "null").replace("-Infinity", "null")


def create_app(config=None):
    """Flask 앱 팩토리 함수"""
    app = Flask(__name__)
    app.json_provider_class = SafeJSONProvider
    app.json = SafeJSONProvider(app)

    # CORS 설정 — 허용 도메인 명시 (origins: * 금지)
    _allowed_origins = os.getenv('CORS_ORIGINS', ','.join([
        'http://localhost:3000', 'http://localhost:3001', 'http://localhost:4000',
        'http://localhost:5173', 'http://127.0.0.1:3000',
        'https://bitman-justbuy.pages.dev',
        'https://crop-mens-street-kai.trycloudflare.com',
    ])).split(',')
    try:
        from flask_cors import CORS
        CORS(app, resources={r"/api/*": {"origins": _allowed_origins}})
    except ImportError:
        logger.warning("flask-cors not installed, CORS disabled")

    # 환경변수 로드
    try:
        from dotenv import load_dotenv
        load_dotenv()
    except ImportError:
        pass

    # 기본 설정 — SECRET_KEY 환경변수 필수 (미설정 시 랜덤 생성 + 경고)
    _secret = os.getenv('SECRET_KEY')
    if not _secret:
        _secret = os.urandom(32).hex()
        logger.warning("SECRET_KEY not set! Generated random key — sessions will NOT survive restarts. "
                        "Set SECRET_KEY env var for production.")
    app.config['SECRET_KEY'] = _secret
    app.config['SQLALCHEMY_DATABASE_URI'] = 'sqlite:///' + os.path.join(
        os.path.abspath(os.path.dirname(os.path.dirname(__file__))), 'data', 'users.db'
    )
    app.config['SQLALCHEMY_TRACK_MODIFICATIONS'] = False

    # 설정 적용
    if config:
        app.config.update(config)

    # Database
    from app.models import db
    db.init_app(app)
    with app.app_context():
        from app.models.user import User  # noqa: F401
        db.create_all()

    # Blueprint 등록
    from app.routes import register_blueprints
    register_blueprints(app)

    # ── API Cache-Control 정책 ──
    @app.after_request
    def add_cache_headers(response):
        """JSON API: 기본 30초 브라우저 캐시 허용, 실시간 엔드포인트는 개별 no-cache 설정"""
        if response.content_type and 'application/json' in response.content_type:
            # 엔드포인트별 no-cache는 개별 라우트에서 설정 (portfolio, market-gate 등)
            if not response.headers.get('Cache-Control'):
                response.headers['Cache-Control'] = 'public, max-age=30'
        return response

    # Health check endpoint
    @app.route('/api/health')
    def health_check():
        import subprocess
        from flask import jsonify as _jsonify
        try:
            git_hash = subprocess.check_output(
                ['git', 'rev-parse', '--short', 'HEAD'],
                stderr=subprocess.DEVNULL, timeout=3
            ).decode().strip()
        except Exception:
            git_hash = 'unknown'
        vcp_exists = os.path.exists(os.path.join(os.path.dirname(os.path.dirname(__file__)), 'data', 'signals_log.csv'))
        return _jsonify({
            'status': 'ok',
            'service': 'MarketFlow API',
            'version': git_hash,
            'data': {'signals_log': vcp_exists},
        })

    # ── 스케줄러 상태 API ──
    @app.route('/api/scheduler/status')
    def scheduler_status():
        from flask import jsonify as _jsonify
        from app.utils.scheduler import get_scheduler_status
        return _jsonify(get_scheduler_status())

    # ── 스케줄러 수동 트리거 API (관리자 인증 필수) ──
    @app.route('/api/scheduler/trigger/<task>', methods=['POST'])
    def scheduler_trigger(task):
        from flask import jsonify as _jsonify, request as _request
        import threading
        from app.auth.decorators import _auth_disabled, _get_current_user

        # 인증 검사: 관리자만 트리거 가능
        if not _auth_disabled():
            user = _get_current_user()
            if user is None:
                return _jsonify({'error': 'Authentication required'}), 401
            if not user.is_admin:
                return _jsonify({'error': 'Admin access required'}), 403

        from app.utils.scheduler import (
            _run_jongga_v2, _run_round2, _run_us_update, _run_crypto_pipeline,
            _run_all_update
        )

        tasks_map = {
            'jongga-v2': _run_jongga_v2,
            'round2': _run_round2,
            'us-update': _run_us_update,
            'crypto': _run_crypto_pipeline,
            'all-update': _run_all_update,
        }
        func = tasks_map.get(task)
        if not func:
            return _jsonify({'error': f'Unknown task: {task}', 'available': list(tasks_map.keys())}), 400

        threading.Thread(target=func, daemon=True, name=f'trigger-{task}').start()
        return _jsonify({'status': 'triggered', 'task': task})

    # ── 데이터 freshness 확인 (GitHub Actions용) ──
    @app.route('/api/system/last-update')
    def system_last_update():
        from flask import jsonify as _jsonify
        from datetime import datetime, timezone
        base = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
        files_to_check = {
            'kr_jongga': os.path.join(base, 'data', 'jongga_v2_latest.json'),
            'us_briefing': os.path.join(base, 'us_market_preview', 'output', 'briefing.json'),
            'us_market_data': os.path.join(base, 'us_market_preview', 'output', 'market_data.json'),
            'us_top_picks': os.path.join(base, 'us_market_preview', 'output', 'top_picks.json'),
        }
        result = {}
        for key, path in files_to_check.items():
            if os.path.exists(path):
                mtime = os.path.getmtime(path)
                result[key] = {
                    'timestamp': datetime.fromtimestamp(mtime, tz=timezone.utc).isoformat(),
                    'age_seconds': int(datetime.now(timezone.utc).timestamp() - mtime),
                }
            else:
                result[key] = {'timestamp': None, 'age_seconds': -1}
        return _jsonify(result)

    # ── 라우트 등록 검증: 핵심 라우트 누락 시 즉시 중단 ──
    registered = {r.rule for r in app.url_map.iter_rules()}
    for critical in ['/api/health', '/api/data-version']:
        if critical not in registered:
            raise RuntimeError(
                f"[FATAL] Critical route not registered: {critical}\n"
                f"  Registered ({len(registered)}): {sorted(list(registered))[:15]}..."
            )

    # ── Global Error Handler — Spring Boot 스타일 표준화 ──
    @app.errorhandler(ApiException)
    def handle_api_exception(e):
        logger.warning("API exception: %s", e.message)
        return jsonify({'error': e.message}), e.status_code

    @app.errorhandler(400)
    def handle_bad_request(e):
        logger.warning("Bad request: %s", e)
        return jsonify({'error': '잘못된 요청입니다.'}), 400

    @app.errorhandler(401)
    def handle_unauthorized(e):
        logger.warning("Unauthorized access: %s", e)
        return jsonify({'error': '인증이 필요합니다.'}), 401

    @app.errorhandler(403)
    def handle_forbidden(e):
        logger.warning("Forbidden access: %s", e)
        return jsonify({'error': '접근 권한이 없습니다.'}), 403

    @app.errorhandler(404)
    def handle_not_found(e):
        logger.warning("Not found: %s", e)
        return jsonify({'error': '요청한 리소스를 찾을 수 없습니다.'}), 404

    @app.errorhandler(429)
    def handle_too_many_requests(e):
        logger.warning("Too many requests: %s", e)
        return jsonify({'error': '요청이 너무 많습니다. 잠시 후 다시 시도해 주세요.'}), 429

    @app.errorhandler(500)
    def handle_internal_error(e):
        logger.error("Internal server error: %s", e, exc_info=True)
        return jsonify({'error': '서버 오류가 발생했습니다.'}), 500

    @app.errorhandler(Exception)
    def handle_unhandled_exception(e):
        logger.error("Unhandled exception: %s", e, exc_info=True)
        return jsonify({'error': '서버 오류가 발생했습니다.'}), 500

    # ── 클라우드 스케줄러 자동 시작 (Render 또는 SCHEDULER_ENABLED) ──
    if os.getenv('RENDER') or os.getenv('SCHEDULER_ENABLED', '').lower() in ('true', '1'):
        try:
            from app.utils.scheduler import start_cloud_scheduler
            start_cloud_scheduler()
            print("[OK] Cloud scheduler started in background thread")
        except Exception as e:
            print(f"[WARN] Cloud scheduler failed to start: {e}")

    return app
