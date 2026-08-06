-- Feature 185: Requirements Alignment Process
-- Creates tables for alignment sessions, reviewers, reviews, and snapshots

-- Alignment Session table
-- Represents an active alignment process for a release
CREATE TABLE IF NOT EXISTS alignment_session (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    release_id BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'OPEN',
    initiated_by BIGINT NOT NULL,
    baseline_release_id BIGINT,
    changed_requirements_count INT NOT NULL DEFAULT 0,
    started_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMP NULL,
    completion_notes TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    CONSTRAINT fk_alignment_session_release FOREIGN KEY (release_id) REFERENCES releases(id) ON DELETE CASCADE,
    CONSTRAINT fk_alignment_session_initiator FOREIGN KEY (initiated_by) REFERENCES users(id),
    CONSTRAINT fk_alignment_session_baseline FOREIGN KEY (baseline_release_id) REFERENCES releases(id) ON DELETE SET NULL
);

CREATE INDEX idx_alignment_session_release ON alignment_session(release_id);
CREATE INDEX idx_alignment_session_status ON alignment_session(status);

-- Alignment Reviewer table
-- Tracks each reviewer's participation in an alignment session
CREATE TABLE IF NOT EXISTS alignment_reviewer (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    review_token VARCHAR(36) NOT NULL,
    started_at TIMESTAMP NULL,
    completed_at TIMESTAMP NULL,
    reviewed_count INT NOT NULL DEFAULT 0,
    notified_at TIMESTAMP NULL,
    reminder_count INT NOT NULL DEFAULT 0,
    last_reminder_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    CONSTRAINT fk_alignment_reviewer_session FOREIGN KEY (session_id) REFERENCES alignment_session(id) ON DELETE CASCADE,
    CONSTRAINT fk_alignment_reviewer_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT uk_alignment_reviewer_session_user UNIQUE (session_id, user_id),
    CONSTRAINT uk_alignment_reviewer_token UNIQUE (review_token)
);

CREATE INDEX idx_alignment_reviewer_session ON alignment_reviewer(session_id);
CREATE INDEX idx_alignment_reviewer_user ON alignment_reviewer(user_id);
CREATE INDEX idx_alignment_reviewer_token ON alignment_reviewer(review_token);

-- Alignment Snapshot table
-- Captures changed requirements at the moment alignment starts
CREATE TABLE IF NOT EXISTS alignment_snapshot (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id BIGINT NOT NULL,
    requirement_internal_id VARCHAR(20) NOT NULL,
    change_type VARCHAR(10) NOT NULL,
    baseline_snapshot_id BIGINT NULL,
    current_snapshot_id BIGINT NULL,
    shortreq VARCHAR(500) NOT NULL,
    previous_shortreq VARCHAR(500),
    details TEXT,
    previous_details TEXT,
    chapter VARCHAR(50),
    previous_chapter VARCHAR(50),
    version_number INT,
    baseline_version_number INT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_alignment_snapshot_session FOREIGN KEY (session_id) REFERENCES alignment_session(id) ON DELETE CASCADE,
    CONSTRAINT fk_alignment_snapshot_baseline FOREIGN KEY (baseline_snapshot_id) REFERENCES requirement_snapshot(id) ON DELETE SET NULL,
    CONSTRAINT fk_alignment_snapshot_current FOREIGN KEY (current_snapshot_id) REFERENCES requirement_snapshot(id) ON DELETE SET NULL
);

CREATE INDEX idx_alignment_snapshot_session ON alignment_snapshot(session_id);
CREATE INDEX idx_alignment_snapshot_requirement ON alignment_snapshot(requirement_internal_id);
CREATE INDEX idx_alignment_snapshot_change_type ON alignment_snapshot(change_type);

-- Requirement Review table
-- Individual feedback on a requirement change
CREATE TABLE IF NOT EXISTS requirement_review (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id BIGINT NOT NULL,
    reviewer_id BIGINT NOT NULL,
    snapshot_id BIGINT NOT NULL,
    assessment VARCHAR(10) NOT NULL,
    comment TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    CONSTRAINT fk_requirement_review_session FOREIGN KEY (session_id) REFERENCES alignment_session(id) ON DELETE CASCADE,
    CONSTRAINT fk_requirement_review_reviewer FOREIGN KEY (reviewer_id) REFERENCES alignment_reviewer(id) ON DELETE CASCADE,
    CONSTRAINT fk_requirement_review_snapshot FOREIGN KEY (snapshot_id) REFERENCES alignment_snapshot(id) ON DELETE CASCADE,
    CONSTRAINT uk_requirement_review_reviewer_snapshot UNIQUE (reviewer_id, snapshot_id)
);

CREATE INDEX idx_requirement_review_session ON requirement_review(session_id);
CREATE INDEX idx_requirement_review_reviewer ON requirement_review(reviewer_id);
CREATE INDEX idx_requirement_review_snapshot ON requirement_review(snapshot_id);
