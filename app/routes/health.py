"""Health check endpoint for monitoring."""

from flask import Blueprint, jsonify
from sqlalchemy import text
import os
from datetime import datetime

health_bp = Blueprint('health', __name__)

@health_bp.route('/api/health', methods=['GET'])
def health_check():
    """Health check endpoint for monitoring."""
    status = {
        'status': 'healthy',
        'timestamp': datetime.utcnow().isoformat(),
        'version': os.getenv('APP_VERSION', '1.0.0'),
        'environment': os.getenv('ENVIRONMENT', 'development'),
    }

    # Check database
    try:
        from app.models import db
        db.session.execute(text('SELECT 1'))
        status['database'] = 'ok'
    except Exception as e:
        status['database'] = f'error: {str(e)}'
        status['status'] = 'degraded'

    # Check data files
    data_dir = os.path.join(os.path.dirname(__file__), '..', 'data')
    status['data_directory'] = os.path.exists(data_dir)

    http_code = 200 if status['status'] == 'healthy' else 503
    return jsonify(status), http_code