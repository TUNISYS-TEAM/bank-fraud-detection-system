BEGIN;

INSERT INTO permission (name, description, action)
VALUES ('ACCESS_ANALYZED_TRANSACTIONS', 'Accès à la page des transactions analysées par le modèle', '/dashboard/analyzed-transactions')
ON CONFLICT (name) DO NOTHING;

COMMIT;