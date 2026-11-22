ALTER TABLE vulnerability ADD COLUMN IF NOT EXISTS crowdstrike_remediation_json LONGTEXT NULL;
