-- V43: Multi-team support — add team assignment columns
-- 1. Dataset gets an assigned_manager_id for team ownership
-- 2. Transaction gets a dataset_id to trace which validation dataset it belongs to
-- 3. ModelDeploymentHistory gets a team_manager_id for per-team deployments

-- 1. Add assigned_manager_id to dataset
ALTER TABLE dataset ADD COLUMN IF NOT EXISTS assigned_manager_id BIGINT;
ALTER TABLE dataset ADD CONSTRAINT fk_dataset_assigned_manager
    FOREIGN KEY (assigned_manager_id) REFERENCES app_user(id)
    ON DELETE SET NULL;
CREATE INDEX IF NOT EXISTS idx_dataset_assigned_manager ON dataset(assigned_manager_id);

-- 2. Add dataset_id to transaction (trace origin validation dataset)
ALTER TABLE transaction ADD COLUMN IF NOT EXISTS dataset_id BIGINT;
ALTER TABLE transaction ADD CONSTRAINT fk_transaction_dataset
    FOREIGN KEY (dataset_id) REFERENCES dataset(id)
    ON DELETE SET NULL;
CREATE INDEX IF NOT EXISTS idx_transaction_dataset ON transaction(dataset_id);

-- 3. Add team_manager_id to model_deployment_history (per-team deployment)
ALTER TABLE model_deployment_history ADD COLUMN IF NOT EXISTS team_manager_id BIGINT;
ALTER TABLE model_deployment_history ADD CONSTRAINT fk_deployment_team_manager
    FOREIGN KEY (team_manager_id) REFERENCES app_user(id)
    ON DELETE SET NULL;
CREATE INDEX IF NOT EXISTS idx_deployment_team_manager ON model_deployment_history(team_manager_id);