# Identity Provider Integration

# --- !Ups

-- Identity Provider configurations
CREATE TABLE identity_providers (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    type ENUM('OIDC', 'SAML') NOT NULL DEFAULT 'OIDC',
    client_id VARCHAR(255),
    client_secret VARCHAR(1024), -- Encrypted client secret
    discovery_url VARCHAR(500),
    authorization_url VARCHAR(500),
    token_url VARCHAR(500),
    user_info_url VARCHAR(500),
    issuer VARCHAR(500),
    jwks_uri VARCHAR(500),
    scopes VARCHAR(255) DEFAULT 'openid email profile',
    enabled BOOLEAN DEFAULT TRUE,
    auto_provision BOOLEAN DEFAULT FALSE,
    role_mapping JSON,
    claim_mappings JSON, -- Map IdP claims to user attributes
    button_text VARCHAR(100) DEFAULT 'Sign in with Provider',
    button_color VARCHAR(7) DEFAULT '#007bff',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY unique_name (name)
);

-- Link users to external identities
CREATE TABLE user_external_identities (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    provider_id BIGINT NOT NULL,
    external_user_id VARCHAR(255) NOT NULL,
    email VARCHAR(255),
    display_name VARCHAR(255),
    first_name VARCHAR(255),
    last_name VARCHAR(255),
    access_token TEXT, -- Encrypted
    refresh_token TEXT, -- Encrypted
    token_expires_at TIMESTAMP NULL,
    last_login TIMESTAMP NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    FOREIGN KEY (provider_id) REFERENCES identity_providers(id) ON DELETE CASCADE,
    UNIQUE KEY unique_external_identity (provider_id, external_user_id)
);

-- OAuth state tracking for CSRF protection
CREATE TABLE oauth_states (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    state_token VARCHAR(255) NOT NULL,
    provider_id BIGINT NOT NULL,
    nonce VARCHAR(255),
    redirect_uri VARCHAR(500),
    code_verifier VARCHAR(255), -- For PKCE
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMP NOT NULL,
    FOREIGN KEY (provider_id) REFERENCES identity_providers(id) ON DELETE CASCADE,
    UNIQUE KEY unique_state_token (state_token),
    INDEX idx_expires_at (expires_at)
);

-- Authentication audit log
CREATE TABLE auth_audit_log (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NULL,
    provider_id BIGINT NULL,
    event_type ENUM('LOGIN_SUCCESS', 'LOGIN_FAILURE', 'LOGOUT', 'TOKEN_REFRESH', 'PROVISION_USER') NOT NULL,
    ip_address VARCHAR(45),
    user_agent TEXT,
    external_user_id VARCHAR(255),
    error_message TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE SET NULL,
    FOREIGN KEY (provider_id) REFERENCES identity_providers(id) ON DELETE SET NULL,
    INDEX idx_created_at (created_at),
    INDEX idx_user_id (user_id),
    INDEX idx_provider_id (provider_id)
);

-- Insert default OAuth providers (disabled by default)
INSERT INTO identity_providers (name, type, button_text, button_color, enabled, auto_provision) VALUES
('Google', 'OIDC', 'Sign in with Google', '#4285f4', FALSE, FALSE),
('Microsoft', 'OIDC', 'Sign in with Microsoft', '#0078d4', FALSE, FALSE),
('GitHub', 'OIDC', 'Sign in with GitHub', '#333333', FALSE, FALSE);

# --- !Downs

DROP TABLE IF EXISTS auth_audit_log;
DROP TABLE IF EXISTS oauth_states;
DROP TABLE IF EXISTS user_external_identities;
DROP TABLE IF EXISTS identity_providers;