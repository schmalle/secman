-- Feature 051: User Password Change
-- Add auth_source column to track authentication method

ALTER TABLE users
ADD COLUMN auth_source VARCHAR(20) NOT NULL DEFAULT 'LOCAL';

-- Index for filtering users by auth source (admin queries)
CREATE INDEX idx_user_auth_source ON users(auth_source);
