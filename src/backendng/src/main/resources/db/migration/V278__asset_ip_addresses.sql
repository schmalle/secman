CREATE TABLE asset_ip_address (
    asset_id BIGINT NOT NULL,
    ip_address VARCHAR(45) NOT NULL,
    PRIMARY KEY (asset_id, ip_address),
    CONSTRAINT fk_asset_ip_address_asset
        FOREIGN KEY (asset_id) REFERENCES asset(id) ON DELETE CASCADE
);

CREATE INDEX idx_asset_ip_address_address ON asset_ip_address(ip_address);

INSERT INTO asset_ip_address (asset_id, ip_address)
SELECT id, TRIM(ip)
FROM asset
WHERE ip IS NOT NULL AND TRIM(ip) <> '';
