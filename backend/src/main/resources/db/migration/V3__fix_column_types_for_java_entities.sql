-- Migration pour aligner les types de la base de données avec les entités Java existantes

-- 1. Suppression des vues dépendantes
DROP VIEW IF EXISTS v_model_performance;
DROP VIEW IF EXISTS v_suspicious_transactions;
-- 2. Modification des types de colonnes avec conversion explicite (USING)
-- Table fraud_alert
ALTER TABLE fraud_alert
ALTER COLUMN fraud_score TYPE DOUBLE PRECISION USING fraud_score::DOUBLE PRECISION,
    ALTER COLUMN supervised_score TYPE DOUBLE PRECISION USING supervised_score::DOUBLE PRECISION,
    ALTER COLUMN anomaly_score TYPE DOUBLE PRECISION USING anomaly_score::DOUBLE PRECISION;
-- Table model_ml
ALTER TABLE model_ml
ALTER COLUMN auc TYPE DOUBLE PRECISION USING auc::DOUBLE PRECISION,
    ALTER COLUMN precision TYPE DOUBLE PRECISION USING precision::DOUBLE PRECISION,
    ALTER COLUMN recall TYPE DOUBLE PRECISION USING recall::DOUBLE PRECISION,
    ALTER COLUMN f1_score TYPE DOUBLE PRECISION USING f1_score::DOUBLE PRECISION;
-- Table rapport
ALTER TABLE rapport
ALTER COLUMN avg_fraud_score TYPE DOUBLE PRECISION USING avg_fraud_score::DOUBLE PRECISION;
-- 3. Recréation des vues
CREATE VIEW v_model_performance AS
SELECT model_name,
    version,
    model_type,
    status,
    auc,
    precision,
    recall,
    f1_score,
    created_at,
    (
        SELECT COUNT(*)
        FROM fraud_alert
        WHERE model_used_id = model_ml.id
    ) as total_alerts,
    (
        SELECT COUNT(*)
        FROM transaction
        WHERE model_used_id = model_ml.id
    ) as total_predictions
FROM model_ml
ORDER BY created_at DESC;
CREATE VIEW v_suspicious_transactions AS
SELECT t.transaction_uuid,
    t.amount,
    t.transaction_date,
    t.merchant_name,
    a.account_number,
    c.first_name || ' ' || c.last_name as client_name,
    f.fraud_score,
    f.risk_level,
    f.status as alert_status,
    m.model_name as model_used,
    cb.client_risk_score,
    cb.client_is_unusual_region,
    cb.client_is_unusual_channel
FROM transaction t
    JOIN account a ON t.account_id = a.id
    JOIN client c ON a.client_id = c.id
    LEFT JOIN fraud_alert f ON t.id = f.transaction_id
    LEFT JOIN model_ml m ON f.model_used_id = m.id
    LEFT JOIN client_behavior cb ON c.id = cb.client_id
    AND a.id = cb.account_id
WHERE f.risk_level IN ('HIGH', 'CRITICAL')
    OR t.is_fraud = true
ORDER BY t.transaction_date DESC;