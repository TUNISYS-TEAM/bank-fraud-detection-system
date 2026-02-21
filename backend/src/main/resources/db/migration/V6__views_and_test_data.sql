-- First insert test data (so tables have data)
INSERT INTO app_user (email, password_hash, first_name, last_name, role) VALUES
('admin@fraud.com', '$2a$10$HkQqXK9QqXK9QqXK9QqXKO', 'Admin', 'System', 'ADMIN'),
('manager@fraud.com', '$2a$10$HkQqXK9QqXK9QqXK9QqXKO', 'Manager', 'User', 'MANAGER'),
('analyst1@fraud.com', '$2a$10$HkQqXK9QqXK9QqXK9QqXKO', 'Jean', 'Dupont', 'ANALYST'),
('analyst2@fraud.com', '$2a$10$HkQqXK9QqXK9QqXK9QqXKO', 'Marie', 'Martin', 'ANALYST');

INSERT INTO client (first_name, last_name, date_of_birth) VALUES
('Alice', 'Smith', '1985-04-12'),
('Bob', 'Johnson', '1990-08-23'),
('Charlie', 'Brown', '1975-12-05'),
('Diana', 'Prince', '1988-03-14');

INSERT INTO account (account_number, account_type, balance, currency, client_id, risk_level) VALUES
('TN76123450001', 'COURANT', 15000.00, 'TND', 1, 'MEDIUM'),
('TN76123450002', 'EPARGNE', 45000.00, 'TND', 1, 'LOW'),
('TN76123450003', 'COURANT', 8500.00, 'TND', 2, 'LOW'),
('TN76123450004', 'DEVISE', 25000.00, 'EUR', 2, 'MEDIUM'),
('TN76123450005', 'COURANT', 22000.00, 'TND', 3, 'HIGH'),
('TN76123450006', 'BLOQUE', 100000.00, 'TND', 4, 'LOW');

INSERT INTO role (name, description, keycloak_role_name) VALUES
('ADMIN', 'Administrator with full access', 'admin'),
('MANAGER', 'Manager with oversight capabilities', 'manager'),
('ANALYST', 'Analyst with limited access', 'analyst');

INSERT INTO permission (name, description, resource_type, action) VALUES
('VIEW_TRANSACTIONS', 'Can view transactions', 'transaction', 'view'),
('CREATE_ALERT', 'Can create fraud alerts', 'alert', 'create'),
('REVIEW_ALERT', 'Can review fraud alerts', 'alert', 'review'),
('MANAGE_MODELS', 'Can manage ML models', 'model', 'manage'),
('VIEW_REPORTS', 'Can view reports', 'report', 'view'),
('MANAGE_USERS', 'Can manage users', 'user', 'manage');

INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id FROM role r, permission p WHERE r.name = 'ADMIN';

INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id FROM role r, permission p 
WHERE r.name = 'MANAGER' AND p.name IN ('VIEW_TRANSACTIONS', 'CREATE_ALERT', 'REVIEW_ALERT', 'VIEW_REPORTS');

INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id FROM role r, permission p 
WHERE r.name = 'ANALYST' AND p.name IN ('VIEW_TRANSACTIONS', 'VIEW_REPORTS');

-- Now create views (after all test data is inserted)
CREATE VIEW v_model_performance AS
SELECT 
    model_name,
    version,
    model_type,
    status,
    auc,
    precision,
    recall,
    f1_score,
    created_at,
    (SELECT COUNT(*) FROM fraud_alert WHERE model_used_id = model_ml.id) as total_alerts,
    (SELECT COUNT(*) FROM transaction WHERE model_used_id = model_ml.id) as total_predictions
FROM model_ml
ORDER BY created_at DESC;

CREATE VIEW v_client_risk_profile AS
SELECT 
    c.id as client_id,
    c.first_name || ' ' || c.last_name as full_name,
    cb.client_avg_tx_amount,
    cb.client_avg_tx_per_day,
    cb.client_total_transactions,
    cb.client_risk_score,
    cb.client_is_unusual_region,
    cb.client_is_unusual_channel,
    cb.client_usual_region,
    cb.client_usual_channel,
    a.account_number,
    a.risk_level as account_risk_level,
    CASE 
        WHEN cb.client_risk_score > 0.8 THEN 'CRITICAL'
        WHEN cb.client_risk_score > 0.6 THEN 'HIGH'
        WHEN cb.client_risk_score > 0.3 THEN 'MEDIUM'
        ELSE 'LOW'
    END as behavioral_risk_level
FROM client c
LEFT JOIN account a ON c.id = a.client_id
LEFT JOIN client_behavior cb ON c.id = cb.client_id AND a.id = cb.account_id;

CREATE VIEW v_suspicious_transactions AS
SELECT 
    t.transaction_uuid,
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
LEFT JOIN client_behavior cb ON c.id = cb.client_id AND a.id = cb.account_id
WHERE f.risk_level IN ('HIGH', 'CRITICAL')
   OR t.is_fraud = true
ORDER BY t.transaction_date DESC;

CREATE VIEW v_user_permissions AS
SELECT 
    u.id as user_id,
    u.email,
    u.role,
    array_agg(DISTINCT p.name) as permissions
FROM app_user u
LEFT JOIN role r ON r.keycloak_role_name = LOWER(u.role)
LEFT JOIN role_permission rp ON r.id = rp.role_id
LEFT JOIN permission p ON rp.permission_id = p.id
GROUP BY u.id, u.email, u.role;