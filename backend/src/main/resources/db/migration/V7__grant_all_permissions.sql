INSERT INTO permission (name, description, action) VALUES
-- Page access permissions
('ACCESS_ADMINISTRATION', 'Accès à la page d''administration', '/dashboard/administration'),
('ACCESS_IMPORT_DATA',    'Accès à la page d''import de données', '/dashboard/import-data'),
('ACCESS_ML_MODELS',      'Accès à la page des modèles ML', '/dashboard/ml-models'),
('ACCESS_ALERTS',         'Accès à la page des alertes', '/dashboard/alerts'),
('ACCESS_PERFORMANCE',    'Accès à la page des performances', '/dashboard/performance'),
('ACCESS_SIMULATION',     'Accès à la page de simulation', '/dashboard/simulation'),

-- Feature permissions
('MANAGE_USERS',     'Créer, modifier et supprimer des utilisateurs', '/api/users/**'),
('MANAGE_ROLES',     'Créer, modifier et supprimer des rôles', '/api/roles/**'),
('VIEW_SESSIONS',    'Voir les sessions utilisateurs actives', '/api/sessions/**'),
('MANAGE_SESSIONS',  'Terminer les sessions utilisateurs', '/api/sessions/**'),
('MANAGE_ML_MODELS', 'Créer et configurer des modèles ML', '/api/ml-models/**'),
('MANAGE_ALERTS',    'Configurer et traiter les alertes', '/api/alerts/**'),
('IMPORT_DATA',      'Importer des données pour analyse', '/api/data/import'),
('RUN_SIMULATION',   'Lancer des simulations de détection', '/api/simulation/**')
ON CONFLICT (name) DO NOTHING;

-- 1) Renommer VIEW_SESSIONS (id=9) -> MANAGE_LOGS + adapter le path
UPDATE permission
SET name = 'MANAGE_LOGS',
    action = '/api/logs/**',
    description = 'Gérer les logs (consultation, export, purge)'
WHERE id = 9
  AND name = 'VIEW_SESSIONS';

-- 2) Ajouter 2 nouvelles permissions
INSERT INTO permission (name, description, action) VALUES
('MANAGE_SETTINGS', 'Gérer les paramètres de l’application', '/api/settings/**'),
('MANAGE_DATASETS', 'Gérer les datasets (import, suppression, consultation)', '/api/datasets/**')
ON CONFLICT (name) DO NOTHING;

-- 1) Assurer que le rôle ADMIN existe
INSERT INTO role (name, description, keycloak_role_name)
VALUES ('ADMIN', 'Administrateur - Accès complet au système', 'ADMIN')
ON CONFLICT (name) DO NOTHING;

-- 2) (Optionnel) Mettre le champ texte role dans app_user si c'est bien ton modèle
UPDATE app_user
SET role = 'ADMIN'
WHERE id = 1;

-- 3) Purge + réaffectation de toutes les permissions au rôle ADMIN
WITH admin_role AS (
  SELECT id FROM role WHERE name = 'ADMIN'
)
DELETE FROM role_permission rp
USING admin_role ar
WHERE rp.role_id = ar.id;

WITH admin_role AS (
  SELECT id FROM role WHERE name = 'ADMIN'
)
INSERT INTO role_permission (role_id, permission_id)
SELECT ar.id, p.id
FROM admin_role ar
CROSS JOIN permission p;
