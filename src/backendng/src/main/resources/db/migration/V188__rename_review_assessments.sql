-- Rename review assessment values: MINOR->OK, MAJOR->CHANGE, NOK->NOGO
-- Column is ENUM type, so we need to change the ENUM definition first
ALTER TABLE requirement_review MODIFY COLUMN assessment VARCHAR(10) NOT NULL;
UPDATE requirement_review SET assessment = 'OK' WHERE assessment = 'MINOR';
UPDATE requirement_review SET assessment = 'CHANGE' WHERE assessment = 'MAJOR';
UPDATE requirement_review SET assessment = 'NOGO' WHERE assessment = 'NOK';
