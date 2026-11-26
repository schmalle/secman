-- CYBERDEF account tickets: vulnerability reports about one AWS account submitted by the
-- cyber-defence team through MCP, tracked by the account's owners. See docs/CYBERDEF_TICKETS.md.
--
-- Users are referenced by id + username snapshot without a foreign key, so the inactive-user
-- cleanup can delete an account without losing ticket history.

CREATE TABLE IF NOT EXISTS cyberdef_ticket (
    id                  BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    ticket_key          VARCHAR(20)  NULL,
    target_type         VARCHAR(16)  NOT NULL,
    aws_account_id      VARCHAR(12)  NOT NULL,
    aws_account_name    VARCHAR(255) NULL,
    title               VARCHAR(200) NOT NULL,
    description         TEXT         NOT NULL,
    recommended_action  TEXT         NULL,
    severity            VARCHAR(10)  NOT NULL,
    status              VARCHAR(20)  NOT NULL,
    cve_ids             TEXT         NULL,
    reference_urls      TEXT         NULL,
    due_at              DATETIME(6)  NULL,
    external_reference  VARCHAR(128) NULL,
    source_system       VARCHAR(64)  NULL,
    created_by_user_id  BIGINT       NOT NULL,
    created_by_username VARCHAR(255) NOT NULL,
    recipient_count     INT          NOT NULL DEFAULT 0,
    created_at          DATETIME(6)  NOT NULL,
    updated_at          DATETIME(6)  NOT NULL,
    acknowledged_at     DATETIME(6)  NULL,
    resolved_at         DATETIME(6)  NULL,
    closed_at           DATETIME(6)  NULL,
    version             BIGINT       NOT NULL DEFAULT 0,
    UNIQUE KEY uk_cyberdef_ticket_key (ticket_key),
    UNIQUE KEY uk_cyberdef_ticket_external_ref (created_by_user_id, external_reference),
    KEY idx_cyberdef_ticket_account_status (aws_account_id, status),
    KEY idx_cyberdef_ticket_status_due (status, due_at),
    KEY idx_cyberdef_ticket_updated (updated_at)
);

CREATE TABLE IF NOT EXISTS cyberdef_ticket_resource (
    id            BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    ticket_id     BIGINT       NOT NULL,
    resource_type VARCHAR(32)  NOT NULL,
    identifier    VARCHAR(512) NOT NULL,
    region        VARCHAR(32)  NULL,
    KEY idx_cyberdef_resource_ticket (ticket_id),
    CONSTRAINT fk_cyberdef_resource_ticket FOREIGN KEY (ticket_id) REFERENCES cyberdef_ticket (id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS cyberdef_ticket_event (
    id             BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    ticket_id      BIGINT       NOT NULL,
    actor_user_id  BIGINT       NULL,
    actor_username VARCHAR(255) NULL,
    actor_kind     VARCHAR(16)  NOT NULL,
    event_type     VARCHAR(24)  NOT NULL,
    from_status    VARCHAR(20)  NULL,
    to_status      VARCHAR(20)  NULL,
    comment        TEXT         NULL,
    created_at     DATETIME(6)  NOT NULL,
    KEY idx_cyberdef_event_ticket (ticket_id, id),
    CONSTRAINT fk_cyberdef_event_ticket FOREIGN KEY (ticket_id) REFERENCES cyberdef_ticket (id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS cyberdef_ticket_recipient (
    id        BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    ticket_id BIGINT       NOT NULL,
    email     VARCHAR(255) NOT NULL,
    user_id   BIGINT       NULL,
    source    VARCHAR(16)  NOT NULL,
    UNIQUE KEY uk_cyberdef_recipient (ticket_id, email),
    CONSTRAINT fk_cyberdef_recipient_ticket FOREIGN KEY (ticket_id) REFERENCES cyberdef_ticket (id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS cyberdef_ticket_notification (
    id              BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    ticket_id       BIGINT       NOT NULL,
    recipient_email VARCHAR(255) NOT NULL,
    kind            VARCHAR(24)  NOT NULL,
    event_id        BIGINT       NULL,
    event_key       VARCHAR(64)  NOT NULL,
    status          VARCHAR(16)  NOT NULL,
    attempts        INT          NOT NULL DEFAULT 0,
    error_code      VARCHAR(64)  NULL,
    created_at      DATETIME(6)  NOT NULL,
    updated_at      DATETIME(6)  NOT NULL,
    sent_at         DATETIME(6)  NULL,
    UNIQUE KEY uk_cyberdef_notification_key (event_key),
    KEY idx_cyberdef_notification_ticket (ticket_id),
    KEY idx_cyberdef_notification_status (status, created_at),
    CONSTRAINT fk_cyberdef_notification_ticket FOREIGN KEY (ticket_id) REFERENCES cyberdef_ticket (id) ON DELETE CASCADE
);
