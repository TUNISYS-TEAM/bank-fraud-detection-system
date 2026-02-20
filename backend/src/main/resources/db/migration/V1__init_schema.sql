-- Extension pour les UUID
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- =====================================================
-- TABLE: app_user
-- =====================================================
CREATE TABLE app_user (
    id BIGSERIAL PRIMARY KEY,
    uuid UUID DEFAULT uuid_generate_v4() UNIQUE,
    email VARCHAR(255) UNIQUE NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    first_name VARCHAR(100),
    last_name VARCHAR(100),
    phone_number VARCHAR(20),
    address TEXT,
    date_of_birth DATE,
    role VARCHAR(50) NOT NULL CHECK (role IN ('ADMIN', 'MANAGER', 'ANALYST')),
    is_active BOOLEAN DEFAULT true,
    last_login TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Index
CREATE INDEX idx_app_user_email ON app_user(email);
CREATE INDEX idx_app_user_role ON app_user(role);
CREATE INDEX idx_app_user_uuid ON app_user(uuid);

-- =====================================================
-- TABLE: client
-- =====================================================
CREATE TABLE client (
    id BIGSERIAL PRIMARY KEY,
    first_name VARCHAR(100),
    last_name VARCHAR(100),
    date_of_birth DATE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- =====================================================
-- TABLE: account
-- =====================================================
CREATE TABLE account (
    id BIGSERIAL PRIMARY KEY,
    account_number VARCHAR(50) UNIQUE NOT NULL,
    account_type VARCHAR(50) NOT NULL CHECK (account_type IN ('COURANT', 'EPARGNE', 'DEVISE', 'BLOQUE')),
    balance DECIMAL(15,2) DEFAULT 0.00,
    currency VARCHAR(3) DEFAULT 'TND',
    client_id BIGINT NOT NULL REFERENCES client(id) ON DELETE CASCADE,
    is_active BOOLEAN DEFAULT true,
    opened_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    closed_date TIMESTAMP,
    risk_level VARCHAR(20) DEFAULT 'LOW' CHECK (risk_level IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Index corrigé (remplace user_id par client_id)
CREATE INDEX idx_account_client_id ON account(client_id);
CREATE INDEX idx_account_account_number ON account(account_number);
CREATE INDEX idx_account_risk_level ON account(risk_level);

-- =====================================================
-- TABLE: transaction
-- =====================================================
CREATE TABLE transaction (
    id BIGSERIAL PRIMARY KEY,
    transaction_uuid UUID DEFAULT uuid_generate_v4() UNIQUE,
    account_id BIGINT NOT NULL REFERENCES account(id),
    amount DECIMAL(15,2) NOT NULL,
    currency VARCHAR(3) DEFAULT 'TND',
    transaction_date TIMESTAMP NOT NULL,
    description TEXT,
    merchant_name VARCHAR(255),
    merchant_category VARCHAR(100),
    channel VARCHAR(50) CHECK (channel IN ('ONLINE', 'POS', 'MOBILE', 'VIREMENT', 'CHEQUE')),
    status VARCHAR(20) DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'COMPLETED', 'FAILED', 'BLOCKED')),
    postal_code_merchant VARCHAR(20),
    postal_code_client VARCHAR(20),
    is_fraud BOOLEAN DEFAULT false,
    fraud_score DECIMAL(5,4),
    fraud_reason TEXT,
    model_used_id BIGINT,
    ip_address VARCHAR(45),
    device_id VARCHAR(255),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Index
CREATE INDEX idx_transaction_account_id ON transaction(account_id);
CREATE INDEX idx_transaction_date ON transaction(transaction_date);
CREATE INDEX idx_transaction_is_fraud ON transaction(is_fraud);
CREATE INDEX idx_transaction_fraud_score ON transaction(fraud_score);
CREATE INDEX idx_transaction_status ON transaction(status);
CREATE INDEX idx_transaction_model_used ON transaction(model_used_id);

-- =====================================================
-- TABLE: model_ml
-- =====================================================
CREATE TABLE model_ml (
    id BIGSERIAL PRIMARY KEY,
    model_uuid UUID DEFAULT uuid_generate_v4() UNIQUE,
    model_name VARCHAR(255) NOT NULL,
    model_type VARCHAR(50) NOT NULL CHECK (model_type IN ('Supervised', 'Unsupervised', 'Ensemble')),
    version VARCHAR(50) NOT NULL,
    auc DECIMAL(5,4),
    precision DECIMAL(5,4),
    recall DECIMAL(5,4),
    f1_score DECIMAL(5,4),
    status VARCHAR(50) NOT NULL CHECK (status IN ('Draft', 'Validated', 'Deployed', 'Archived')),
    created_by_id BIGINT REFERENCES app_user(id),
    validated_by_id BIGINT REFERENCES app_user(id),
    model_path VARCHAR(500),
    feature_importance JSONB,
    hyperparameters JSONB,
    training_date TIMESTAMP,
    validation_date TIMESTAMP,
    deployment_date TIMESTAMP,
    description TEXT,
    notes TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Index corrigé (model_id n'existe pas, on utilise id)
CREATE INDEX idx_model_ml_id ON model_ml(id);
CREATE INDEX idx_model_ml_status ON model_ml(status);
CREATE INDEX idx_model_ml_type ON model_ml(model_type);
CREATE INDEX idx_model_ml_created_by ON model_ml(created_by_id);

-- =====================================================
-- TABLE: client_behavior
-- =====================================================
CREATE TABLE client_behavior (
    id BIGSERIAL PRIMARY KEY,
    client_id BIGINT REFERENCES client(id),
    account_id BIGINT REFERENCES account(id),
    client_avg_tx_amount DECIMAL(15,2) DEFAULT 0,
    client_avg_tx_per_day DECIMAL(10,2) DEFAULT 0,
    client_total_transactions INTEGER DEFAULT 0,
    client_total_days_since_first INTEGER DEFAULT 0,
    client_amount_deviation DECIMAL(10,4) DEFAULT 0,
    client_abs_amount_deviation DECIMAL(10,4) DEFAULT 0,
    client_amount_z_score DECIMAL(10,4) DEFAULT 0,
    client_amount_max_ratio DECIMAL(10,4) DEFAULT 1,
    client_is_new_max BOOLEAN DEFAULT false,
    client_is_new_min BOOLEAN DEFAULT false,
    client_time_since_last_tx BIGINT DEFAULT 0,
    client_hours_since_last_tx DECIMAL(10,2) DEFAULT 0,
    client_usual_region VARCHAR(100),
    client_usual_channel VARCHAR(50),
    client_usual_category VARCHAR(100),
    client_region_freq DECIMAL(5,4) DEFAULT 1,
    client_channel_freq DECIMAL(5,4) DEFAULT 1,
    client_category_freq DECIMAL(5,4) DEFAULT 1,
    client_is_unusual_region BOOLEAN DEFAULT false,
    client_is_unusual_channel BOOLEAN DEFAULT false,
    client_is_unusual_category BOOLEAN DEFAULT false,
    client_risk_score DECIMAL(10,4) DEFAULT 0,
    -- Ajout de last_calculated_at manquant
    last_calculated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(client_id, account_id)
);

-- Index
CREATE INDEX idx_client_behavior_client_id ON client_behavior(client_id);
CREATE INDEX idx_client_behavior_account_id ON client_behavior(account_id);
CREATE INDEX idx_client_behavior_risk_score ON client_behavior(client_risk_score);
CREATE INDEX idx_client_behavior_calculated ON client_behavior(last_calculated_at);

-- =====================================================
-- TABLE: client_behavior_history
-- =====================================================
CREATE TABLE client_behavior_history (
    id BIGSERIAL PRIMARY KEY,
    client_id BIGINT NOT NULL REFERENCES client(id),
    account_id BIGINT REFERENCES account(id),
    behavior_snapshot JSONB NOT NULL,
    transaction_id BIGINT REFERENCES transaction(id),
    calculation_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Index
CREATE INDEX idx_behavior_history_client ON client_behavior_history(client_id);
CREATE INDEX idx_behavior_history_date ON client_behavior_history(calculation_date);

-- =====================================================
-- TABLE: model_deployment_history
-- =====================================================
CREATE TABLE model_deployment_history (
    id BIGSERIAL PRIMARY KEY,
    model_id BIGINT NOT NULL REFERENCES model_ml(id),
    deployed_by_id BIGINT REFERENCES app_user(id),
    deployment_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    undeployment_date TIMESTAMP,
    environment VARCHAR(50) CHECK (environment IN ('DEV', 'TEST', 'PROD')),
    status VARCHAR(50),
    performance_at_deployment JSONB,
    notes TEXT
);

-- Index
CREATE INDEX idx_model_deployment_model ON model_deployment_history(model_id);

-- =====================================================
-- TABLE: fraud_alert
-- =====================================================
CREATE TABLE fraud_alert (
    id BIGSERIAL PRIMARY KEY,
    alert_uuid UUID DEFAULT uuid_generate_v4() UNIQUE,
    transaction_id BIGINT NOT NULL REFERENCES transaction(id),
    account_id BIGINT NOT NULL REFERENCES account(id),
    user_id BIGINT NOT NULL REFERENCES app_user(id),
    fraud_score DECIMAL(5,4) NOT NULL,
    supervised_score DECIMAL(5,4),
    anomaly_score DECIMAL(5,4),
    model_used_id BIGINT REFERENCES model_ml(id),
    risk_level VARCHAR(20) NOT NULL CHECK (risk_level IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
    alert_type VARCHAR(50) CHECK (alert_type IN ('AMOUNT_ANOMALY', 'LOCATION_ANOMALY', 'VELOCITY', 'PATTERN', 'BEHAVIORAL')),
    status VARCHAR(20) DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'REVIEWED', 'CONFIRMED', 'FALSE_POSITIVE', 'BLOCKED')),
    reviewed_by BIGINT REFERENCES app_user(id),
    review_date TIMESTAMP,
    review_comment TEXT,
    action_taken VARCHAR(100),
    features_snapshot JSONB,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Index
CREATE INDEX idx_fraud_alert_account_id ON fraud_alert(account_id);
CREATE INDEX idx_fraud_alert_user_id ON fraud_alert(user_id);
CREATE INDEX idx_fraud_alert_status ON fraud_alert(status);
CREATE INDEX idx_fraud_alert_risk_level ON fraud_alert(risk_level);
CREATE INDEX idx_fraud_alert_model_used ON fraud_alert(model_used_id);

-- =====================================================
-- TABLE: notification
-- =====================================================
CREATE TABLE notification (
    id BIGSERIAL PRIMARY KEY,
    notification_uuid UUID DEFAULT uuid_generate_v4() UNIQUE,
    user_id BIGINT NOT NULL REFERENCES app_user(id),
    title VARCHAR(255) NOT NULL,
    message TEXT NOT NULL,
    type VARCHAR(50) CHECK (type IN ('ALERT', 'INFO', 'WARNING', 'SUCCESS')),
    is_read BOOLEAN DEFAULT false,
    read_at TIMESTAMP,
    action_url VARCHAR(500),
    metadata JSONB,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMP
);

-- Index
CREATE INDEX idx_notification_user_id ON notification(user_id);
CREATE INDEX idx_notification_is_read ON notification(is_read);

-- =====================================================
-- TABLE: message
-- =====================================================
CREATE TABLE message (
    id BIGSERIAL PRIMARY KEY,
    message_uuid UUID DEFAULT uuid_generate_v4() UNIQUE,
    sender_id BIGINT NOT NULL REFERENCES app_user(id),
    receiver_id BIGINT NOT NULL REFERENCES app_user(id),
    subject VARCHAR(255),
    content TEXT NOT NULL,
    is_read BOOLEAN DEFAULT false,
    read_at TIMESTAMP,
    parent_message_id BIGINT REFERENCES message(id),
    priority VARCHAR(20) DEFAULT 'NORMAL' CHECK (priority IN ('LOW', 'NORMAL', 'HIGH', 'URGENT')),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Index
CREATE INDEX idx_message_sender_id ON message(sender_id);
CREATE INDEX idx_message_receiver_id ON message(receiver_id);

-- =====================================================
-- TABLE: rapport
-- =====================================================
CREATE TABLE rapport (
    id BIGSERIAL PRIMARY KEY,
    rapport_uuid UUID DEFAULT uuid_generate_v4() UNIQUE,
    user_id BIGINT NOT NULL REFERENCES app_user(id),
    title VARCHAR(255) NOT NULL,
    type VARCHAR(50) CHECK (type IN ('DAILY', 'WEEKLY', 'MONTHLY', 'CUSTOM')),
    format VARCHAR(20) CHECK (format IN ('PDF', 'EXCEL', 'CSV', 'JSON')),
    content TEXT,
    file_path VARCHAR(500),
    data_summary JSONB,
    start_date TIMESTAMP,
    end_date TIMESTAMP,
    total_transactions INTEGER,
    fraud_detected INTEGER,
    avg_fraud_score DECIMAL(5,4),
    status VARCHAR(20) DEFAULT 'GENERATING' CHECK (status IN ('PENDING', 'GENERATING', 'COMPLETED', 'FAILED')),
    generated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Index
CREATE INDEX idx_rapport_user_id ON rapport(user_id);
CREATE INDEX idx_rapport_type ON rapport(type);

-- =====================================================
-- TABLE: audit_log
-- =====================================================
CREATE TABLE audit_log (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT REFERENCES app_user(id),
    action VARCHAR(100) NOT NULL,
    entity_type VARCHAR(50),
    entity_id BIGINT,
    old_value JSONB,
    new_value JSONB,
    ip_address VARCHAR(45),
    user_agent TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Index
CREATE INDEX idx_audit_log_user_id ON audit_log(user_id);
CREATE INDEX idx_audit_log_action ON audit_log(action);

-- =====================================================
-- FONCTIONS ET TRIGGERS CORRIGÉS
-- =====================================================

-- Mise à jour automatique de updated_at
CREATE OR REPLACE FUNCTION update_updated_at_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$ language 'plpgsql';

-- Triggers pour updated_at
CREATE TRIGGER update_app_user_updated_at BEFORE UPDATE ON app_user
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER update_client_updated_at BEFORE UPDATE ON client
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER update_account_updated_at BEFORE UPDATE ON account
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER update_transaction_updated_at BEFORE UPDATE ON transaction
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER update_fraud_alert_updated_at BEFORE UPDATE ON fraud_alert
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER update_model_ml_updated_at BEFORE UPDATE ON model_ml
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER update_client_behavior_updated_at BEFORE UPDATE ON client_behavior
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

-- Fonction corrigée pour mettre à jour le comportement client
CREATE OR REPLACE FUNCTION update_client_behavior_after_transaction()
RETURNS TRIGGER AS $$
DECLARE
    v_client_id BIGINT;
BEGIN
    -- Récupérer le client_id depuis le compte
    SELECT client_id INTO v_client_id FROM account WHERE id = NEW.account_id;
    
    -- Mettre à jour ou insérer le comportement client
    INSERT INTO client_behavior (
        client_id,
        account_id,
        client_avg_tx_amount,
        client_avg_tx_per_day,
        client_total_transactions,
        client_total_days_since_first,
        client_time_since_last_tx,
        last_calculated_at
    )
    SELECT 
        v_client_id,
        NEW.account_id,
        COALESCE((
            SELECT AVG(amount)::DECIMAL(15,2)
            FROM transaction 
            WHERE account_id = NEW.account_id 
            AND id <= NEW.id
        ), NEW.amount),
        COALESCE((
            SELECT COUNT(*)::DECIMAL(10,2) / 
                   GREATEST(EXTRACT(DAY FROM (MAX(transaction_date) - MIN(transaction_date))) + 1, 1)
            FROM transaction 
            WHERE account_id = NEW.account_id 
            AND id <= NEW.id
        ), 1),
        COALESCE((
            SELECT COUNT(*) 
            FROM transaction 
            WHERE account_id = NEW.account_id 
            AND id <= NEW.id
        ), 1),
        COALESCE((
            SELECT EXTRACT(DAY FROM (NEW.transaction_date - MIN(transaction_date)))::INTEGER
            FROM transaction 
            WHERE account_id = NEW.account_id 
            AND id <= NEW.id
        ), 0),
        COALESCE((
            SELECT EXTRACT(EPOCH FROM (NEW.transaction_date - MAX(transaction_date)))::BIGINT
            FROM transaction 
            WHERE account_id = NEW.account_id 
            AND id < NEW.id
        ), 0),
        NOW()
    ON CONFLICT (client_id, account_id) 
    DO UPDATE SET
        client_avg_tx_amount = EXCLUDED.client_avg_tx_amount,
        client_avg_tx_per_day = EXCLUDED.client_avg_tx_per_day,
        client_total_transactions = EXCLUDED.client_total_transactions,
        client_total_days_since_first = EXCLUDED.client_total_days_since_first,
        client_time_since_last_tx = EXCLUDED.client_time_since_last_tx,
        last_calculated_at = EXCLUDED.last_calculated_at;
    
    RETURN NEW;
END;
$$ language 'plpgsql';

-- Trigger après insertion de transaction
CREATE TRIGGER after_transaction_insert
    AFTER INSERT ON transaction
    FOR EACH ROW
    EXECUTE FUNCTION update_client_behavior_after_transaction();


-- =====================================================
-- VUES
-- =====================================================

-- Vue des performances des modèles
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

-- Vue corrigée du comportement client (utilise client au lieu de app_user)
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

-- Vue corrigée des transactions suspectes (utilise client au lieu de app_user)
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

-- =====================================================
-- DONNÉES DE TEST CORRIGÉES
-- =====================================================

-- Insérer des utilisateurs (app_user)
INSERT INTO app_user (email, password_hash, first_name, last_name, role) VALUES
('admin@fraud.com', '$2a$10$HkQqXK9QqXK9QqXK9QqXKO', 'Admin', 'System', 'ADMIN'),
('manager@fraud.com', '$2a$10$HkQqXK9QqXK9QqXK9QqXKO', 'Manager', 'User', 'MANAGER'),
('analyst1@fraud.com', '$2a$10$HkQqXK9QqXK9QqXK9QqXKO', 'Jean', 'Dupont', 'ANALYST'),
('analyst2@fraud.com', '$2a$10$HkQqXK9QqXK9QqXK9QqXKO', 'Marie', 'Martin', 'ANALYST');

-- Insérer des clients
INSERT INTO client (first_name, last_name, date_of_birth) VALUES
('Alice', 'Smith', '1985-04-12'),
('Bob', 'Johnson', '1990-08-23'),
('Charlie', 'Brown', '1975-12-05'),
('Diana', 'Prince', '1988-03-14');


-- Insérer des comptes
INSERT INTO account (account_number, account_type, balance, currency, client_id, risk_level) VALUES
('TN76123450001', 'COURANT', 15000.00, 'TND', 1, 'MEDIUM'),
('TN76123450002', 'EPARGNE', 45000.00, 'TND', 1, 'LOW'),
('TN76123450003', 'COURANT', 8500.00, 'TND', 2, 'LOW'),
('TN76123450004', 'DEVISE', 25000.00, 'EUR', 2, 'MEDIUM'),
('TN76123450005', 'COURANT', 22000.00, 'TND', 3, 'HIGH'),
('TN76123450006', 'BLOQUE', 100000.00, 'TND', 4, 'LOW');

