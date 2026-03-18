--1. Ajout nouvelle colonne visibility
ALTER TABLE dataset ADD COLUMN visibility BOOLEAN DEFAULT FALSE;

-- 2. Migration des données existantes vers la nouvelle colonne
UPDATE dataset 
SET visibility = CASE 
    WHEN status = 'CLEANED' OR status = 'PROCESSED' THEN TRUE 
    WHEN status = 'INACTIVE' THEN FALSE 
    ELSE FALSE 
END;

-- 3. Suppression de la contrainte check existante sur le statut
ALTER TABLE dataset DROP CONSTRAINT IF EXISTS dataset_status_check;

ALTER TABLE dataset ALTER COLUMN status DROP NOT NULL;

UPDATE dataset 
SET status = NULL 
WHERE status IN ('ACTIVE', 'INACTIVE');

-- 4. Mise à jour de la contrainte pour n'autoriser que 'CLEANED' et 'PROCESSED'
ALTER TABLE dataset ADD CONSTRAINT dataset_status_check 
    CHECK (status IS NULL OR status IN ('CLEANED', 'PROCESSED'));

-- 5. Mise à jour des index
DROP INDEX IF EXISTS idx_dataset_status;
CREATE INDEX idx_dataset_visibility ON dataset(visibility);
CREATE INDEX idx_dataset_status ON dataset(status) WHERE status IS NOT NULL;

-- 6. Mise à jour taille de colonne type
ALTER TABLE dataset ALTER COLUMN dataset_type TYPE varchar(30);