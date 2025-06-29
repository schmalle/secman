-- !Ups

-- Consolidated Play Framework Evolution Script
-- This script creates the complete secman database schema in a compact, optimized format

-- Users and Authentication
CREATE TABLE users (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(255) NOT NULL UNIQUE,
    email VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_username (username),
    INDEX idx_email (email)
);

CREATE TABLE user_roles (
    user_id BIGINT NOT NULL,
    role_name VARCHAR(50) NOT NULL,
    PRIMARY KEY (user_id, role_name),
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    INDEX idx_role_name (role_name)
);

-- Identity Provider Integration
CREATE TABLE identity_providers (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    type ENUM('OIDC', 'SAML') NOT NULL DEFAULT 'OIDC',
    client_id VARCHAR(255),
    client_secret VARCHAR(1024),
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
    claim_mappings JSON,
    button_text VARCHAR(100) DEFAULT 'Sign in with Provider',
    button_color VARCHAR(7) DEFAULT '#007bff',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY unique_name (name)
);

CREATE TABLE user_external_identities (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    provider_id BIGINT NOT NULL,
    external_user_id VARCHAR(255) NOT NULL,
    email VARCHAR(255),
    display_name VARCHAR(255),
    first_name VARCHAR(255),
    last_name VARCHAR(255),
    access_token TEXT,
    refresh_token TEXT,
    token_expires_at TIMESTAMP NULL,
    last_login TIMESTAMP NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    FOREIGN KEY (provider_id) REFERENCES identity_providers(id) ON DELETE CASCADE,
    UNIQUE KEY unique_external_identity (provider_id, external_user_id)
);

CREATE TABLE oauth_states (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    state_token VARCHAR(255) NOT NULL,
    provider_id BIGINT NOT NULL,
    nonce VARCHAR(255),
    redirect_uri VARCHAR(500),
    code_verifier VARCHAR(255),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMP NOT NULL,
    FOREIGN KEY (provider_id) REFERENCES identity_providers(id) ON DELETE CASCADE,
    UNIQUE KEY unique_state_token (state_token),
    INDEX idx_expires_at (expires_at)
);

CREATE TABLE auth_audit_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
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

-- Version Management
CREATE TABLE releases (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    version VARCHAR(50) NOT NULL UNIQUE,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    release_date TIMESTAMP NULL,
    created_by BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    FOREIGN KEY (created_by) REFERENCES users(id),
    INDEX idx_version (version),
    INDEX idx_status (status),
    INDEX idx_release_date (release_date)
);

-- Core Business Entities with Versioning
CREATE TABLE standard (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(255) NOT NULL UNIQUE,
    description TEXT,
    release_id BIGINT NULL,
    version_number INT DEFAULT 1,
    is_current BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    FOREIGN KEY (release_id) REFERENCES releases(id),
    INDEX idx_name (name),
    INDEX idx_release_id (release_id),
    INDEX idx_version_current (version_number, is_current)
);

CREATE TABLE norm (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    version VARCHAR(255),
    year INT,
    release_id BIGINT NULL,
    version_number INT DEFAULT 1,
    is_current BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    FOREIGN KEY (release_id) REFERENCES releases(id),
    INDEX idx_name_version (name, version),
    INDEX idx_year (year),
    INDEX idx_release_id (release_id),
    INDEX idx_version_current (version_number, is_current)
);

CREATE TABLE usecase (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(255) NOT NULL UNIQUE,
    release_id BIGINT NULL,
    version_number INT DEFAULT 1,
    is_current BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    FOREIGN KEY (release_id) REFERENCES releases(id),
    INDEX idx_name (name),
    INDEX idx_release_id (release_id),
    INDEX idx_version_current (version_number, is_current)
);

CREATE TABLE requirement (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    shortreq VARCHAR(512) NOT NULL,
    description TEXT,
    language VARCHAR(255) NULL,
    example TEXT NULL,
    motivation TEXT NULL,
    usecase TEXT NULL,
    norm TEXT NULL,
    chapter TEXT NULL,
    release_id BIGINT NULL,
    version_number INT DEFAULT 1,
    is_current BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    FOREIGN KEY (release_id) REFERENCES releases(id),
    INDEX idx_shortreq (shortreq(100)),
    INDEX idx_language (language),
    INDEX idx_release_id (release_id),
    INDEX idx_version_current (version_number, is_current)
);

-- Asset and Risk Management
CREATE TABLE asset (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    type VARCHAR(100) NOT NULL,
    ip VARCHAR(45) NULL,
    owner VARCHAR(255) NOT NULL,
    description VARCHAR(1024) NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_name (name),
    INDEX idx_type (type),
    INDEX idx_owner (owner),
    INDEX idx_ip (ip)
);

CREATE TABLE risk (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    description VARCHAR(1024) NOT NULL,
    likelihood INT NOT NULL,
    impact INT NOT NULL,
    risk_level INT NOT NULL,
    asset_id BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'OPEN',
    owner VARCHAR(255) NULL,
    deadline DATE NULL,
    severity VARCHAR(20) NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    FOREIGN KEY (asset_id) REFERENCES asset(id) ON DELETE CASCADE,
    INDEX idx_name (name),
    INDEX idx_asset_id (asset_id),
    INDEX idx_status (status),
    INDEX idx_risk_level (risk_level)
);

CREATE TABLE risk_assessment (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    asset_id BIGINT NOT NULL,
    start_date DATE NOT NULL,
    end_date DATE NOT NULL,
    status VARCHAR(50) DEFAULT 'STARTED',
    notes VARCHAR(1024) NULL,
    requestor_id BIGINT,
    respondent_id BIGINT,
    assessor_id BIGINT,
    release_id BIGINT NULL,
    release_locked_at TIMESTAMP NULL,
    is_release_locked BOOLEAN DEFAULT FALSE,
    content_snapshot_taken BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    FOREIGN KEY (asset_id) REFERENCES asset(id) ON DELETE CASCADE,
    FOREIGN KEY (requestor_id) REFERENCES users(id),
    FOREIGN KEY (respondent_id) REFERENCES users(id),
    FOREIGN KEY (assessor_id) REFERENCES users(id),
    FOREIGN KEY (release_id) REFERENCES releases(id),
    INDEX idx_asset_id (asset_id),
    INDEX idx_status (status),
    INDEX idx_release_locked (is_release_locked)
);

-- File Management
CREATE TABLE requirement_files (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    filename VARCHAR(255) NOT NULL,
    original_filename VARCHAR(255) NOT NULL,
    file_path VARCHAR(500) NOT NULL,
    file_size BIGINT NOT NULL,
    content_type VARCHAR(100) NOT NULL,
    uploaded_by BIGINT NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    FOREIGN KEY (uploaded_by) REFERENCES users(id),
    INDEX idx_filename (filename),
    INDEX idx_uploaded_by (uploaded_by)
);

-- Email and Response System
CREATE TABLE email_config (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    smtp_host VARCHAR(255) NOT NULL,
    smtp_port INT NOT NULL DEFAULT 587,
    smtp_username VARCHAR(255) NULL,
    smtp_password VARCHAR(255) NULL,
    smtp_tls BOOLEAN NOT NULL DEFAULT TRUE,
    smtp_ssl BOOLEAN NOT NULL DEFAULT FALSE,
    from_email VARCHAR(255) NOT NULL,
    from_name VARCHAR(255) NOT NULL,
    is_active BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_is_active (is_active)
);

CREATE TABLE assessment_token (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    risk_assessment_id BIGINT NOT NULL,
    token VARCHAR(255) NOT NULL UNIQUE,
    respondent_email VARCHAR(255) NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    used_at TIMESTAMP NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (risk_assessment_id) REFERENCES risk_assessment(id) ON DELETE CASCADE,
    INDEX idx_token (token),
    INDEX idx_risk_assessment_id (risk_assessment_id),
    INDEX idx_expires_at (expires_at)
);

CREATE TABLE response (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    risk_assessment_id BIGINT NOT NULL,
    requirement_id BIGINT NOT NULL,
    respondent_email VARCHAR(255) NOT NULL,
    answer ENUM('YES', 'NO', 'N_A') NOT NULL,
    comment TEXT,
    requirement_version_hash VARCHAR(64) NULL,
    response_valid_for_release BIGINT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    FOREIGN KEY (risk_assessment_id) REFERENCES risk_assessment(id) ON DELETE CASCADE,
    FOREIGN KEY (requirement_id) REFERENCES requirement(id) ON DELETE CASCADE,
    FOREIGN KEY (response_valid_for_release) REFERENCES releases(id),
    UNIQUE KEY unique_response (risk_assessment_id, requirement_id, respondent_email),
    INDEX idx_risk_assessment_id (risk_assessment_id),
    INDEX idx_requirement_id (requirement_id),
    INDEX idx_response_release (response_valid_for_release)
);

-- Translation Configuration
CREATE TABLE translation_config (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    api_key VARCHAR(512) NOT NULL,
    base_url VARCHAR(255) NOT NULL DEFAULT 'https://openrouter.ai/api/v1',
    model_name VARCHAR(255) NOT NULL DEFAULT 'anthropic/claude-3-haiku',
    max_tokens INT NOT NULL DEFAULT 4000,
    temperature DOUBLE NOT NULL DEFAULT 0.3,
    is_active BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_is_active (is_active)
);

-- Relationship Tables
CREATE TABLE requirement_standard (
    requirement_id BIGINT NOT NULL,
    standard_id BIGINT NOT NULL,
    PRIMARY KEY (requirement_id, standard_id),
    FOREIGN KEY (requirement_id) REFERENCES requirement(id) ON DELETE CASCADE,
    FOREIGN KEY (standard_id) REFERENCES standard(id) ON DELETE CASCADE,
    INDEX idx_standard_id (standard_id)
);

CREATE TABLE requirement_norm (
    requirement_id BIGINT NOT NULL,
    norm_id BIGINT NOT NULL,
    PRIMARY KEY (requirement_id, norm_id),
    FOREIGN KEY (requirement_id) REFERENCES requirement(id) ON DELETE CASCADE,
    FOREIGN KEY (norm_id) REFERENCES norm(id) ON DELETE CASCADE,
    INDEX idx_norm_id (norm_id)
);

CREATE TABLE requirement_usecase (
    requirement_id BIGINT NOT NULL,
    usecase_id BIGINT NOT NULL,
    PRIMARY KEY (requirement_id, usecase_id),
    FOREIGN KEY (requirement_id) REFERENCES requirement(id) ON DELETE CASCADE,
    FOREIGN KEY (usecase_id) REFERENCES usecase(id) ON DELETE CASCADE,
    INDEX idx_usecase_id (usecase_id)
);

CREATE TABLE standard_usecase (
    standard_id BIGINT NOT NULL,
    usecase_id BIGINT NOT NULL,
    PRIMARY KEY (standard_id, usecase_id),
    FOREIGN KEY (standard_id) REFERENCES standard(id) ON DELETE CASCADE,
    FOREIGN KEY (usecase_id) REFERENCES usecase(id) ON DELETE CASCADE,
    INDEX idx_usecase_id (usecase_id)
);

CREATE TABLE risk_assessment_usecase (
    risk_assessment_id BIGINT NOT NULL,
    usecase_id BIGINT NOT NULL,
    PRIMARY KEY (risk_assessment_id, usecase_id),
    FOREIGN KEY (risk_assessment_id) REFERENCES risk_assessment(id) ON DELETE CASCADE,
    FOREIGN KEY (usecase_id) REFERENCES usecase(id) ON DELETE CASCADE,
    INDEX idx_usecase_id (usecase_id)
);

CREATE TABLE risk_assessment_requirement_files (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    risk_assessment_id BIGINT NOT NULL,
    requirement_id BIGINT NOT NULL,
    file_id BIGINT NOT NULL,
    uploaded_by BIGINT NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (risk_assessment_id) REFERENCES risk_assessment(id) ON DELETE CASCADE,
    FOREIGN KEY (requirement_id) REFERENCES requirement(id) ON DELETE CASCADE,
    FOREIGN KEY (file_id) REFERENCES requirement_files(id) ON DELETE CASCADE,
    FOREIGN KEY (uploaded_by) REFERENCES users(id),
    UNIQUE KEY unique_assessment_req_file (risk_assessment_id, requirement_id, file_id)
);

-- Version History Tables (Compact)
CREATE TABLE requirements_history (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    original_id BIGINT NOT NULL,
    release_id BIGINT NOT NULL,
    version_number INT NOT NULL,
    shortreq VARCHAR(512) NOT NULL,
    description TEXT,
    archived_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (release_id) REFERENCES releases(id),
    INDEX idx_original_release (original_id, release_id)
);

CREATE TABLE standards_history (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    original_id BIGINT NOT NULL,
    release_id BIGINT NOT NULL,
    version_number INT NOT NULL,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    archived_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (release_id) REFERENCES releases(id),
    INDEX idx_original_release (original_id, release_id)
);

-- Version Tracking for Relationships
CREATE TABLE requirement_usecase_versions (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    requirement_id BIGINT NOT NULL,
    usecase_id BIGINT NOT NULL,
    release_id BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (release_id) REFERENCES releases(id),
    INDEX idx_release_requirement (release_id, requirement_id),
    UNIQUE KEY unique_req_usecase_release (requirement_id, usecase_id, release_id)
);

CREATE TABLE standard_usecase_versions (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    standard_id BIGINT NOT NULL,
    usecase_id BIGINT NOT NULL,
    release_id BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (release_id) REFERENCES releases(id),
    INDEX idx_release_standard (release_id, standard_id),
    UNIQUE KEY unique_std_usecase_release (standard_id, usecase_id, release_id)
);

CREATE TABLE requirement_standard_versions (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    requirement_id BIGINT NOT NULL,
    standard_id BIGINT NOT NULL,
    release_id BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (release_id) REFERENCES releases(id),
    INDEX idx_release_requirement (release_id, requirement_id),
    UNIQUE KEY unique_req_std_release (requirement_id, standard_id, release_id)
);

-- Assessment Snapshots for Release Compatibility
CREATE TABLE assessment_content_snapshots (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    assessment_id BIGINT NOT NULL,
    release_id BIGINT NOT NULL,
    requirements_snapshot JSON NOT NULL,
    standards_snapshot JSON NOT NULL,
    snapshot_created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    snapshot_hash VARCHAR(64) NOT NULL,
    FOREIGN KEY (assessment_id) REFERENCES risk_assessment(id) ON DELETE CASCADE,
    FOREIGN KEY (release_id) REFERENCES releases(id),
    UNIQUE INDEX idx_assessment_snapshot (assessment_id),
    INDEX idx_release_id (release_id)
);

-- Initial Data
INSERT INTO users (id, username, email, password_hash, created_at, updated_at) VALUES 
(1, 'adminuser', 'admin@example.com', '$2a$12$qjfBO6Mr65SiujkTbgg3YudRvsDRE7A.RBvSyLJe89PKbkWhC6sgK', NOW(), NOW()),
(2, 'normaluser', 'user@example.com', '$2a$12$qjfBO6Mr65SiujkTbgg3YudRvsDRE7A.RBvSyLJe89PKbkWhC6sgK', NOW(), NOW());

INSERT INTO user_roles (user_id, role_name) VALUES 
(1, 'ADMIN'), (1, 'USER'), (2, 'USER');

INSERT INTO identity_providers (name, type, button_text, button_color, enabled, auto_provision) VALUES
('Google', 'OIDC', 'Sign in with Google', '#4285f4', FALSE, FALSE),
('Microsoft', 'OIDC', 'Sign in with Microsoft', '#0078d4', FALSE, FALSE),
('GitHub', 'OIDC', 'Sign in with GitHub', '#333333', FALSE, FALSE);

INSERT INTO email_config (smtp_host, smtp_port, from_email, from_name, is_active) 
VALUES ('smtp.gmail.com', 587, 'noreply@yourcompany.com', 'SecMan Risk Assessment', TRUE);

INSERT INTO translation_config (api_key, base_url, model_name, max_tokens, temperature, is_active) 
VALUES ('your-openrouter-api-key-here', 'https://openrouter.ai/api/v1', 'anthropic/claude-3-haiku', 4000, 0.3, FALSE);

INSERT INTO releases (version, name, description, status, release_date, created_by, created_at, updated_at) 
VALUES ('1.0.0', 'Initial Release', 'Initial system release', 'ACTIVE', NOW(), 1, NOW(), NOW());

-- !Downs

-- Drop tables in reverse dependency order
DROP TABLE IF EXISTS assessment_content_snapshots;
DROP TABLE IF EXISTS requirement_standard_versions;
DROP TABLE IF EXISTS standard_usecase_versions;
DROP TABLE IF EXISTS requirement_usecase_versions;
DROP TABLE IF EXISTS standards_history;
DROP TABLE IF EXISTS requirements_history;
DROP TABLE IF EXISTS risk_assessment_requirement_files;
DROP TABLE IF EXISTS risk_assessment_usecase;
DROP TABLE IF EXISTS standard_usecase;
DROP TABLE IF EXISTS requirement_usecase;
DROP TABLE IF EXISTS requirement_norm;
DROP TABLE IF EXISTS requirement_standard;
DROP TABLE IF EXISTS translation_config;
DROP TABLE IF EXISTS response;
DROP TABLE IF EXISTS assessment_token;
DROP TABLE IF EXISTS email_config;
DROP TABLE IF EXISTS requirement_files;
DROP TABLE IF EXISTS risk_assessment;
DROP TABLE IF EXISTS risk;
DROP TABLE IF EXISTS asset;
DROP TABLE IF EXISTS requirement;
DROP TABLE IF EXISTS usecase;
DROP TABLE IF EXISTS norm;
DROP TABLE IF EXISTS standard;
DROP TABLE IF EXISTS releases;
DROP TABLE IF EXISTS auth_audit_log;
DROP TABLE IF EXISTS oauth_states;
DROP TABLE IF EXISTS user_external_identities;
DROP TABLE IF EXISTS identity_providers;
DROP TABLE IF EXISTS user_roles;
DROP TABLE IF EXISTS users;

