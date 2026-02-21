-- Create model_deployment_history table
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

CREATE INDEX idx_model_deployment_model ON model_deployment_history(model_id);

-- Create fraud_alert table
CREATE TABLE fraud_alert (
    id BIGSERIAL PRIMARY KEY,
    alert_uuid UUID DEFAULT uuid_generate_v4() UNIQUE,
    transaction_id BIGINT NOT NULL REFERENCES transaction(id),
    account_id BIGINT NOT NULL REFERENCES account(id),
    user_id BIGINT NOT NULL REFERENCES app_user(id),
    fraud_score DOUBLE PRECISION NOT NULL,
    supervised_score DOUBLE PRECISION,
    anomaly_score DOUBLE PRECISION,
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

CREATE INDEX idx_fraud_alert_account_id ON fraud_alert(account_id);
CREATE INDEX idx_fraud_alert_user_id ON fraud_alert(user_id);
CREATE INDEX idx_fraud_alert_status ON fraud_alert(status);
CREATE INDEX idx_fraud_alert_risk_level ON fraud_alert(risk_level);
CREATE INDEX idx_fraud_alert_model_used ON fraud_alert(model_used_id);

CREATE TRIGGER update_fraud_alert_updated_at BEFORE UPDATE ON fraud_alert
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

-- Create notification table
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

CREATE INDEX idx_notification_user_id ON notification(user_id);
CREATE INDEX idx_notification_is_read ON notification(is_read);

-- Create message table
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

CREATE INDEX idx_message_sender_id ON message(sender_id);
CREATE INDEX idx_message_receiver_id ON message(receiver_id);

-- Create rapport table
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
    avg_fraud_score DOUBLE PRECISION,
    status VARCHAR(20) DEFAULT 'GENERATING' CHECK (status IN ('PENDING', 'GENERATING', 'COMPLETED', 'FAILED')),
    generated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_rapport_user_id ON rapport(user_id);
CREATE INDEX idx_rapport_type ON rapport(type);

-- Create audit_log table
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

CREATE INDEX idx_audit_log_user_id ON audit_log(user_id);
CREATE INDEX idx_audit_log_action ON audit_log(action);