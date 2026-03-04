-- ============================================================
-- TABLE: system_settings
-- ============================================================
CREATE TABLE IF NOT EXISTS system_settings (
    key         VARCHAR(100) PRIMARY KEY,
    value       VARCHAR(255) NOT NULL,
    description TEXT,
    updated_at  TIMESTAMP NOT NULL DEFAULT NOW()
);

-- auto-update updated_at sur UPDATE
CREATE OR REPLACE FUNCTION trg_set_updated_at()
RETURNS TRIGGER AS $$
BEGIN
  NEW.updated_at := NOW();
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS set_system_settings_updated_at ON system_settings;
CREATE TRIGGER set_system_settings_updated_at
BEFORE UPDATE ON system_settings
FOR EACH ROW
EXECUTE FUNCTION trg_set_updated_at();

-- Seed: audit_log_enabled
INSERT INTO system_settings (key, value, description)
VALUES ('audit_log_enabled', 'true', 'Enable or disable audit log recording')
ON CONFLICT (key) DO NOTHING;

-- ============================================================
-- FUNCTION: is_audit_enabled()
-- ============================================================
CREATE OR REPLACE FUNCTION is_audit_enabled()
RETURNS BOOLEAN
LANGUAGE plpgsql
STABLE
AS $$
DECLARE v TEXT;
BEGIN
  SELECT value
  INTO v
  FROM system_settings
  WHERE key = 'audit_log_enabled';

  IF v IS NULL THEN
    RETURN FALSE;
  END IF;

  RETURN lower(trim(v)) IN ('true','1','yes','y','on');
END;
$$;