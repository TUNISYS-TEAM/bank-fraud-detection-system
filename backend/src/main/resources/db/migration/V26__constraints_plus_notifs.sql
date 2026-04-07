ALTER TABLE app_user DROP CONSTRAINT IF EXISTS uk_app_user_phone_number;
ALTER TABLE app_user ADD CONSTRAINT uk_app_user_phone_number UNIQUE (phone_number);

/*ALTER TABLE fraud_alert DROP CONSTRAINT fraud_alert_alert_type_check;*/

ALTER TABLE notification ADD COLUMN IF NOT EXISTS is_read BOOLEAN DEFAULT FALSE;
ALTER TABLE notification ADD COLUMN IF NOT EXISTS read_at TIMESTAMP;