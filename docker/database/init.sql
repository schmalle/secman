-- Secman Docker Database Initialization
-- This runs automatically on first container start via MariaDB entrypoint.
-- The MYSQL_DATABASE, MYSQL_USER, MYSQL_PASSWORD env vars already create the DB and user.
-- This script grants the additional DDL privileges needed by Hibernate and Flyway.

-- Grant DDL privileges for Hibernate auto-update and Flyway migrations
GRANT CREATE, ALTER, DROP, INDEX, REFERENCES
    ON secman.* TO 'secman'@'%';

-- Also allow connections from any host (Docker networking)
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, DROP, INDEX, REFERENCES
    ON secman.* TO 'secman'@'%';

FLUSH PRIVILEGES;
