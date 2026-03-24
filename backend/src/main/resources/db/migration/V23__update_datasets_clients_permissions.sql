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