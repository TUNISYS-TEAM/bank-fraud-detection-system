ALTER TABLE dataset DROP CONSTRAINT dataset_status_check;
ALTER TABLE dataset ADD CONSTRAINT dataset_status_check 
    CHECK (status IN ('ACTIVE', 'INACTIVE', 'PROCESSED'));

ALTER TABLE model_ml 
DROP CONSTRAINT model_ml_model_type_check;

ALTER TABLE model_ml 
ADD CONSTRAINT model_ml_model_type_check 
CHECK (model_type IN ('RANDOM_FOREST', 'XGBOOST', 'LIGHTGBM'));