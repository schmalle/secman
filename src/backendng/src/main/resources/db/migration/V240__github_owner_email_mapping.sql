CREATE TABLE github_owner_email_mapping (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    owner VARCHAR(255) NOT NULL,
    email VARCHAR(255) NOT NULL,
    created_by VARCHAR(255) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT uk_ghownermap_owner UNIQUE (owner)
);
