-- Update MANAGE_DATASETS: rename to ACCESS_DATASETS and fix action path
UPDATE permission
SET name = 'ACCESS_DATASETS',
    action = '/dashboard/datasets'
WHERE name = 'MANAGE_DATASETS';

-- Update MANAGE_CLIENTS_ACCOUNTS: rename to ACCESS_CLIENTS_ACCOUNTS and fix action path
UPDATE permission
SET name = 'ACCESS_CLIENTS_ACCOUNTS',
    action = '/dashboard/clients-accounts'
WHERE name = 'MANAGE_CLIENTS_ACCOUNTS';

-- Add manager_id column to app_user table
ALTER TABLE app_user ADD COLUMN manager_id BIGINT;

-- Add foreign key constraint to reference the same table (self-referential)
ALTER TABLE app_user ADD CONSTRAINT fk_app_user_manager 
    FOREIGN KEY (manager_id) REFERENCES app_user(id) ON DELETE SET NULL;

-- Create index for better performance on manager queries
CREATE INDEX idx_app_user_manager_id ON app_user(manager_id);