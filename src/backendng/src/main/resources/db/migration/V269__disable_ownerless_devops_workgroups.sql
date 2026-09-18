UPDATE workgroup
SET enabled = FALSE
WHERE BINARY name LIKE 'aws-DevOps-%'
  AND NULLIF(TRIM(owner_email), '') IS NULL;
