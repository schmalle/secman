-- V251: Generic chat notification support (Slack + Telegram).
--
-- Five tables:
--   slack_config                 workspace-level Slack bot token + default channel (ADMIN, optional singleton row)
--   telegram_config              workspace-level Telegram bot token (ADMIN, optional singleton row)
--   user_slack_settings          per-user Slack destination (personal webhook or channel) + last delivery outcome
--   user_telegram_settings       per-user Telegram destination (chat ID, optional personal bot token) + last outcome
--   user_notification_subscription  one row per (user, channel, event type) the user wants reported
--
-- Subscriptions are rows rather than boolean columns so adding a NotificationEventType —
-- or a whole new NotificationChannel — costs no migration, and "who wants X over Slack"
-- stays a single indexed lookup on the dispatch path. Including the channel in the unique
-- key is what lets one user route CrowdStrike imports to Slack and AWS imports to Telegram.
--
-- Every *_token / webhook_url column holds a credential encrypted by EncryptedStringConverter,
-- hence TEXT (the ciphertext is longer than the plaintext) and hence never NOT NULL: each
-- destination is optional and either one alone is a valid configuration.

CREATE TABLE IF NOT EXISTS slack_config (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    bot_token       TEXT         NULL,
    default_channel VARCHAR(100) NULL,
    enabled         BIT(1)       NOT NULL DEFAULT b'0',
    created_at      DATETIME(6)  NOT NULL,
    updated_at      DATETIME(6)  NOT NULL,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS telegram_config (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    bot_token  TEXT        NULL,
    enabled    BIT(1)      NOT NULL DEFAULT b'0',
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS user_slack_settings (
    id                   BIGINT       NOT NULL AUTO_INCREMENT,
    user_id              BIGINT       NOT NULL,
    enabled              BIT(1)       NOT NULL DEFAULT b'0',
    webhook_url          TEXT         NULL,
    channel              VARCHAR(100) NULL,
    last_notified_at     DATETIME(6)  NULL,
    last_delivery_status VARCHAR(20)  NULL,
    last_delivery_error  VARCHAR(500) NULL,
    created_at           DATETIME(6)  NOT NULL,
    updated_at           DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_user_slack_settings_user UNIQUE (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS user_telegram_settings (
    id                   BIGINT       NOT NULL AUTO_INCREMENT,
    user_id              BIGINT       NOT NULL,
    enabled              BIT(1)       NOT NULL DEFAULT b'0',
    chat_id              VARCHAR(64)  NULL,
    bot_token            TEXT         NULL,
    last_notified_at     DATETIME(6)  NULL,
    last_delivery_status VARCHAR(20)  NULL,
    last_delivery_error  VARCHAR(500) NULL,
    created_at           DATETIME(6)  NOT NULL,
    updated_at           DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_user_telegram_settings_user UNIQUE (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS user_notification_subscription (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    user_id    BIGINT      NOT NULL,
    channel    VARCHAR(32) NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_user_notification_subscription UNIQUE (user_id, channel, event_type),
    INDEX idx_user_notification_subscription_lookup (channel, event_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
