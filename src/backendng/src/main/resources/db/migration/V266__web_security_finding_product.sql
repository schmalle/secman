-- Present web-configuration findings as one understandable product while
-- retaining their stable per-finding projection keys for identity and CVE/Finding exceptions.
UPDATE vulnerability v
JOIN integration_finding f ON f.vulnerability_id = v.id
JOIN integration_subject s ON s.id = f.subject_id
JOIN integration_scanner scanner ON scanner.id = s.scanner_id
SET v.vulnerable_product_versions = 'Webserver'
WHERE scanner.source = 'WEB_SECURITY';

-- Resolved findings have no vulnerability projection. Update the durable value
-- as well so a later reopen recreates the Product cell consistently.
UPDATE integration_finding f
JOIN integration_subject s ON s.id = f.subject_id
JOIN integration_scanner scanner ON scanner.id = s.scanner_id
SET f.projection_product = 'Webserver'
WHERE scanner.source = 'WEB_SECURITY';
