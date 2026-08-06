-- Feature: 078-release-rework
-- Add review_decision table for admin decisions on reviewer assessments

CREATE TABLE review_decision (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    review_id BIGINT NOT NULL,
    session_id BIGINT NOT NULL,
    decision VARCHAR(10) NOT NULL,
    comment LONGTEXT,
    decided_by BIGINT NOT NULL,
    decided_by_username VARCHAR(255) NOT NULL,
    created_at TIMESTAMP(6) NULL,
    updated_at TIMESTAMP(6) NULL,
    CONSTRAINT uk_review_decision_review UNIQUE (review_id),
    CONSTRAINT fk_review_decision_review FOREIGN KEY (review_id) REFERENCES requirement_review(id) ON DELETE CASCADE,
    CONSTRAINT fk_review_decision_session FOREIGN KEY (session_id) REFERENCES alignment_session(id) ON DELETE CASCADE,
    CONSTRAINT fk_review_decision_user FOREIGN KEY (decided_by) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_review_decision_session ON review_decision(session_id);
CREATE INDEX idx_review_decision_review ON review_decision(review_id);
