-- Feature: Admin email broadcast
-- Stores HTML broadcast jobs (subject + body) and per-recipient progress.

CREATE TABLE email_broadcast_jobs (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    status VARCHAR(20) NOT NULL,
    subject VARCHAR(255) NOT NULL,
    html_content MEDIUMTEXT NOT NULL,
    total_recipients INT NOT NULL DEFAULT 0,
    sent_count INT NOT NULL DEFAULT 0,
    failed_count INT NOT NULL DEFAULT 0,
    error_message VARCHAR(2000) NULL,
    created_by VARCHAR(100) NOT NULL,
    created_at DATETIME NOT NULL,
    started_at DATETIME NULL,
    completed_at DATETIME NULL,
    INDEX idx_email_broadcast_status (status),
    INDEX idx_email_broadcast_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
