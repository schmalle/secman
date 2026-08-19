-- Product classification: separate installer/setup payloads from deployed software.
--
-- CrowdStrike Discover reports installer payloads as first-class application entities
-- ("Chrome Installer" is separate from "Chrome"), so EOL and vulnerability findings are
-- raised against things that never run. This adds an admin-tunable rule set plus a
-- materialized class on every row a read surface filters.
--
-- Phase-0 measurement against the live tenant (2026-08-19) drove two design choices:
--   1. Rules match PRODUCT IDENTITY, not path. "Chrome Installer" (17910 rows estate-wide)
--      returns no installation path at all, so a path-only design would classify none of it.
--   2. C:\Windows\Installer\*.msi (565594 rows) and C:\ProgramData\Package Cache\ (150812)
--      are where Windows and WiX keep the package OF AN INSTALLED PRODUCT. They are NOT
--      seeded as artifact locations: Splunk Universal Forwarder is 100% the former and runs.

CREATE TABLE IF NOT EXISTS product_classification_rule (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    match_field    VARCHAR(20)  NOT NULL DEFAULT 'PRODUCT_NAME',
    pattern        VARCHAR(512) NOT NULL,
    classification VARCHAR(20)  NOT NULL DEFAULT 'INSTALLER_ARTIFACT',
    priority       INT          NOT NULL DEFAULT 100,
    enabled        BOOLEAN      NOT NULL DEFAULT TRUE,
    description    VARCHAR(512) NULL,
    created_by     VARCHAR(255) NULL,
    created_at     DATETIME(6)  NOT NULL,
    updated_at     DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    KEY idx_prod_class_rule_enabled (enabled, priority)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Materialized class. Default UNKNOWN, which every read filter treats as visible: a row we
-- have not classified must never disappear.
ALTER TABLE installed_product
    ADD COLUMN IF NOT EXISTS product_class VARCHAR(20) NOT NULL DEFAULT 'UNKNOWN';

ALTER TABLE vulnerability
    ADD COLUMN IF NOT EXISTS product_class VARCHAR(20) NOT NULL DEFAULT 'UNKNOWN';

ALTER TABLE eol_finding
    ADD COLUMN IF NOT EXISTS product_class VARCHAR(20) NOT NULL DEFAULT 'UNKNOWN';

-- Deliberately NO index on vulnerability.product_class. The column is ~99.99% one value in
-- this dataset (26 of ~1.3M rows classify as artifacts), so an index on it would never be
-- chosen, while docs/CROWDSTRIKE_IMPORT.md documents that index count on `vulnerability` is
-- load-bearing for import deadlocks under 3 concurrent writers. The predicate rides along as
-- a filter on rows already selected by idx_vulnerability_excepted_sort.

-- Seeded artifact rules. Every pattern below was checked against real installed_product rows;
-- the counts in the descriptions are what it matched on 2026-08-19.
INSERT INTO product_classification_rule
    (match_field, pattern, classification, priority, enabled, description, created_by, created_at, updated_at)
VALUES
    -- Allowlist first (priority 0). These exist because the broad artifact rules below would
    -- otherwise swallow genuinely installed software found in the estate.
    ('PRODUCT_NAME', 'app installer*',        'INSTALLED', 0, TRUE, 'Microsoft Store App Installer is a real product', 'seed', NOW(6), NOW(6)),
    ('PRODUCT_NAME', '*installer service*',   'INSTALLED', 0, TRUE, 'e.g. Veeam Installer Service - a running Windows service', 'seed', NOW(6), NOW(6)),
    ('PRODUCT_NAME', '*installer engine*',    'INSTALLED', 0, TRUE, 'installer engines are deployed components', 'seed', NOW(6), NOW(6)),
    ('PRODUCT_NAME', '*setup service*',       'INSTALLED', 0, TRUE, 'deployed service, not a payload', 'seed', NOW(6), NOW(6)),
    ('PRODUCT_NAME', '*setup engine*',        'INSTALLED', 0, TRUE, 'e.g. Veeam Setup Engine', 'seed', NOW(6), NOW(6)),
    ('PRODUCT_NAME', '*setup sdk*',           'INSTALLED', 0, TRUE, 'e.g. ALI Setup SDK', 'seed', NOW(6), NOW(6)),
    ('PRODUCT_NAME', '*setup wmi provider*',  'INSTALLED', 0, TRUE, 'e.g. Microsoft Visual Studio Setup WMI Provider', 'seed', NOW(6), NOW(6)),
    ('PRODUCT_NAME', '*setup utilities*',     'INSTALLED', 0, TRUE, 'e.g. Zebra Setup Utilities', 'seed', NOW(6), NOW(6)),
    ('PRODUCT_NAME', '*setuptools*',          'INSTALLED', 0, TRUE, 'python-setuptools is a real package with real CVEs', 'seed', NOW(6), NOW(6)),

    -- Artifact rules.
    ('PRODUCT_NAME', '* installer*',          'INSTALLER_ARTIFACT', 100, TRUE, 'Edge Installer (2659), Chrome Installer (227), Web Installer (124), Google Installer (36)', 'seed', NOW(6), NOW(6)),
    ('PRODUCT_NAME', '* setup*',              'INSTALLER_ARTIFACT', 100, TRUE, 'Photon Setup (80), SQL Server Setup Bootstrapper (52)', 'seed', NOW(6), NOW(6)),
    ('PRODUCT_NAME', 'setup',                 'INSTALLER_ARTIFACT', 100, TRUE, 'bare "setup" product name (137)', 'seed', NOW(6), NOW(6)),
    ('PRODUCT_NAME', 'setup.exe*',            'INSTALLER_ARTIFACT', 100, TRUE, 'bare "Setup.exe" product name (5)', 'seed', NOW(6), NOW(6)),

    -- Path rules. Narrow by measurement: *Downloads* matched 5 rows estate-wide and
    -- *ccmcache* 5, so these are correctness rules rather than volume rules.
    ('INSTALL_PATH', '*/downloads/*',           'INSTALLER_ARTIFACT', 200, TRUE, 'payload sitting in a user Downloads folder', 'seed', NOW(6), NOW(6)),
    ('INSTALL_PATH', '*/ccmcache/*',            'INSTALLER_ARTIFACT', 200, TRUE, 'SCCM download cache', 'seed', NOW(6), NOW(6)),
    ('INSTALL_PATH', '*/appdata/local/temp/*',  'INSTALLER_ARTIFACT', 200, TRUE, 'per-user temp directory', 'seed', NOW(6), NOW(6)),
    ('INSTALL_PATH', '*/windows/temp/*',        'INSTALLER_ARTIFACT', 200, TRUE, 'machine temp directory', 'seed', NOW(6), NOW(6)),
    ('INSTALL_PATH', '*/$recycle.bin/*',        'INSTALLER_ARTIFACT', 200, TRUE, 'deleted payload still on disk', 'seed', NOW(6), NOW(6));
