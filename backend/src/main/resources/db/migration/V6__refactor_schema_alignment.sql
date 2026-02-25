-- ============================================================
-- 0️⃣ DROP DEPENDENT VIEWS
-- ============================================================

DROP VIEW IF EXISTS v_suspicious_transactions;
DROP VIEW IF EXISTS v_model_performance;
DROP VIEW IF EXISTS v_client_risk_profile;

-- ============================================================
--  2- AUDIT_LOG 
-- ============================================================

DROP INDEX IF EXISTS idx_audit_log_user_id;
DROP INDEX IF EXISTS idx_audit_log_action;

DROP TABLE IF EXISTS audit_log CASCADE;

CREATE TABLE audit_log (
    id BIGSERIAL PRIMARY KEY,
    session_id BIGINT REFERENCES user_session(id) ON DELETE CASCADE,
    action VARCHAR(255),
    action_type VARCHAR(100),
    action_timestamp TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    level VARCHAR(50)
);

CREATE INDEX idx_audit_log_session_id ON audit_log(session_id);
CREATE INDEX idx_audit_log_action_type ON audit_log(action_type);

-- ============================================================
-- 3- RAPPORT
-- ============================================================

ALTER TABLE rapport
DROP CONSTRAINT IF EXISTS rapport_user_id_fkey;

ALTER TABLE rapport
RENAME COLUMN user_id TO model_id;

ALTER TABLE rapport
ADD CONSTRAINT rapport_model_id_fkey
FOREIGN KEY (model_id) REFERENCES model_ml(id);

CREATE INDEX IF NOT EXISTS idx_rapport_model_id ON rapport(model_id);

-- ============================================================
-- 4- CREATE TABLE comment
-- ============================================================

CREATE TABLE comment (
    id BIGSERIAL PRIMARY KEY,
    object VARCHAR(255),
    content TEXT NOT NULL,
    status VARCHAR(50),
    author_id BIGINT REFERENCES app_user(id) ON DELETE SET NULL,
    model_id BIGINT REFERENCES model_ml(id) ON DELETE CASCADE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_comment_model_id ON comment(model_id);
CREATE INDEX idx_comment_author_id ON comment(author_id);

-- ============================================================
-- 5- ACCOUNT : remove opened_date
-- ============================================================

ALTER TABLE account
DROP COLUMN IF EXISTS opened_date;

-- ============================================================
-- 6-  MODEL_ML : remove unused lifecycle columns
-- ============================================================

ALTER TABLE model_ml
DROP COLUMN IF EXISTS training_date,
DROP COLUMN IF EXISTS validation_date,
DROP COLUMN IF EXISTS deployment_date,
DROP COLUMN IF EXISTS notes,
DROP COLUMN IF EXISTS model_path;

-- ============================================================
-- 7- TRANSACTION : remove description & merchant_name
--      + add model_id FK
-- ============================================================

ALTER TABLE transaction
DROP COLUMN IF EXISTS description,
DROP COLUMN IF EXISTS merchant_name;

ALTER TABLE transaction
ADD COLUMN IF NOT EXISTS model_id BIGINT;

ALTER TABLE transaction
DROP CONSTRAINT IF EXISTS transaction_model_id_fkey;

ALTER TABLE transaction
ADD CONSTRAINT transaction_model_id_fkey
FOREIGN KEY (model_id) REFERENCES model_ml(id);

CREATE INDEX IF NOT EXISTS idx_transaction_model_id
ON transaction(model_id);

-- ============================================================
-- 8-  CLIENT_BEHAVIOR : remove client_id
-- ============================================================

ALTER TABLE client_behavior
DROP CONSTRAINT IF EXISTS client_behavior_client_id_fkey;

ALTER TABLE client_behavior
DROP COLUMN IF EXISTS client_id;

-- ============================================================
-- 9-  FRAUD_ALERT : remove account_id
-- ============================================================

ALTER TABLE fraud_alert
DROP CONSTRAINT IF EXISTS fraud_alert_account_id_fkey;

ALTER TABLE fraud_alert
DROP COLUMN IF EXISTS account_id;

-- ============================================================
-- 10-  NOTIFICATION : remove unused fields
-- ============================================================

ALTER TABLE notification
DROP COLUMN IF EXISTS action_url,
DROP COLUMN IF EXISTS is_read,
DROP COLUMN IF EXISTS read_at,
DROP COLUMN IF EXISTS expires_at,
DROP COLUMN IF EXISTS metadata;

ALTER TABLE notification
DROP CONSTRAINT IF EXISTS notification_type_check;

ALTER TABLE notification
ADD CONSTRAINT notification_type_check
CHECK (
    type IN (
        'ALERT',
        'INFO',
        'WARNING',
        'SUCCESS',
        'MODEL_READY',
        'CONFIG_UPDATED'
    )
);

-- ============================================================
-- 11-  MESSAGE : remove priority
-- ============================================================

ALTER TABLE message
DROP COLUMN IF EXISTS priority;

-- ============================================================
-- 12-  PERMISSION : remove resource_type
-- ============================================================

ALTER TABLE permission
DROP COLUMN IF EXISTS resource_type;

-- ============================================================
-- 13-  DATASET : remove record_count & created_by_id
-- ============================================================

ALTER TABLE dataset
DROP CONSTRAINT IF EXISTS dataset_created_by_id_fkey;

ALTER TABLE dataset
DROP COLUMN IF EXISTS record_count,
DROP COLUMN IF EXISTS created_by_id;

-- ============================================================
-- 🔁 RECREATE VIEWS
-- ============================================================

CREATE VIEW v_model_performance AS
SELECT
    m.model_name,
    m.version,
    m.model_type,
    m.status,
    m.auc,
    m.precision,
    m.recall,
    m.f1_score,
    m.created_at,
    (
        SELECT COUNT(*)
        FROM fraud_alert fa
        WHERE fa.model_used_id = m.id
    ) AS total_alerts,
    (
        SELECT COUNT(*)
        FROM transaction t
        WHERE t.model_id = m.id
    ) AS total_predictions
FROM model_ml m
ORDER BY m.created_at DESC;


CREATE VIEW v_suspicious_transactions AS
SELECT
    t.transaction_uuid,
    t.amount,
    t.transaction_date,
    a.account_number,
    c.first_name || ' ' || c.last_name AS client_name,
    f.fraud_score,
    f.risk_level,
    f.status AS alert_status,
    m.model_name AS model_used,
    cb.client_risk_score
FROM transaction t
JOIN account a ON t.account_id = a.id
JOIN client c ON a.client_id = c.id
LEFT JOIN fraud_alert f ON t.id = f.transaction_id
LEFT JOIN model_ml m ON t.model_id = m.id
LEFT JOIN client_behavior cb ON a.id = cb.account_id
WHERE f.risk_level IN ('HIGH', 'CRITICAL')
   OR t.is_fraud = true
ORDER BY t.transaction_date DESC;