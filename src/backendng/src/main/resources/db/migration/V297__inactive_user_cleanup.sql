-- Inactive user cleanup (docs/USER_CLEANUP.md).

-- When the deletion warning went out. A warning only counts while it is newer than the
-- user's last activity, so a login invalidates it without any reset.
ALTER TABLE users ADD COLUMN IF NOT EXISTS inactivity_warning_sent_at DATETIME(6) NULL;
CREATE INDEX IF NOT EXISTS idx_user_last_login ON users (last_login);

-- The job's triggering user may be deleted; keep the job as history and detach the actor.
-- Until now this NOT NULL FK made every deletion of such a user fail with MariaDB 1451.
ALTER TABLE ai_suggestion_job MODIFY triggered_by_user_id BIGINT NULL;

-- Passkey logins never set last_login before this release. Back-fill it from the passkeys'
-- own last use so passkey-only users do not look a year inactive.
UPDATE users u
JOIN (SELECT user_id, MAX(last_used_at) AS last_used
      FROM passkey_credentials
      GROUP BY user_id) p ON p.user_id = u.id
SET u.last_login = p.last_used
WHERE p.last_used IS NOT NULL
  AND (u.last_login IS NULL OR u.last_login < p.last_used);

CREATE TABLE IF NOT EXISTS user_cleanup_run (
    id              BIGINT       AUTO_INCREMENT PRIMARY KEY,
    triggered_by    VARCHAR(255) NOT NULL,
    started_at      DATETIME(6)  NOT NULL,
    finished_at     DATETIME(6)  NULL,
    status          VARCHAR(32)  NOT NULL,
    dry_run         BOOLEAN      NOT NULL,
    inactive_days   INT          NOT NULL,
    candidate_count INT          NOT NULL DEFAULT 0,
    deleted_count   INT          NOT NULL DEFAULT 0,
    skipped_count   INT          NOT NULL DEFAULT 0,
    warned_count    INT          NOT NULL DEFAULT 0,
    error_count     INT          NOT NULL DEFAULT 0,
    summary_json    TEXT         NULL,
    INDEX idx_user_cleanup_run_started (started_at)
);

CREATE TABLE IF NOT EXISTS user_cleanup_run_item (
    id               BIGINT       AUTO_INCREMENT PRIMARY KEY,
    run_id           BIGINT       NOT NULL,
    user_id          BIGINT       NOT NULL,
    username         VARCHAR(255) NOT NULL,
    email            VARCHAR(255) NOT NULL,
    last_activity_at DATETIME(6)  NULL,
    outcome          VARCHAR(32)  NOT NULL,
    reason           VARCHAR(64)  NULL,
    details_json     TEXT         NULL,
    INDEX idx_user_cleanup_item_run_outcome (run_id, outcome),
    CONSTRAINT fk_user_cleanup_item_run FOREIGN KEY (run_id)
        REFERENCES user_cleanup_run (id) ON DELETE CASCADE
);
