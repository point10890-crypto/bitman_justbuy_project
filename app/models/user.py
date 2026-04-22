"""User model + SubscriptionRequest model"""

from datetime import datetime, timezone
import bcrypt
from app.models import db


class User(db.Model):
    __tablename__ = 'users'

    id = db.Column(db.Integer, primary_key=True)
    email = db.Column(db.String(255), unique=True, nullable=False, index=True)
    password_hash = db.Column(db.String(255), nullable=False)
    name = db.Column(db.String(100), nullable=False)

    # Role: 'user' | 'admin'
    role = db.Column(db.String(20), default='user', nullable=False)
    # Tier: 'free' | 'pro' | 'premium'
    tier = db.Column(db.String(20), default='free')
    # Subscription status: 'pending' | 'approved' | 'rejected' | 'suspended'
    status = db.Column(db.String(20), default='pending', nullable=False)

    stripe_customer_id = db.Column(db.String(255), nullable=True)
    depositor_name = db.Column(db.String(100), nullable=True)  # 입금자명 (구독 신청 시)
    subscription_end_date = db.Column(db.String(255), nullable=True)  # ISO format: "2026-05-18"
    created_at = db.Column(db.DateTime, default=lambda: datetime.now(timezone.utc))
    approved_at = db.Column(db.DateTime, nullable=True)
    approved_by = db.Column(db.Integer, nullable=True)  # admin user id
    last_login_at = db.Column(db.DateTime, nullable=True)

    def set_password(self, password: str):
        self.password_hash = bcrypt.hashpw(
            password.encode('utf-8'), bcrypt.gensalt()
        ).decode('utf-8')

    def check_password(self, password: str) -> bool:
        return bcrypt.checkpw(
            password.encode('utf-8'),
            self.password_hash.encode('utf-8')
        )

    @property
    def is_admin(self) -> bool:
        return self.role == 'admin'

    @property
    def is_approved(self) -> bool:
        return self.status == 'approved'

    def to_dict(self):
        # subscription: tier를 기반으로 결정 (admin은 무기한 pro)
        if self.role == 'admin':
            subscription = 'PRO'
        else:
            # status='pending' → PENDING, tier='pro' → PRO, tier='free' → FREE
            if self.status == 'pending':
                subscription = 'PENDING'
            else:
                # ⭐ PRO 만료 검증: subscription_end_date가 과거이면 FREE로 취급
                if self.tier == 'pro' and self.subscription_end_date:
                    from datetime import datetime
                    try:
                        end_date = datetime.fromisoformat(self.subscription_end_date)
                        if datetime.now() > end_date:
                            # 만료됨 → FREE로 반환 (tier는 그대로 유지)
                            subscription = 'FREE'
                        else:
                            subscription = 'PRO'
                    except (ValueError, TypeError):
                        subscription = self.tier.upper() if self.tier else 'FREE'
                else:
                    subscription = self.tier.upper() if self.tier else 'FREE'

        return {
            'id': self.id,
            'email': self.email,
            'name': self.name,
            'role': self.role.upper(),  # 'user' → 'USER', 'admin' → 'ADMIN'
            'subscription': subscription,  # 프론트엔드용: 'FREE' | 'PENDING' | 'PRO'
            'subscriptionEndDate': self.subscription_end_date,  # camelCase
            'subscriptionApprovedAt': self.approved_at.isoformat() if self.approved_at else None,  # ⭐ PRO 부여일
            'depositorName': None,  # 향후 구독 신청자명 저장용
            'createdAt': self.created_at.isoformat() if self.created_at else None,
            # 내부용 (프론트엔드에서는 사용 안 함)
            '_tier': self.tier,
            '_status': self.status,
            '_stripe_customer_id': self.stripe_customer_id,
            '_last_login_at': self.last_login_at.isoformat() if self.last_login_at else None,
        }


class SubscriptionRequest(db.Model):
    """구독 변경 요청 (free→pro, pro→premium 등)"""
    __tablename__ = 'subscription_requests'

    id = db.Column(db.Integer, primary_key=True)
    user_id = db.Column(db.Integer, db.ForeignKey('users.id'), nullable=False)
    request_type = db.Column(db.String(50), nullable=False)  # 'upgrade', 'downgrade'
    from_tier = db.Column(db.String(20), nullable=False)
    to_tier = db.Column(db.String(20), nullable=False)
    status = db.Column(db.String(20), default='pending')  # 'pending', 'approved', 'rejected'
    payment_id = db.Column(db.String(255), nullable=True)
    admin_note = db.Column(db.Text, nullable=True)
    created_at = db.Column(db.DateTime, default=lambda: datetime.now(timezone.utc))
    processed_at = db.Column(db.DateTime, nullable=True)
    processed_by = db.Column(db.Integer, nullable=True)  # admin user id

    # Relationship
    user = db.relationship('User', backref=db.backref('subscription_requests', lazy=True))

    def to_dict(self):
        return {
            'id': self.id,
            'user_id': self.user_id,
            'user_email': self.user.email if self.user else None,
            'user_name': self.user.name if self.user else None,
            'request_type': self.request_type,
            'from_tier': self.from_tier,
            'to_tier': self.to_tier,
            'status': self.status,
            'payment_id': self.payment_id,
            'admin_note': self.admin_note,
            'created_at': self.created_at.isoformat() if self.created_at else None,
            'processed_at': self.processed_at.isoformat() if self.processed_at else None,
        }
