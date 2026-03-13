BEGIN;

-- ============================================================
-- 1) Types (ENUM) pour cadrer le registre d'archivage
-- ============================================================
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_type WHERE typname = 'archive_type') THEN
    CREATE TYPE archive_type AS ENUM ('AUDIT_LOG', 'USER_SESSION');
  END IF;

  IF NOT EXISTS (SELECT 1 FROM pg_type WHERE typname = 'archive_status') THEN
    CREATE TYPE archive_status AS ENUM ('PENDING', 'CREATED', 'FAILED', 'DELETED');
  END IF;

  IF NOT EXISTS (SELECT 1 FROM pg_type WHERE typname = 'archive_format') THEN
    CREATE TYPE archive_format AS ENUM ('JSONL_GZ', 'PARQUET');
  END IF;
END $$;

-- ============================================================
-- 2) Table registre : archive_object
-- ============================================================
CREATE TABLE IF NOT EXISTS archive_object (
  id              BIGSERIAL PRIMARY KEY,

  -- Qu'est-ce qui est archivé ?
  type            archive_type NOT NULL,   -- AUDIT_LOG | USER_SESSION

  -- Fenêtre temporelle couverte par le fichier d'archive
  start_at        TIMESTAMP WITHOUT TIME ZONE NOT NULL,
  end_at          TIMESTAMP WITHOUT TIME ZONE NOT NULL,

  -- Référence MinIO/S3
  bucket          VARCHAR(255) NOT NULL,
  object_key      TEXT NOT NULL,           -- ex: archive/audit_logs/yyyy=.../file.parquet

  -- Métadonnées utiles UI
  format          archive_format NOT NULL DEFAULT 'JSONL_GZ',

  -- Statut d'exécution
  status          archive_status NOT NULL DEFAULT 'PENDING',

  -- Audit technique
  created_at      TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT NOW(),
  updated_at      TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT NOW()
);

-- ============================================================
-- 3) Contraintes de cohérence
-- ============================================================
ALTER TABLE archive_object
  ADD CONSTRAINT chk_archive_window
  CHECK (end_at > start_at);

-- Unicité: éviter doublons sur une même fenêtre / même objet
ALTER TABLE archive_object
  ADD CONSTRAINT uq_archive_object UNIQUE (type, start_at, end_at, bucket, object_key);

-- ============================================================
-- 4) Index pour l'UI (liste / filtres)
-- ============================================================
CREATE INDEX IF NOT EXISTS idx_archive_object_type_status_created_at
  ON archive_object(type, status, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_archive_object_type_window
  ON archive_object(type, start_at, end_at);

CREATE INDEX IF NOT EXISTS idx_archive_object_bucket_key
  ON archive_object(bucket, object_key);

-- ============================================================
-- 5) Trigger updated_at
-- ============================================================
CREATE OR REPLACE FUNCTION set_updated_at() RETURNS TRIGGER AS $$
BEGIN
  NEW.updated_at = NOW();
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_archive_object_updated_at ON archive_object;

CREATE TRIGGER trg_archive_object_updated_at
BEFORE UPDATE ON archive_object
FOR EACH ROW
EXECUTE FUNCTION set_updated_at();

COMMIT;

BEGIN;
-- Ajouter 'RESTORED' à l'ENUM archive_status
ALTER TYPE archive_status
ADD VALUE IF NOT EXISTS 'RESTORED';
COMMIT;