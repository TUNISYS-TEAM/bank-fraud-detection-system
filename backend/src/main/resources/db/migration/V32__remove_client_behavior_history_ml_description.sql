-- Suppression de la table client_behavior_history
DROP TABLE IF EXISTS client_behavior_history CASCADE;

-- Suppression de la colonne description de la table model_ml
ALTER TABLE model_ml DROP COLUMN IF EXISTS description;