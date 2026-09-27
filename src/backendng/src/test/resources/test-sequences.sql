-- Fresh test schemas need the sequence normally supplied by Flyway/startup migration.
INSERT INTO requirement_id_sequence (id, next_value, updated_at) VALUES (1, 1, CURRENT_TIMESTAMP);
