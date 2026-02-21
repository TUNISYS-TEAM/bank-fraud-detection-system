-- Create account table
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

CREATE INDEX idx_account_client_id ON account(client_id);
CREATE INDEX idx_account_account_number ON account(account_number);
CREATE INDEX idx_account_risk_level ON account(risk_level);

CREATE TRIGGER update_account_updated_at BEFORE UPDATE ON account
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

-- Create model_ml table
CREATE TABLE model_ml (
    id BIGSERIAL PRIMARY KEY,
    model_uuid UUID DEFAULT uuid_generate_v4() UNIQUE,
    model_name VARCHAR(255) NOT NULL,
    model_type VARCHAR(50) NOT NULL CHECK (model_type IN ('Supervised', 'Unsupervised', 'Ensemble')),
    version VARCHAR(50) NOT NULL,
    auc DOUBLE PRECISION,
    precision DOUBLE PRECISION,
    recall DOUBLE PRECISION,
    f1_score DOUBLE PRECISION,
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

CREATE INDEX idx_model_ml_status ON model_ml(status);
CREATE INDEX idx_model_ml_type ON model_ml(model_type);
CREATE INDEX idx_model_ml_created_by ON model_ml(created_by_id);

CREATE TRIGGER update_model_ml_updated_at BEFORE UPDATE ON model_ml
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

-- Create transaction table
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
    fraud_score DOUBLE PRECISION,
    fraud_reason TEXT,
    model_used_id BIGINT REFERENCES model_ml(id),
    ip_address VARCHAR(45),
    device_id VARCHAR(255),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_transaction_account_id ON transaction(account_id);
CREATE INDEX idx_transaction_date ON transaction(transaction_date);
CREATE INDEX idx_transaction_is_fraud ON transaction(is_fraud);
CREATE INDEX idx_transaction_fraud_score ON transaction(fraud_score);
CREATE INDEX idx_transaction_status ON transaction(status);
CREATE INDEX idx_transaction_model_used ON transaction(model_used_id);

CREATE TRIGGER update_transaction_updated_at BEFORE UPDATE ON transaction
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();