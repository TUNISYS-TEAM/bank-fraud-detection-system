-- Create client_behavior table
CREATE TABLE client_behavior (
    id BIGSERIAL PRIMARY KEY,
    client_id BIGINT REFERENCES client(id),
    account_id BIGINT REFERENCES account(id),
    client_avg_tx_amount DOUBLE PRECISION DEFAULT 0,
    client_avg_tx_per_day DOUBLE PRECISION DEFAULT 0,
    client_total_transactions INTEGER DEFAULT 0,
    client_total_days_since_first INTEGER DEFAULT 0,
    client_amount_deviation DOUBLE PRECISION DEFAULT 0,
    client_abs_amount_deviation DOUBLE PRECISION DEFAULT 0,
    client_amount_z_score DOUBLE PRECISION DEFAULT 0,
    client_amount_max_ratio DOUBLE PRECISION DEFAULT 1,
    client_is_new_max BOOLEAN DEFAULT false,
    client_is_new_min BOOLEAN DEFAULT false,
    client_time_since_last_tx BIGINT DEFAULT 0,
    client_hours_since_last_tx DOUBLE PRECISION DEFAULT 0,
    client_usual_region VARCHAR(100),
    client_usual_channel VARCHAR(50),
    client_usual_category VARCHAR(100),
    client_region_freq DOUBLE PRECISION DEFAULT 1,
    client_channel_freq DOUBLE PRECISION DEFAULT 1,
    client_category_freq DOUBLE PRECISION DEFAULT 1,
    client_is_unusual_region BOOLEAN DEFAULT false,
    client_is_unusual_channel BOOLEAN DEFAULT false,
    client_is_unusual_category BOOLEAN DEFAULT false,
    client_risk_score DOUBLE PRECISION DEFAULT 0,
    last_calculated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(client_id, account_id)
);

CREATE INDEX idx_client_behavior_client_id ON client_behavior(client_id);
CREATE INDEX idx_client_behavior_account_id ON client_behavior(account_id);
CREATE INDEX idx_client_behavior_risk_score ON client_behavior(client_risk_score);
CREATE INDEX idx_client_behavior_calculated ON client_behavior(last_calculated_at);

CREATE TRIGGER update_client_behavior_updated_at BEFORE UPDATE ON client_behavior
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

-- Create client_behavior_history table
CREATE TABLE client_behavior_history (
    id BIGSERIAL PRIMARY KEY,
    client_id BIGINT NOT NULL REFERENCES client(id),
    account_id BIGINT REFERENCES account(id),
    behavior_snapshot JSONB NOT NULL,
    transaction_id BIGINT REFERENCES transaction(id),
    calculation_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_behavior_history_client ON client_behavior_history(client_id);
CREATE INDEX idx_behavior_history_date ON client_behavior_history(calculation_date);

-- Create function for updating client behavior after transaction
CREATE OR REPLACE FUNCTION update_client_behavior_after_transaction()
RETURNS TRIGGER AS $$
DECLARE
    v_client_id BIGINT;
BEGIN
    SELECT client_id INTO v_client_id FROM account WHERE id = NEW.account_id;
    
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
            SELECT AVG(amount)::DOUBLE PRECISION
            FROM transaction 
            WHERE account_id = NEW.account_id 
            AND id <= NEW.id
        ), NEW.amount::DOUBLE PRECISION),
        COALESCE((
            SELECT COUNT(*)::DOUBLE PRECISION / 
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

-- Create trigger
CREATE TRIGGER after_transaction_insert
    AFTER INSERT ON transaction
    FOR EACH ROW
    EXECUTE FUNCTION update_client_behavior_after_transaction();