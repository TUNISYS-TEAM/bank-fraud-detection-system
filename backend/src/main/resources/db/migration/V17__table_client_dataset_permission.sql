ALTER TABLE dataset ADD COLUMN dataset_type VARCHAR(20) NOT NULL DEFAULT 'TRANSACTIONS_TRAIN';
ALTER TABLE client ADD COLUMN national_id VARCHAR(50);
ALTER TABLE client RENAME COLUMN id TO client_id;
BEGIN;

-- 1) Ajouter la permission pour la section Clients & Comptes
INSERT INTO permission (name, description, action)
VALUES ('MANAGE_CLIENTS_ACCOUNTS', 'Gérer les clients et leurs comptes (consultation, détails)', '/dashboard/administration/clients-accounts')
ON CONFLICT (name) DO NOTHING;

-- 2) Affecter cette permission au rôle ADMIN
INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM role r, permission p
WHERE r.name = 'ADMIN' 
  AND p.name = 'MANAGE_CLIENTS_ACCOUNTS'
  AND NOT EXISTS (
    SELECT 1 FROM role_permission rp 
    WHERE rp.role_id = r.id AND rp.permission_id = p.id
  );

COMMIT;