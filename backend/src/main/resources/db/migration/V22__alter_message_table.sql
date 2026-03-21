ALTER TABLE message ADD COLUMN message_type VARCHAR(20) DEFAULT 'GENERAL';

ALTER TABLE message ADD COLUMN is_archived BOOLEAN DEFAULT FALSE;
UPDATE message SET is_archived = FALSE WHERE is_archived IS NULL;

ALTER TABLE model_ml DROP CONSTRAINT IF EXISTS model_ml_status_check;
ALTER TABLE model_ml ADD CONSTRAINT model_ml_status_check 
    CHECK (status IN ('Draft', 'Validated', 'Deployed', 'Archived', 'Rejected'));