BEGIN;

INSERT INTO system_settings (key, value, description, updated_at)
VALUES
  -- Audit / sécurité
  ('audit_log_enabled',      'true', 'Enable or disable audit log recording', NOW()),
  ('session_timeout',        '30',   'Session timeout in minutes', NOW()),

  -- Archivage + rétention
  ('archive_enabled',        'true', 'Enable or disable archiving of logs and sessions before purge', NOW()),
  ('retention_days',         '90',   'Retention in days for audit_log and user_session in PostgreSQL', NOW())

ON CONFLICT (key) DO NOTHING;

COMMIT;