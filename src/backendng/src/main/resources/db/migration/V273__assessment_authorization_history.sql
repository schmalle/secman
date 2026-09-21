ALTER TABLE risk_assessment ADD COLUMN answer_revision BIGINT NOT NULL DEFAULT 0,
 ADD COLUMN assignment_version BIGINT NOT NULL DEFAULT 0,
 ADD COLUMN authorship_complete BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE assessment_token ADD COLUMN assignment_id BIGINT NULL,
 ADD COLUMN assignment_version BIGINT NOT NULL DEFAULT -1;
CREATE TABLE assessment_assignment (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, assessment_id BIGINT NOT NULL, user_id BIGINT NULL,
 email VARCHAR(255) NOT NULL, role VARCHAR(255) NOT NULL, requirement_ids TEXT NOT NULL,
 revoked BOOLEAN NOT NULL DEFAULT FALSE, submitted BOOLEAN NOT NULL DEFAULT FALSE,
 version BIGINT NOT NULL DEFAULT 1, reminder_sent_at DATETIME(6) NULL, created_at DATETIME(6) NOT NULL,
 INDEX idx_assessment_assignment_assessment (assessment_id));
CREATE TABLE assessment_contribution (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, assessment_id BIGINT NOT NULL, requirement_id BIGINT NOT NULL,
 actor_user_id BIGINT NULL, actor_email VARCHAR(255) NOT NULL, initiating_user_id BIGINT NULL,
 assignment_id BIGINT NULL, api_key_id BIGINT NULL,
 source VARCHAR(255) NOT NULL, revision BIGINT NOT NULL, job_id BIGINT NULL,
 created_at DATETIME(6) NOT NULL, INDEX idx_assessment_contribution_assessment (assessment_id));
CREATE TABLE assessment_acceptance (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, assessment_id BIGINT NOT NULL, reviewer_user_id BIGINT NOT NULL,
 answer_revision BIGINT NOT NULL, rationale TEXT NOT NULL, invalidated BOOLEAN NOT NULL DEFAULT FALSE,
 created_at DATETIME(6) NOT NULL, INDEX idx_assessment_acceptance_assessment (assessment_id));
-- Preserve known authors, but never infer that legacy history is complete.
INSERT INTO assessment_contribution (assessment_id, requirement_id, actor_user_id, actor_email,
 initiating_user_id, source, revision, created_at)
 SELECT r.risk_assessment_id, r.requirement_id, u.id, COALESCE(r.respondent_email, ''),
 NULL, 'LEGACY', 0, CURRENT_TIMESTAMP(6)
 FROM response r LEFT JOIN users u ON LOWER(u.email) = LOWER(r.respondent_email);
INSERT INTO assessment_assignment (assessment_id, user_id, email, role, requirement_ids, submitted, created_at)
 SELECT ra.id, u.id, u.email, 'ASSESSOR', '', FALSE, CURRENT_TIMESTAMP(6)
 FROM risk_assessment ra JOIN users u ON u.id = ra.assessor_id;
INSERT INTO assessment_assignment (assessment_id, user_id, email, role, requirement_ids, submitted, created_at)
 SELECT ra.id, u.id, u.email, 'RESPONDENT', '', ra.status <> 'STARTED', CURRENT_TIMESTAMP(6)
 FROM risk_assessment ra JOIN users u ON u.id = ra.respondent_id;
-- Existing capability URLs must be reissued against a reviewed assignment.
