CREATE TABLE requirement_comment (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    requirement_id BIGINT NULL,
    release_id BIGINT NULL,
    snapshot_id BIGINT NULL,
    requirement_revision INT NULL,
    author_id BIGINT NULL,
    author_name VARCHAR(255) NOT NULL,
    body VARCHAR(2000) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT ck_comment_target CHECK (
        (requirement_id IS NOT NULL) + (release_id IS NOT NULL) + (snapshot_id IS NOT NULL) = 1
    ),
    CONSTRAINT fk_comment_requirement FOREIGN KEY (requirement_id) REFERENCES requirement(id) ON DELETE CASCADE,
    CONSTRAINT fk_comment_release FOREIGN KEY (release_id) REFERENCES releases(id) ON DELETE CASCADE,
    CONSTRAINT fk_comment_snapshot FOREIGN KEY (snapshot_id) REFERENCES requirement_snapshot(id) ON DELETE CASCADE,
    CONSTRAINT fk_comment_author FOREIGN KEY (author_id) REFERENCES users(id) ON DELETE SET NULL,
    INDEX idx_comment_requirement (requirement_id, created_at, id),
    INDEX idx_comment_release (release_id, created_at, id),
    INDEX idx_comment_snapshot (snapshot_id, created_at, id)
);
