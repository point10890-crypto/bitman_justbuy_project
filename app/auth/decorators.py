"""Auth decorators for Flask routes"""

import os
import jwt
from functools import wraps
from flask import request, jsonify, current_app
from app.models import db
from app.models.user import User

# JWT 설정
JWT_SECRET = os.getenv('JWT_SECRET', 'bitman-justbuy-secret-key-change-in-production')
JWT_ALGORITHM = 'HS512'
TOKEN_EXPIRY = 86400 * 7  # 7 days


def generate_token(user_id: str, email: str, role: str) -> str:
    """Spring Boot와 동일한 JWT 토큰 생성"""
    import datetime
    payload = {
        'sub': str(user_id),
        'email': email,
        'role': role,
        'iat': datetime.datetime.utcnow(),
        'exp': datetime.datetime.utcnow() + datetime.timedelta(seconds=TOKEN_EXPIRY)
    }
    return jwt.encode(payload, JWT_SECRET, algorithm=JWT_ALGORITHM)


def validate_token(token: str):
    """JWT 토큰 검증 및 페이로드 반환"""
    try:
        payload = jwt.decode(token, JWT_SECRET, algorithms=[JWT_ALGORITHM])
        return payload
    except jwt.ExpiredSignatureError:
        return None
    except jwt.InvalidTokenError:
        return None


def _get_current_user():
    """현재 사용자 조회 (JWT 기반)"""
    auth = request.headers.get('Authorization', '')
    if not auth.startswith('Bearer '):
        return None
    token = auth[7:]
    payload = validate_token(token)
    if payload is None:
        return None
    try:
        user_id = payload['sub']
        return db.session.get(User, user_id)
    except (KeyError, ValueError):
        return None


def _auth_disabled():
    """인증 비활성화 여부 — 개발 단계에서 모든 인증/구독 제한 해제.

    활성화 조건 (OR):
      1. Flask debug 모드
      2. AUTH_DISABLED=true 환경변수
      3. DEV_MODE=true 환경변수
      4. RENDER 환경변수 존재 (Render 배포 자동 감지)

    프로덕션 전환 시:
      - Render: DEV_MODE 환경변수 제거 + 아래 RENDER 체크 라인 삭제
      - 로컬: .env에서 DEV_MODE 제거
    """
    if current_app.debug:
        return True
    if os.getenv('AUTH_DISABLED', '').lower() in ('true', '1', 'yes'):
        return True
    # DEV_MODE=true 일 때만 인증 해제 (로컬 개발 전용, 프로덕션에서 절대 설정 금지)
    if os.getenv('DEV_MODE', '').lower() in ('true', '1', 'yes'):
        return True
    return False


def login_required(f):
    """인증 필수 — 로그인한 유저만 접근 가능"""
    @wraps(f)
    def decorated(*args, **kwargs):
        if _auth_disabled():
            request.current_user = None
            return f(*args, **kwargs)
        user = _get_current_user()
        if user is None:
            return jsonify({'error': 'Authentication required'}), 401
        request.current_user = user
        return f(*args, **kwargs)
    return decorated


def approved_required(f):
    """승인된 유저 전용 — 관리자가 승인한 유저만 접근 가능"""
    @wraps(f)
    def decorated(*args, **kwargs):
        if _auth_disabled():
            request.current_user = None
            return f(*args, **kwargs)
        user = _get_current_user()
        if user is None:
            return jsonify({'error': 'Authentication required'}), 401
        if not user.is_approved and not user.is_admin:
            return jsonify({'error': 'Account not approved. Please wait for admin approval.'}), 403
        request.current_user = user
        return f(*args, **kwargs)
    return decorated


def pro_required(f):
    """Pro 구독 유저 전용 — 승인 + Pro tier"""
    @wraps(f)
    def decorated(*args, **kwargs):
        if _auth_disabled():
            request.current_user = None
            return f(*args, **kwargs)
        user = _get_current_user()
        if user is None:
            return jsonify({'error': 'Authentication required'}), 401
        if not user.is_approved and not user.is_admin:
            return jsonify({'error': 'Account not approved'}), 403
        if user.tier not in ('pro', 'premium') and not user.is_admin:
            return jsonify({'error': 'Pro subscription required'}), 403
        request.current_user = user
        return f(*args, **kwargs)
    return decorated


def admin_required(f):
    """관리자 전용 — role='admin' 유저만 접근 가능"""
    @wraps(f)
    def decorated(*args, **kwargs):
        if _auth_disabled():
            request.current_user = None
            return f(*args, **kwargs)
        user = _get_current_user()
        if user is None:
            return jsonify({'error': 'Authentication required'}), 401
        if not user.is_admin:
            return jsonify({'error': 'Admin access denied'}), 403
        request.current_user = user
        return f(*args, **kwargs)
    return decorated
