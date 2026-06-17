ALTER TABLE IF EXISTS users
    ADD COLUMN IF NOT EXISTS subscription_requested_at TIMESTAMP;
