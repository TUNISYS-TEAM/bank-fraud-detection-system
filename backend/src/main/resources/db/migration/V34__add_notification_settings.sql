-- Insert notification master control setting
INSERT INTO system_settings (key, value, description, updated_at) 
VALUES ('email_notifications_enabled', 'true', 'Enable/disable all email notifications (OTP always active)', NOW())
ON CONFLICT (key) DO UPDATE SET 
    value = EXCLUDED.value,
    description = EXCLUDED.description,
    updated_at = EXCLUDED.updated_at;

-- Insert individual notification settings
INSERT INTO system_settings (key, value, description, updated_at) 
VALUES 
    ('critical_alerts', 'true', 'Send email alerts for critical fraud alerts', NOW()),
    ('model_drift', 'true', 'Send email alerts when model drift is detected', NOW()),
    ('version_updates', 'false', 'Send email alerts for system version updates', NOW())
ON CONFLICT (key) DO UPDATE SET 
    value = EXCLUDED.value,
    description = EXCLUDED.description,
    updated_at = EXCLUDED.updated_at;