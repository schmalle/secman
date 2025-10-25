-- Migration: Add asset_deletion_audit_log table
-- Feature: 033-cascade-asset-deletion
-- Date: 2025-10-25
-- Description: Creates audit log table for tracking asset cascade deletions
-- Note: This table provides permanent audit trail for compliance and debugging

-- Create asset_deletion_audit_log table
CREATE TABLE IF NOT EXISTS asset_deletion_audit_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,

    -- Asset information (preserved after deletion)
    asset_id BIGINT NOT NULL,
    asset_name VARCHAR(255) NOT NULL,

    -- Audit metadata
    deleted_by_user VARCHAR(255) NOT NULL,
    deletion_timestamp DATETIME NOT NULL,

    -- Cascade deletion counts
    vulnerabilities_count INT NOT NULL DEFAULT 0,
    asset_exceptions_count INT NOT NULL DEFAULT 0,
    exception_requests_count INT NOT NULL DEFAULT 0,

    -- Detailed ID tracking (JSON arrays)
    deleted_vulnerability_ids TEXT NOT NULL,
    deleted_exception_ids TEXT NOT NULL,
    deleted_request_ids TEXT NOT NULL,

    -- Operation tracking
    operation_type VARCHAR(20) NOT NULL,
    bulk_operation_id VARCHAR(36) NULL,

    -- Indexes for performance and compliance reporting
    INDEX idx_audit_asset (asset_id),
    INDEX idx_audit_user (deleted_by_user),
    INDEX idx_audit_deletion_timestamp (deletion_timestamp),
    INDEX idx_audit_bulk_op (bulk_operation_id),

    -- Constraints
    CONSTRAINT chk_operation_type CHECK (operation_type IN ('SINGLE', 'BULK'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Add table comment for documentation
ALTER TABLE asset_deletion_audit_log
COMMENT = 'Immutable audit trail for asset cascade deletions (Feature 033). No UPDATE/DELETE allowed.';

-- Verify the table was created
-- SELECT COUNT(*) as audit_log_count FROM asset_deletion_audit_log;
