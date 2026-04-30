-- 1) Ajouter le créateur du rapport (qui l'a généré)
ALTER TABLE rapport
ADD COLUMN IF NOT EXISTS generated_by_id BIGINT REFERENCES app_user(id);

CREATE INDEX IF NOT EXISTS idx_rapport_generated_by ON rapport(generated_by_id);

-- 2) Ajouter une description libre
ALTER TABLE rapport
ADD COLUMN IF NOT EXISTS description TEXT;

-- 3) Supprimer les attributs inutiles
ALTER TABLE rapport
DROP CONSTRAINT IF EXISTS rapport_model_id_fkey;

DROP INDEX IF EXISTS idx_rapport_model_id;

ALTER TABLE rapport DROP COLUMN IF EXISTS model_id; 
ALTER TABLE rapport DROP COLUMN IF EXISTS generated_at;
ALTER TABLE rapport DROP COLUMN IF EXISTS content;
ALTER TABLE rapport DROP COLUMN IF EXISTS file_path;

-- 4) Mettre à jour la contrainte type
ALTER TABLE rapport DROP CONSTRAINT IF EXISTS rapport_type_check;
ALTER TABLE rapport ADD CONSTRAINT rapport_type_check
CHECK (type IN ('DAILY', 'WEEKLY', 'MONTHLY', 'ON_DEMAND'));

-- 5) Ajouter l'attribut distribution_list (JSONB) pour les destinataires et leur état de lecture
ALTER TABLE rapport ADD COLUMN IF NOT EXISTS distribution_list JSONB DEFAULT '[]';

-- Index GIN pour des recherches performantes dans le JSON
CREATE INDEX IF NOT EXISTS idx_rapport_distribution_list ON rapport USING GIN (distribution_list);

-- Update notification type check constraint to include NEW_REPORT
ALTER TABLE notification DROP CONSTRAINT IF EXISTS notification_type_check;

ALTER TABLE notification ADD CONSTRAINT notification_type_check
    CHECK (type IN (
        'ALERT', 'INFO', 'WARNING', 'SUCCESS', 'MODEL_READY', 'CONFIG_UPDATED',
        'NEW_MESSAGE', 'VALIDATION_REQUEST', 'MODEL_VALIDATED', 'MODEL_REJECTED', 'NEW_REPORT'
    ));