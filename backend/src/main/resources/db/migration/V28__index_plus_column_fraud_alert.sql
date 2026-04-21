CREATE INDEX idx_tx_unscored_dataset
ON transaction(dataset_id)
WHERE fraud_score IS NULL;

CREATE INDEX idx_dataset_manager
ON dataset(assigned_manager_id);

ALTER TABLE fraud_alert
    ADD COLUMN IF NOT EXISTS assigned_analyst_id BIGINT REFERENCES app_user(id) ON DELETE SET NULL;

CREATE INDEX IF NOT EXISTS idx_fraud_alert_assigned_analyst_id ON fraud_alert(assigned_analyst_id);