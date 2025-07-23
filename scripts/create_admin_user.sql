-- SQL script to create an admin user
-- Username: admin
-- Password: admin
-- Email: admin@secman.local

-- Insert the admin user
-- Note: This uses a pre-generated BCrypt hash for password "admin"
-- BCrypt hash: $2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy
INSERT INTO users (id, username, email, password_hash, created_at, updated_at)
VALUES (
    1,
    'admin',
    'admin@secman.local',
    '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy',
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
) ON DUPLICATE KEY UPDATE
    password_hash = '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy',
    updated_at = CURRENT_TIMESTAMP;

-- Insert the ADMIN role for this user
INSERT INTO user_roles (user_id, role)
VALUES (1, 'ADMIN')
ON DUPLICATE KEY UPDATE role = 'ADMIN';

-- Also add USER role (as the system expects users to have at least USER role)
INSERT INTO user_roles (user_id, role)
VALUES (1, 'USER')
ON DUPLICATE KEY UPDATE role = 'USER';

-- Verify the user was created (optional - remove these lines for production)
SELECT u.id, u.username, u.email, GROUP_CONCAT(ur.role) as roles
FROM users u
LEFT JOIN user_roles ur ON u.id = ur.user_id
WHERE u.username = 'admin'
GROUP BY u.id, u.username, u.email;