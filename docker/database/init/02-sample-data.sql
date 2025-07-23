-- Sample data for Secman application
-- This script will be executed after the database and user are created

USE secman;

-- Note: Tables will be created automatically by Hibernate when the application starts
-- This script is for sample data only

-- Sample data will be inserted here once the application creates the tables
-- For now, this is a placeholder for future sample data

-- Example of what might be added later:
-- INSERT INTO users (username, email, role, created_at) VALUES 
-- ('adminuser', 'admin@secman.local', 'ADMIN', NOW()),
-- ('normaluser', 'user@secman.local', 'USER', NOW());

-- For now, we'll just create a simple log entry to confirm this script ran
-- (This will only work if a logs table exists)
SELECT 'Sample data initialization script executed' as message;