-- 1-  DROP DEPENDENT VIEWS

DROP VIEW IF EXISTS v_suspicious_transactions;
DROP VIEW IF EXISTS v_model_performance;


-- 2- Suppression de l'ancienne colonne redondante model_used_id

DROP INDEX IF EXISTS idx_transaction_model_used;

ALTER TABLE transaction
DROP COLUMN IF EXISTS model_used_id;

-- 3️-  RECREATE VIEWS

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
JOIN client c ON a.client_id =c.client_id
LEFT JOIN fraud_alert f ON t.id = f.transaction_id
LEFT JOIN model_ml m ON f.model_used_id = m.id
LEFT JOIN client_behavior cb ON a.id = cb.account_id
WHERE f.risk_level IN ('HIGH', 'CRITICAL')
   OR t.is_fraud = true
ORDER BY t.transaction_date DESC;

ALTER TABLE model_ml
DROP COLUMN IF EXISTS dataset_path,
DROP COLUMN IF EXISTS artifacts;