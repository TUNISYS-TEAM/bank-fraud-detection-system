ALTER TABLE dataset DROP CONSTRAINT dataset_status_check;
ALTER TABLE dataset ADD CONSTRAINT dataset_status_check 
    CHECK (status IN ('ACTIVE', 'INACTIVE', 'PROCESSED', 'CLEANED'));