-- Feature 088 — AI-Assisted Risk Assessment Answers.
--
-- Lets ADMIN/SECCHAMPION users trigger an OpenRouter LLM to pre-fill answers
-- to a risk-assessment's compliance questions. Generated answers are written
-- directly to `response` as drafts with `source='AI_GENERATED'`; the human
-- reviews/edits before submitting via the existing finalization endpoint.
--
-- Two new tables track the job lifecycle and per-question suggestions for
-- audit + re-run safety:
--   * ai_suggestion_job    — one row per "start AI pre-fill" action.
--   * ai_answer_suggestion — one row per requirement per run. APPLIED rows
--                            are the live suggestions; SUPERSEDED preserves
--                            history when a re-run replaces them.

CREATE TABLE ai_suggestion_job (
    id                      BIGINT       AUTO_INCREMENT PRIMARY KEY,
    risk_assessment_id      BIGINT       NOT NULL,
    triggered_by_user_id    BIGINT       NOT NULL,
    model                   VARCHAR(128) NOT NULL,
    scope                   VARCHAR(32)  NOT NULL,
    status                  VARCHAR(16)  NOT NULL DEFAULT 'QUEUED',
    total_count             INT          NOT NULL DEFAULT 0,
    completed_count         INT          NOT NULL DEFAULT 0,
    failed_count            INT          NOT NULL DEFAULT 0,
    total_cost_usd          DECIMAL(10,6) NOT NULL DEFAULT 0,
    estimated_cost_usd      DECIMAL(10,6) NULL,
    started_at              DATETIME     NULL,
    finished_at             DATETIME     NULL,
    last_heartbeat_at       DATETIME     NULL,
    error_message           VARCHAR(2048) NULL,
    created_at              DATETIME     NOT NULL,
    INDEX idx_aijob_assessment (risk_assessment_id, status),
    INDEX idx_aijob_status_heartbeat (status, last_heartbeat_at),
    CONSTRAINT fk_aijob_assessment FOREIGN KEY (risk_assessment_id)
        REFERENCES risk_assessment(id) ON DELETE CASCADE,
    CONSTRAINT fk_aijob_user FOREIGN KEY (triggered_by_user_id)
        REFERENCES user(id)
);

CREATE TABLE ai_answer_suggestion (
    id                       BIGINT       AUTO_INCREMENT PRIMARY KEY,
    job_id                   BIGINT       NOT NULL,
    risk_assessment_id       BIGINT       NOT NULL,
    requirement_id           BIGINT       NOT NULL,
    suggested_answer_type    VARCHAR(16)  NOT NULL,
    suggested_comment        TEXT         NULL,
    raw_confidence           DOUBLE       NOT NULL,
    confidence_band          VARCHAR(8)   NOT NULL,
    rationale                TEXT         NULL,
    citations                LONGTEXT     NULL,
    model                    VARCHAR(128) NOT NULL,
    prompt_version           VARCHAR(16)  NOT NULL,
    input_tokens             INT          NULL,
    output_tokens            INT          NULL,
    cost_usd                 DECIMAL(10,6) NULL,
    web_search_used          BOOLEAN      NOT NULL DEFAULT FALSE,
    status                   VARCHAR(16)  NOT NULL,
    error_message            VARCHAR(2048) NULL,
    created_at               DATETIME     NOT NULL,
    superseded_at            DATETIME     NULL,
    INDEX idx_aisug_assessment_req (risk_assessment_id, requirement_id, status),
    INDEX idx_aisug_band (confidence_band, status),
    INDEX idx_aisug_job (job_id),
    CONSTRAINT fk_aisug_job FOREIGN KEY (job_id)
        REFERENCES ai_suggestion_job(id) ON DELETE CASCADE,
    CONSTRAINT fk_aisug_assessment FOREIGN KEY (risk_assessment_id)
        REFERENCES risk_assessment(id) ON DELETE CASCADE,
    CONSTRAINT fk_aisug_requirement FOREIGN KEY (requirement_id)
        REFERENCES requirement(id)
);

-- response.source: provenance of every answer. MANUAL is the only value that
-- existed before this feature; the default + UPDATE below backfills every
-- existing row safely.
ALTER TABLE response
    ADD COLUMN source             VARCHAR(16) NOT NULL DEFAULT 'MANUAL',
    ADD COLUMN ai_suggestion_id   BIGINT      NULL;

ALTER TABLE response
    ADD INDEX idx_response_source (source),
    ADD CONSTRAINT fk_response_ai_suggestion FOREIGN KEY (ai_suggestion_id)
        REFERENCES ai_answer_suggestion(id) ON DELETE SET NULL;

-- Explicit no-op backfill (defensive — DEFAULT 'MANUAL' on ADD COLUMN already
-- did this, but Hibernate sometimes treats new-column defaults differently
-- across MariaDB versions). Cheap; runs once.
UPDATE response SET source = 'MANUAL' WHERE source IS NULL OR source = '';
