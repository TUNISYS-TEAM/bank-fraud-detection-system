BEGIN;

-- 1) Ajouter la permission pour la page Données d'Entraînement
INSERT INTO permission (name, description, action)
VALUES ('ACCESS_TRAINING_DATA', 'Accès à la gestion des datasets d''entraînement', '/dashboard/training-data')
ON CONFLICT (name) DO NOTHING;

COMMIT;