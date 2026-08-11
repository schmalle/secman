-- V253: AWS account onboarding — welcome mail, direct and guided risk assessments.
--
-- Five tables plus two join tables:
--   account_onboarding_question        a question put to the account owner (GUIDED mode)
--   account_onboarding_choice          one selectable answer; unique per question, not globally
--   account_onboarding_rule            a combination of choices -> the use cases it resolves to
--   account_onboarding_rule_choice     the combination (flat AND over choices)
--   account_onboarding_rule_usecase    what the combination means
--   account_onboarding_invite          the one-time link mailed to the owner
--
-- Why normalized and not a ruleJson TEXT column like demand_classification_rule: that
-- precedent exists because its condition is a recursive tree. This condition is a flat AND
-- over choice ids — no recursion, no operators. JSON would cost referential integrity on the
-- rule -> usecase link (a deleted use case must not leave a rule that silently resolves to
-- nothing), make "which rules reference this choice" un-queryable, and buy nothing.
--
-- Matching is a UNION: every active rule whose choices are all present contributes its use
-- cases, deduplicated, into one assessment. priority_order is display order only and decides
-- nothing. is_default marks the single fallback applied when no other rule matched — the only
-- rule allowed to reference zero choices.

CREATE TABLE IF NOT EXISTS account_onboarding_question (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    question_key  VARCHAR(64)  NOT NULL,
    label         VARCHAR(500) NOT NULL,
    help_text     VARCHAR(1024) NULL,
    input_type    VARCHAR(32)  NOT NULL DEFAULT 'SINGLE_SELECT',
    display_order INT          NOT NULL DEFAULT 0,
    required      BIT(1)       NOT NULL DEFAULT b'1',
    active        BIT(1)       NOT NULL DEFAULT b'1',
    created_at    DATETIME(6)  NOT NULL,
    updated_at    DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_aob_question_key UNIQUE (question_key),
    INDEX idx_aob_question_order (display_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS account_onboarding_choice (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    question_id   BIGINT       NOT NULL,
    choice_key    VARCHAR(64)  NOT NULL,
    label         VARCHAR(500) NOT NULL,
    display_order INT          NOT NULL DEFAULT 0,
    active        BIT(1)       NOT NULL DEFAULT b'1',
    created_at    DATETIME(6)  NOT NULL,
    updated_at    DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_aob_choice_question_key UNIQUE (question_id, choice_key),
    CONSTRAINT fk_aob_choice_question FOREIGN KEY (question_id)
        REFERENCES account_onboarding_question (id) ON DELETE CASCADE,
    INDEX idx_aob_choice_question (question_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS account_onboarding_rule (
    id             BIGINT        NOT NULL AUTO_INCREMENT,
    name           VARCHAR(255)  NOT NULL,
    description    VARCHAR(1024) NULL,
    active         BIT(1)        NOT NULL DEFAULT b'1',
    priority_order INT           NOT NULL DEFAULT 0,
    is_default     BIT(1)        NOT NULL DEFAULT b'0',
    created_by     BIGINT        NULL,
    created_at     DATETIME(6)   NOT NULL,
    updated_at     DATETIME(6)   NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_aob_rule_name UNIQUE (name),
    CONSTRAINT fk_aob_rule_created_by FOREIGN KEY (created_by)
        REFERENCES users (id) ON DELETE SET NULL,
    INDEX idx_aob_rule_active (active),
    INDEX idx_aob_rule_order (priority_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ON DELETE CASCADE on both join tables: removing a choice or a use case removes the edge.
-- The controller still refuses (409) to delete a choice a rule references, so the cascade is
-- a backstop for direct DB surgery, not the normal path.
CREATE TABLE IF NOT EXISTS account_onboarding_rule_choice (
    rule_id   BIGINT NOT NULL,
    choice_id BIGINT NOT NULL,
    PRIMARY KEY (rule_id, choice_id),
    CONSTRAINT fk_aob_rc_rule FOREIGN KEY (rule_id)
        REFERENCES account_onboarding_rule (id) ON DELETE CASCADE,
    CONSTRAINT fk_aob_rc_choice FOREIGN KEY (choice_id)
        REFERENCES account_onboarding_choice (id) ON DELETE CASCADE,
    INDEX idx_aob_rc_choice (choice_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS account_onboarding_rule_usecase (
    rule_id    BIGINT NOT NULL,
    usecase_id BIGINT NOT NULL,
    PRIMARY KEY (rule_id, usecase_id),
    CONSTRAINT fk_aob_ru_rule FOREIGN KEY (rule_id)
        REFERENCES account_onboarding_rule (id) ON DELETE CASCADE,
    CONSTRAINT fk_aob_ru_usecase FOREIGN KEY (usecase_id)
        REFERENCES usecase (id) ON DELETE CASCADE,
    INDEX idx_aob_ru_usecase (usecase_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- The token is a credential: 64 lowercase hex characters = 32 SecureRandom bytes = 256 bits.
-- Single use is enforced by a guarded UPDATE in AccountOnboardingInviteRepository.claim, not
-- by reading status and writing it back, so concurrent submissions cannot both win. The token
-- column is UNIQUE so a collision surfaces as an insert failure rather than silently rebinding
-- someone else's link.
CREATE TABLE IF NOT EXISTS account_onboarding_invite (
    id                 BIGINT        NOT NULL AUTO_INCREMENT,
    token              VARCHAR(64)   NOT NULL,
    aws_account_id     VARCHAR(12)   NOT NULL,
    owner_email        VARCHAR(255)  NOT NULL,
    expires_at         DATETIME(6)   NOT NULL,
    status             VARCHAR(16)   NOT NULL DEFAULT 'PENDING',
    used_at            DATETIME(6)   NULL,
    answers_json       TEXT          NULL,
    resolved_rules     VARCHAR(1024) NULL,
    deadline_days      INT           NOT NULL DEFAULT 7,
    simulated          BIT(1)        NOT NULL DEFAULT b'0',
    reminder_sent_at   DATETIME(6)   NULL,
    risk_assessment_id BIGINT        NULL,
    requestor_id       BIGINT        NULL,
    created_at         DATETIME(6)   NOT NULL,
    updated_at         DATETIME(6)   NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_aob_invite_token UNIQUE (token),
    CONSTRAINT fk_aob_invite_assessment FOREIGN KEY (risk_assessment_id)
        REFERENCES risk_assessment (id) ON DELETE SET NULL,
    CONSTRAINT fk_aob_invite_requestor FOREIGN KEY (requestor_id)
        REFERENCES users (id) ON DELETE SET NULL,
    INDEX idx_aob_invite_lookup (aws_account_id, owner_email, status),
    INDEX idx_aob_invite_expiry (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- A guided assessment is scoped to the UNION of every matching rule's use cases, so the
-- tracking row's use_case_name now holds several joined names rather than one. 255 was
-- comfortable for a single name and is not for a union.
ALTER TABLE aws_account_risk_assessment
    MODIFY COLUMN use_case_name VARCHAR(1024) NOT NULL;
