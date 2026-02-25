-- =====================================================
-- TABLE: keycloak_mapping
-- =====================================================
CREATE TABLE keycloak_mapping (
    id BIGSERIAL PRIMARY KEY,
    keycloak_id VARCHAR(255) UNIQUE NOT NULL,
    user_id BIGINT NOT NULL REFERENCES app_user(id),
    username VARCHAR(255),
    email VARCHAR(255),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    last_sync TIMESTAMP
);

CREATE INDEX idx_keycloak_mapping_keycloak_id ON keycloak_mapping(keycloak_id);
CREATE INDEX idx_keycloak_mapping_user_id ON keycloak_mapping(user_id);

-- =====================================================
-- TABLE: user_session
-- =====================================================
CREATE TABLE user_session (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES app_user(id),
    session_id VARCHAR(255) UNIQUE NOT NULL,
    keycloak_session_id VARCHAR(255),
    ip_address VARCHAR(45),
    user_agent TEXT,
    login_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    last_activity TIMESTAMP,
    logout_time TIMESTAMP,
    is_active BOOLEAN DEFAULT true
);

CREATE INDEX idx_user_session_user_id ON user_session(user_id);
CREATE INDEX idx_user_session_active ON user_session(is_active);

-- =====================================================
-- TABLE: permission
-- =====================================================
CREATE TABLE permission (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(100) UNIQUE NOT NULL,
    description TEXT,
    resource_type VARCHAR(50),
    action VARCHAR(50)
);

-- =====================================================
-- TABLE: role
-- =====================================================
CREATE TABLE role (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(100) UNIQUE NOT NULL,
    description TEXT,
    keycloak_role_name VARCHAR(100)
);

-- =====================================================
-- TABLE: role_permission
-- =====================================================
CREATE TABLE role_permission (
    role_id BIGINT REFERENCES role(id),
    permission_id BIGINT REFERENCES permission(id),
    PRIMARY KEY (role_id, permission_id)
);

-- Vue des permissions utilisateur
CREATE VIEW v_user_permissions AS
SELECT 
    u.id as user_id,
    u.email,
    u.role,
    array_agg(DISTINCT p.name) as permissions
FROM app_user u
JOIN role r ON r.keycloak_role_name = LOWER(u.role)
JOIN role_permission rp ON r.id = rp.role_id
JOIN permission p ON rp.permission_id = p.id
GROUP BY u.id, u.email, u.role;