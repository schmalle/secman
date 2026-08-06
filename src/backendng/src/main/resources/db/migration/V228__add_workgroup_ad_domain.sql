CREATE TABLE IF NOT EXISTS workgroup_ad_domain (
    id              BIGINT       AUTO_INCREMENT PRIMARY KEY,
    workgroup_id    BIGINT       NOT NULL,
    ad_domain       VARCHAR(255) NOT NULL,
    created_by_id   BIGINT       NULL,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP    NULL,
    CONSTRAINT uk_workgroup_ad_domain UNIQUE (workgroup_id, ad_domain),
    CONSTRAINT fk_wg_ad_domain_workgroup
        FOREIGN KEY (workgroup_id) REFERENCES workgroup(id) ON DELETE CASCADE,
    CONSTRAINT fk_wg_ad_domain_created_by
        FOREIGN KEY (created_by_id) REFERENCES users(id) ON DELETE SET NULL,
    INDEX idx_wg_ad_domain_workgroup (workgroup_id),
    INDEX idx_wg_ad_domain (ad_domain)
);
