-- Feature: Profile Picture Management
-- Stores a single normalized avatar image per user.
--
-- Kept in a side table rather than a column on `users` on purpose: `users` is read on every
-- authenticated request (findByUsername) and Hibernate emits SELECT * for entity loads, so a
-- LONGBLOB there would ride along on every one of those. @Basic(fetch = LAZY) does NOT defer a
-- @Lob without Hibernate bytecode enhancement, which this build does not enable.
--
-- The bytes stored here are always re-encoded by ProfilePictureService (never the raw upload).

CREATE TABLE user_profile_picture (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    content_type VARCHAR(64) NOT NULL,
    file_size_bytes BIGINT NOT NULL,
    width INT NOT NULL,
    height INT NOT NULL,
    sha256 CHAR(64) NOT NULL,
    original_filename VARCHAR(255) NULL,
    content LONGBLOB NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    -- Deliberately no ON UPDATE CURRENT_TIMESTAMP: JPA owns updated_at, and a DB-side trigger
    -- would diverge from the value the same transaction returns for cache-busting.
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_user_profile_picture_user UNIQUE (user_id),
    CONSTRAINT fk_user_profile_picture_user FOREIGN KEY (user_id)
        REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB;
