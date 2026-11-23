ALTER TABLE vulnerability_exception_request ADD COLUMN IF NOT EXISTS product_patterns_json TEXT;
ALTER TABLE vulnerability_exception ADD COLUMN IF NOT EXISTS product_patterns_json TEXT;
