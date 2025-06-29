-- !Ups

-- Create all tables with final schema to avoid multiple ALTERs

-- Users and authentication tables
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

-- Requirements and standards management
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
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    
    INDEX idx_shortreq (shortreq(100)),
    INDEX idx_language (language)
);

CREATE TABLE standard (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(255) NOT NULL UNIQUE,
    description TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    
    INDEX idx_name (name)
);

CREATE TABLE norm (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    version VARCHAR(255),
    year INT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    
    INDEX idx_name_version (name, version),
    INDEX idx_year (year)
);

CREATE TABLE usecase (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(255) NOT NULL UNIQUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    
    INDEX idx_name (name)
);

-- Asset and risk management
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
    INDEX idx_risk_level (risk_level),
    INDEX idx_deadline (deadline),
    INDEX idx_severity (severity)
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
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    
    FOREIGN KEY (asset_id) REFERENCES asset(id) ON DELETE CASCADE,
    FOREIGN KEY (requestor_id) REFERENCES users(id),
    FOREIGN KEY (respondent_id) REFERENCES users(id),
    FOREIGN KEY (assessor_id) REFERENCES users(id),
    
    INDEX idx_asset_id (asset_id),
    INDEX idx_status (status),
    INDEX idx_start_date (start_date),
    INDEX idx_end_date (end_date),
    INDEX idx_requestor_id (requestor_id),
    INDEX idx_respondent_id (respondent_id),
    INDEX idx_assessor_id (assessor_id)
);

-- Junction tables for many-to-many relationships
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

-- Insert default users
-- Default password ('password') BCrypt hash
INSERT INTO users (id, username, email, password_hash, created_at, updated_at) VALUES 
(1, 'adminuser', 'admin@example.com', '$2a$12$qjfBO6Mr65SiujkTbgg3YudRvsDRE7A.RBvSyLJe89PKbkWhC6sgK', NOW(), NOW()),
(2, 'normaluser', 'user@example.com', '$2a$12$qjfBO6Mr65SiujkTbgg3YudRvsDRE7A.RBvSyLJe89PKbkWhC6sgK', NOW(), NOW());

-- Assign roles
INSERT INTO user_roles (user_id, role_name) VALUES 
(1, 'ADMIN'),
(1, 'USER'),
(2, 'USER');

-- Email configuration and response system tables
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
    
    INDEX idx_is_active (is_active),
    INDEX idx_smtp_host (smtp_host)
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
    INDEX idx_respondent_email (respondent_email),
    INDEX idx_expires_at (expires_at),
    INDEX idx_used_at (used_at)
);

CREATE TABLE response (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    risk_assessment_id BIGINT NOT NULL,
    requirement_id BIGINT NOT NULL,
    respondent_email VARCHAR(255) NOT NULL,
    answer ENUM('YES', 'NO', 'N_A') NOT NULL,
    comment TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    
    FOREIGN KEY (risk_assessment_id) REFERENCES risk_assessment(id) ON DELETE CASCADE,
    FOREIGN KEY (requirement_id) REFERENCES requirement(id) ON DELETE CASCADE,
    
    UNIQUE KEY unique_response (risk_assessment_id, requirement_id, respondent_email),
    INDEX idx_risk_assessment_id (risk_assessment_id),
    INDEX idx_requirement_id (requirement_id),
    INDEX idx_respondent_email (respondent_email),
    INDEX idx_answer (answer)
);

-- Insert default email configuration
INSERT INTO email_config (smtp_host, smtp_port, from_email, from_name, is_active) 
VALUES ('smtp.gmail.com', 587, 'noreply@yourcompany.com', 'SecMan Risk Assessment', TRUE);

-- Add an index for faster token lookups and cleanup
CREATE INDEX idx_assessment_token_expires_used 
ON assessment_token (expires_at, used_at);

-- Add translation configuration table for OpenRouter API integration
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
    
    INDEX idx_is_active (is_active),
    INDEX idx_model_name (model_name)
);

-- Insert translation configuration for OpenRouter API integration
INSERT INTO translation_config (api_key, base_url, model_name, max_tokens, temperature, is_active) 
VALUES ('your-openrouter-api-key-here', 'https://openrouter.ai/api/v1', 'anthropic/claude-3-haiku', 4000, 0.3, FALSE);

-- Version Management System
-- Create releases table for version management
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
    INDEX idx_release_date (release_date),
    INDEX idx_created_by (created_by)
);

-- Add versioning fields to existing tables
-- Requirements versioning
ALTER TABLE requirement 
ADD COLUMN release_id BIGINT NULL,
ADD COLUMN version_number INT DEFAULT 1,
ADD COLUMN is_current BOOLEAN DEFAULT TRUE,
ADD FOREIGN KEY (release_id) REFERENCES releases(id),
ADD INDEX idx_release_id (release_id),
ADD INDEX idx_version_current (version_number, is_current);

-- Standards versioning
ALTER TABLE standard 
ADD COLUMN release_id BIGINT NULL,
ADD COLUMN version_number INT DEFAULT 1,
ADD COLUMN is_current BOOLEAN DEFAULT TRUE,
ADD FOREIGN KEY (release_id) REFERENCES releases(id),
ADD INDEX idx_release_id (release_id),
ADD INDEX idx_version_current (version_number, is_current);

-- Norms versioning
ALTER TABLE norm 
ADD COLUMN release_id BIGINT NULL,
ADD COLUMN version_number INT DEFAULT 1,
ADD COLUMN is_current BOOLEAN DEFAULT TRUE,
ADD FOREIGN KEY (release_id) REFERENCES releases(id),
ADD INDEX idx_release_id (release_id),
ADD INDEX idx_version_current (version_number, is_current);

-- Use cases versioning
ALTER TABLE usecase 
ADD COLUMN release_id BIGINT NULL,
ADD COLUMN version_number INT DEFAULT 1,
ADD COLUMN is_current BOOLEAN DEFAULT TRUE,
ADD FOREIGN KEY (release_id) REFERENCES releases(id),
ADD INDEX idx_release_id (release_id),
ADD INDEX idx_version_current (version_number, is_current);

-- Risk assessments release locking
ALTER TABLE risk_assessment 
ADD COLUMN release_id BIGINT NULL,
ADD COLUMN release_locked_at TIMESTAMP NULL,
ADD COLUMN is_release_locked BOOLEAN DEFAULT FALSE,
ADD COLUMN content_snapshot_taken BOOLEAN DEFAULT FALSE,
ADD FOREIGN KEY (release_id) REFERENCES releases(id),
ADD INDEX idx_release_id (release_id),
ADD INDEX idx_release_locked (is_release_locked);

-- Create history tables for versioned entities
CREATE TABLE requirements_history (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    original_id BIGINT NOT NULL,
    release_id BIGINT NOT NULL,
    version_number INT NOT NULL,
    shortreq VARCHAR(512) NOT NULL,
    description TEXT,
    language VARCHAR(255) NULL,
    example TEXT NULL,
    motivation TEXT NULL,
    usecase TEXT NULL,
    norm TEXT NULL,
    chapter TEXT NULL,
    created_at TIMESTAMP NOT NULL,
    archived_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    
    FOREIGN KEY (release_id) REFERENCES releases(id),
    
    INDEX idx_original_release (original_id, release_id),
    INDEX idx_version (version_number),
    INDEX idx_archived_at (archived_at)
);

CREATE TABLE standards_history (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    original_id BIGINT NOT NULL,
    release_id BIGINT NOT NULL,
    version_number INT NOT NULL,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    created_at TIMESTAMP NOT NULL,
    archived_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    
    FOREIGN KEY (release_id) REFERENCES releases(id),
    
    INDEX idx_original_release (original_id, release_id),
    INDEX idx_version (version_number),
    INDEX idx_archived_at (archived_at)
);

CREATE TABLE norms_history (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    original_id BIGINT NOT NULL,
    release_id BIGINT NOT NULL,
    version_number INT NOT NULL,
    name VARCHAR(255) NOT NULL,
    version VARCHAR(255),
    year INT,
    created_at TIMESTAMP NOT NULL,
    archived_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    
    FOREIGN KEY (release_id) REFERENCES releases(id),
    
    INDEX idx_original_release (original_id, release_id),
    INDEX idx_version (version_number),
    INDEX idx_archived_at (archived_at)
);

CREATE TABLE usecases_history (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    original_id BIGINT NOT NULL,
    release_id BIGINT NOT NULL,
    version_number INT NOT NULL,
    name VARCHAR(255) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    archived_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    
    FOREIGN KEY (release_id) REFERENCES releases(id),
    
    INDEX idx_original_release (original_id, release_id),
    INDEX idx_version (version_number),
    INDEX idx_archived_at (archived_at)
);

-- Versioned junction tables
CREATE TABLE requirement_usecase_versions (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    requirement_id BIGINT NOT NULL,
    usecase_id BIGINT NOT NULL,
    release_id BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    
    FOREIGN KEY (release_id) REFERENCES releases(id),
    
    INDEX idx_release_requirement (release_id, requirement_id),
    INDEX idx_release_usecase (release_id, usecase_id),
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
    INDEX idx_release_usecase (release_id, usecase_id),
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
    INDEX idx_release_standard (release_id, standard_id),
    UNIQUE KEY unique_req_std_release (requirement_id, standard_id, release_id)
);

-- Assessment content snapshots for backwards compatibility
CREATE TABLE assessment_content_snapshots (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    assessment_id BIGINT NOT NULL,
    release_id BIGINT NOT NULL,
    requirements_snapshot JSON NOT NULL,
    standards_snapshot JSON NOT NULL,
    norms_snapshot JSON NOT NULL,
    usecases_snapshot JSON NOT NULL,
    snapshot_created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    snapshot_hash VARCHAR(64) NOT NULL,
    
    FOREIGN KEY (assessment_id) REFERENCES risk_assessment(id) ON DELETE CASCADE,
    FOREIGN KEY (release_id) REFERENCES releases(id),
    
    UNIQUE INDEX idx_assessment_snapshot (assessment_id),
    INDEX idx_release_id (release_id),
    INDEX idx_snapshot_hash (snapshot_hash)
);

-- Standard requirement change tracking
CREATE TABLE standard_requirement_changes (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    standard_id BIGINT NOT NULL,
    from_release_id BIGINT NOT NULL,
    to_release_id BIGINT NOT NULL,
    requirement_id BIGINT NOT NULL,
    change_type VARCHAR(20) NOT NULL,
    change_description TEXT,
    change_detected_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    
    FOREIGN KEY (standard_id) REFERENCES standard(id),
    FOREIGN KEY (from_release_id) REFERENCES releases(id),
    FOREIGN KEY (to_release_id) REFERENCES releases(id),
    FOREIGN KEY (requirement_id) REFERENCES requirement(id),
    
    INDEX idx_standard_releases (standard_id, from_release_id, to_release_id),
    INDEX idx_change_type (change_type),
    INDEX idx_change_detected (change_detected_at)
);

-- Response versioning for assessments
ALTER TABLE response 
ADD COLUMN requirement_version_hash VARCHAR(64) NULL,
ADD COLUMN response_valid_for_release BIGINT NULL,
ADD FOREIGN KEY (response_valid_for_release) REFERENCES releases(id),
ADD INDEX idx_response_release (response_valid_for_release);

-- Create initial release (v1.0.0) for existing data
INSERT INTO releases (version, name, description, status, release_date, created_by, created_at, updated_at) 
VALUES ('1.0.0', 'Initial Release', 'Initial system release with existing data', 'ACTIVE', NOW(), 1, NOW(), NOW());

-- Link existing data to initial release
SET @initial_release_id = LAST_INSERT_ID();

UPDATE requirement SET release_id = @initial_release_id WHERE release_id IS NULL;
UPDATE standard SET release_id = @initial_release_id WHERE release_id IS NULL;
UPDATE norm SET release_id = @initial_release_id WHERE release_id IS NULL;
UPDATE usecase SET release_id = @initial_release_id WHERE release_id IS NULL;

-- Migrate existing junction table data to versioned tables
INSERT INTO requirement_usecase_versions (requirement_id, usecase_id, release_id, created_at)
SELECT ru.requirement_id, ru.usecase_id, @initial_release_id, NOW()
FROM requirement_usecase ru;

INSERT INTO standard_usecase_versions (standard_id, usecase_id, release_id, created_at)
SELECT su.standard_id, su.usecase_id, @initial_release_id, NOW()
FROM standard_usecase su;

INSERT INTO requirement_standard_versions (requirement_id, standard_id, release_id, created_at)
SELECT rs.requirement_id, rs.standard_id, @initial_release_id, NOW()
FROM requirement_standard rs;


-- !Downs

-- Drop versioned tables in correct order
DROP TABLE IF EXISTS standard_requirement_changes;
DROP TABLE IF EXISTS assessment_content_snapshots;
DROP TABLE IF EXISTS requirement_standard_versions;
DROP TABLE IF EXISTS standard_usecase_versions;
DROP TABLE IF EXISTS requirement_usecase_versions;
DROP TABLE IF EXISTS usecases_history;
DROP TABLE IF EXISTS norms_history;
DROP TABLE IF EXISTS standards_history;
DROP TABLE IF EXISTS requirements_history;

-- Remove versioning columns from existing tables
ALTER TABLE response 
DROP FOREIGN KEY response_ibfk_3,
DROP COLUMN requirement_version_hash,
DROP COLUMN response_valid_for_release;

ALTER TABLE risk_assessment 
DROP FOREIGN KEY risk_assessment_ibfk_4,
DROP COLUMN release_id,
DROP COLUMN release_locked_at,
DROP COLUMN is_release_locked,
DROP COLUMN content_snapshot_taken;

ALTER TABLE usecase 
DROP FOREIGN KEY usecase_ibfk_1,
DROP COLUMN release_id,
DROP COLUMN version_number,
DROP COLUMN is_current;

ALTER TABLE norm 
DROP FOREIGN KEY norm_ibfk_1,
DROP COLUMN release_id,
DROP COLUMN version_number,
DROP COLUMN is_current;

ALTER TABLE standard 
DROP FOREIGN KEY standard_ibfk_1,
DROP COLUMN release_id,
DROP COLUMN version_number,
DROP COLUMN is_current;

ALTER TABLE requirement 
DROP FOREIGN KEY requirement_ibfk_1,
DROP COLUMN release_id,
DROP COLUMN version_number,
DROP COLUMN is_current;

-- Drop releases table
DROP TABLE IF EXISTS releases;

-- Drop tables in correct order to respect foreign key constraints
DROP TABLE IF EXISTS risk_assessment_usecase;
DROP TABLE IF EXISTS standard_usecase;
DROP TABLE IF EXISTS requirement_usecase;
DROP TABLE IF EXISTS requirement_norm;
DROP TABLE IF EXISTS requirement_standard;
DROP TABLE IF EXISTS risk_assessment;
DROP TABLE IF EXISTS risk;
DROP TABLE IF EXISTS asset;
DROP TABLE IF EXISTS usecase;
DROP TABLE IF EXISTS norm;
DROP TABLE IF EXISTS standard;
DROP TABLE IF EXISTS requirement;
DROP TABLE IF EXISTS user_roles;
DROP TABLE IF EXISTS users;
DROP TABLE IF EXISTS response;
DROP TABLE IF EXISTS assessment_token;
DROP TABLE IF EXISTS email_config;
DROP TABLE IF EXISTS translation_config;

