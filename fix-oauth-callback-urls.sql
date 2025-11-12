-- Fix OAuth callback URLs - remove localhost references
-- This will make the system use the BACKEND_BASE_URL from configuration

-- Show current callback URLs
SELECT id, name, callback_url, enabled
FROM identity_provider
WHERE enabled = true;

-- Update all identity providers to use NULL callback_url
-- This will make them use the dynamic BACKEND_BASE_URL from config
UPDATE identity_provider
SET callback_url = NULL
WHERE callback_url LIKE '%localhost%';

-- Verify the update
SELECT id, name, callback_url, enabled
FROM identity_provider
WHERE enabled = true;

-- Note: After running this, callback URLs will be dynamically generated as:
-- ${BACKEND_BASE_URL}/oauth/callback
-- where BACKEND_BASE_URL comes from environment or defaults to https://secman.covestro.net
