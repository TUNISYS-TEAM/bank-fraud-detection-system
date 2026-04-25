ALTER TABLE transaction ADD COLUMN final_decision BOOLEAN DEFAULT FALSE;

UPDATE transaction t
SET final_decision = true
FROM fraud_alert a
WHERE a.transaction_id = t.id AND a.status = 'CONFIRMED';