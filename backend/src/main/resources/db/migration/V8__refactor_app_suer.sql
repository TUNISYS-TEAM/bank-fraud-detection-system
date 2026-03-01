-- 1. S'assurer que les rôles prédéfinis existent dans la table 'role'
INSERT INTO role (name, description, keycloak_role_name)
SELECT 'ADMIN', 'Administrateur système', 'admin'
WHERE NOT EXISTS (SELECT 1 FROM role WHERE name = 'ADMIN');

INSERT INTO role (name, description, keycloak_role_name)
SELECT 'MANAGER', 'Plateforme Manager', 'manager'
WHERE NOT EXISTS (SELECT 1 FROM role WHERE name = 'MANAGER');

INSERT INTO role (name, description, keycloak_role_name)
SELECT 'ANALYST', 'Analyste des fraudes', 'analyst'
WHERE NOT EXISTS (SELECT 1 FROM role WHERE name = 'ANALYST');

-- 2. Ajouter la colonne role_id si elle n'existe pas déjà
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns 
                   WHERE table_name = 'app_user' AND column_name = 'role_id') THEN
        ALTER TABLE app_user ADD COLUMN role_id BIGINT;
    END IF;
END $$;

-- 3. Migration des données (seulement si role_id est null)
UPDATE app_user u
SET role_id = r.id
FROM role r
WHERE u.role = r.name 
  AND u.role_id IS NULL;

-- 4. Gérer les dépendances
DROP VIEW IF EXISTS v_user_permissions;

-- 5. Nettoyer l'ancienne structure
DROP INDEX IF EXISTS idx_app_user_role;
ALTER TABLE app_user DROP COLUMN IF EXISTS role;

-- 6. Ajouter les contraintes (si pas déjà présentes)
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.table_constraints 
                   WHERE constraint_name = 'fk_app_user_role') THEN
        ALTER TABLE app_user 
        ADD CONSTRAINT fk_app_user_role FOREIGN KEY (role_id) REFERENCES role(id);
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_app_user_role_id ON app_user(role_id);

-- 7. Recréer la vue
CREATE OR REPLACE VIEW v_user_permissions AS
SELECT u.id as user_id,
    u.email,
    r.name as role,
    array_agg(DISTINCT p.name) as permissions
FROM app_user u
    JOIN role r ON u.role_id = r.id
    JOIN role_permission rp ON r.id = rp.role_id
    JOIN permission p ON rp.permission_id = p.id
GROUP BY u.id, u.email, r.name;