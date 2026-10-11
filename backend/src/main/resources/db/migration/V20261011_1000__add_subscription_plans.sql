-- Separate approved and pending plans; no backfill or changes to existing access/history.
-- Legacy pending rows with a null requested plan are approved as MONTHLY in the service.
ALTER TABLE IF EXISTS users ADD COLUMN IF NOT EXISTS subscription_plan VARCHAR(32);
ALTER TABLE IF EXISTS users ADD COLUMN IF NOT EXISTS requested_subscription_plan VARCHAR(32);
