BEGIN;

-- =========================================================
-- 0) AUDIT_LOG : conserver session_id BIGINT + FK
-- =========================================================

-- 0.1) session_id doit rester BIGINT 
ALTER TABLE audit_log
  ALTER COLUMN session_id TYPE BIGINT
  USING NULLIF(session_id::text, '')::bigint;

-- 0.2) session_id doit être nullable (pour les logs "system")
ALTER TABLE audit_log
  ALTER COLUMN session_id DROP NOT NULL;

-- 0.3) Recréer la FK vers user_session(id)
ALTER TABLE audit_log DROP CONSTRAINT IF EXISTS audit_log_session_id_fkey;

ALTER TABLE audit_log
  ADD CONSTRAINT audit_log_session_id_fkey
  FOREIGN KEY (session_id)
  REFERENCES user_session(id)
  ON DELETE SET NULL;

-- 0.4) Suppression de colonnes obsolètes si elles existent
DO $$
BEGIN
  IF EXISTS (
    SELECT 1
    FROM information_schema.columns
    WHERE table_schema='public'
      AND table_name='audit_log'
      AND column_name='user_id'
  ) THEN
    ALTER TABLE audit_log DROP COLUMN user_id;
  END IF;
END $$;

-- =========================================================
-- 1) AUTHENTICATION - user_session
-- session_id dans audit_log = user_session.id (BIGINT)
-- =========================================================
CREATE OR REPLACE FUNCTION trg_audit_user_session()
RETURNS TRIGGER AS $$
BEGIN
  IF TG_OP = 'INSERT' THEN
    INSERT INTO audit_log (session_id, action, action_type, action_timestamp, level)
    VALUES (NEW.id, 'Connexion utilisateur', 'Authentification', NOW(), 'Info');

  ELSIF TG_OP = 'UPDATE' THEN
    IF NEW.is_active = false AND OLD.is_active = true THEN
      INSERT INTO audit_log (session_id, action, action_type, action_timestamp, level)
      VALUES (NEW.id, 'Déconnexion utilisateur', 'Authentification', NOW(), 'Info');
    END IF;
  END IF;

  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_user_session ON user_session;
CREATE TRIGGER trg_user_session
AFTER INSERT OR UPDATE ON user_session
FOR EACH ROW EXECUTE FUNCTION trg_audit_user_session();

-- =========================================================
-- 2) USER MANAGEMENT - app_user
-- session_id = NULL (action système)
-- =========================================================
CREATE OR REPLACE FUNCTION trg_audit_app_user()
RETURNS TRIGGER AS $$
BEGIN
  IF TG_OP = 'INSERT' THEN
    INSERT INTO audit_log (session_id, action, action_type, action_timestamp, level)
    VALUES (NULL, 'SYSTEM - Création utilisateur : ' || NEW.email, 'Gestion utilisateur', NOW(), 'Info');

  ELSIF TG_OP = 'UPDATE' THEN
    IF OLD.role_id IS DISTINCT FROM NEW.role_id THEN
      INSERT INTO audit_log (session_id, action, action_type, action_timestamp, level)
      VALUES (
        NULL,
        'SYSTEM - Changement de rôle pour ' || NEW.email || ' (rôle ID: ' || OLD.role_id || ' → ' || NEW.role_id || ')',
        'Gestion utilisateur',
        NOW(),
        'Attention'
      );
    END IF;

    IF OLD.is_active IS DISTINCT FROM NEW.is_active THEN
      INSERT INTO audit_log (session_id, action, action_type, action_timestamp, level)
      VALUES (
        NULL,
        'SYSTEM - ' ||
        (CASE WHEN NEW.is_active THEN 'Activation utilisateur : ' ELSE 'Désactivation utilisateur : ' END) || NEW.email,
        'Gestion utilisateur',
        NOW(),
        'Attention'
      );
    END IF;

  ELSIF TG_OP = 'DELETE' THEN
    INSERT INTO audit_log (session_id, action, action_type, action_timestamp, level)
    VALUES (NULL, 'SYSTEM - Suppression utilisateur : ' || OLD.email, 'Gestion utilisateur', NOW(), 'Attention');
  END IF;

  RETURN COALESCE(NEW, OLD);
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_app_user ON app_user;
CREATE TRIGGER trg_app_user
AFTER INSERT OR UPDATE OR DELETE ON app_user
FOR EACH ROW EXECUTE FUNCTION trg_audit_app_user();

-- =========================================================
-- 3) ML MODEL - model_ml
-- session_id = NULL (action système)
-- =========================================================
CREATE OR REPLACE FUNCTION trg_audit_model_ml()
RETURNS TRIGGER AS $$
BEGIN
  IF TG_OP = 'INSERT' THEN
    INSERT INTO audit_log (session_id, action, action_type, action_timestamp, level)
    VALUES (NULL, 'SYSTEM - Création modèle ML : ' || NEW.model_name, 'Modèle', NOW(), 'Info');

  ELSIF TG_OP = 'UPDATE' THEN
    IF OLD.status IS DISTINCT FROM NEW.status THEN
      INSERT INTO audit_log (session_id, action, action_type, action_timestamp, level)
      VALUES (
        NULL,
        'SYSTEM - Statut modèle ' || NEW.model_name || ' : ' || OLD.status || ' → ' || NEW.status,
        'Modèle',
        NOW(),
        CASE
          WHEN NEW.status ILIKE '%fail%' OR NEW.status ILIKE '%error%' THEN 'Erreur'
          WHEN NEW.status ILIKE '%deploy%' THEN 'Succès'
          ELSE 'Info'
        END
      );
    END IF;

  ELSIF TG_OP = 'DELETE' THEN
    INSERT INTO audit_log (session_id, action, action_type, action_timestamp, level)
    VALUES (NULL, 'SYSTEM - Suppression modèle ML : ' || OLD.model_name, 'Modèle', NOW(), 'Attention');
  END IF;

  RETURN COALESCE(NEW, OLD);
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_model_ml ON model_ml;
CREATE TRIGGER trg_model_ml
AFTER INSERT OR UPDATE OR DELETE ON model_ml
FOR EACH ROW EXECUTE FUNCTION trg_audit_model_ml();

-- =========================================================
-- 4) MODEL DEPLOYMENT - model_deployment_history
-- session_id = NULL (action système)
-- =========================================================
CREATE OR REPLACE FUNCTION trg_audit_deployment()
RETURNS TRIGGER AS $$
BEGIN
  IF TG_OP = 'INSERT' THEN
    INSERT INTO audit_log (session_id, action, action_type, action_timestamp, level)
    VALUES (
      NULL,
      'SYSTEM - Déploiement modèle ID ' || NEW.model_id || ' — statut : ' || NEW.status,
      'Déploiement',
      NOW(),
      CASE
        WHEN NEW.status ILIKE '%fail%' OR NEW.status ILIKE '%error%' THEN 'Erreur'
        WHEN NEW.status ILIKE '%success%' OR NEW.status ILIKE '%active%' THEN 'Succès'
        ELSE 'Info'
      END
    );
  END IF;

  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_model_deployment ON model_deployment_history;
CREATE TRIGGER trg_model_deployment
AFTER INSERT ON model_deployment_history
FOR EACH ROW EXECUTE FUNCTION trg_audit_deployment();

-- =========================================================
-- 5) FRAUD ALERTS - fraud_alert
-- session_id = NULL (action système)
-- =========================================================
CREATE OR REPLACE FUNCTION trg_audit_fraud_alert()
RETURNS TRIGGER AS $$
BEGIN
  IF TG_OP = 'INSERT' THEN
    INSERT INTO audit_log (session_id, action, action_type, action_timestamp, level)
    VALUES (NULL, 'SYSTEM - Nouvelle alerte fraude créée : ' || NEW.id, 'Alerte', NOW(), 'Attention');

  ELSIF TG_OP = 'UPDATE' THEN
    IF OLD.status IS DISTINCT FROM NEW.status THEN
      INSERT INTO audit_log (session_id, action, action_type, action_timestamp, level)
      VALUES (
        NULL,
        'SYSTEM - Alerte ' || NEW.id || ' : statut ' || OLD.status || ' → ' || NEW.status,
        'Alerte',
        NOW(),
        CASE
          WHEN NEW.status ILIKE '%fraud%' OR NEW.status ILIKE '%confirm%' THEN 'Erreur'
          WHEN NEW.status ILIKE '%resolv%' OR NEW.status ILIKE '%close%' THEN 'Succès'
          ELSE 'Attention'
        END
      );
    END IF;
  END IF;

  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_fraud_alert ON fraud_alert;
CREATE TRIGGER trg_fraud_alert
AFTER INSERT OR UPDATE ON fraud_alert
FOR EACH ROW EXECUTE FUNCTION trg_audit_fraud_alert();

-- =========================================================
-- 6) ROLES - role
-- session_id = NULL (action système)
-- =========================================================
CREATE OR REPLACE FUNCTION trg_audit_role()
RETURNS TRIGGER AS $$
BEGIN
  IF TG_OP = 'INSERT' THEN
    INSERT INTO audit_log (session_id, action, action_type, action_timestamp, level)
    VALUES (NULL, 'SYSTEM - Création rôle : ' || NEW.name, 'Gestion rôle', NOW(), 'Info');

  ELSIF TG_OP = 'DELETE' THEN
    INSERT INTO audit_log (session_id, action, action_type, action_timestamp, level)
    VALUES (NULL, 'SYSTEM - Suppression rôle : ' || OLD.name, 'Gestion rôle', NOW(), 'Attention');
  END IF;

  RETURN COALESCE(NEW, OLD);
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_role ON role;
CREATE TRIGGER trg_role
AFTER INSERT OR DELETE ON role
FOR EACH ROW EXECUTE FUNCTION trg_audit_role();

COMMIT;