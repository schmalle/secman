-- A standard that covers the whole requirement corpus rather than a named subset.
--
-- Requirements are otherwise selected through a standard's use cases
-- (standard_usecase -> requirement_usecase). That cannot express "everything": ticking every
-- use case still misses requirements carrying no use case at all, and it freezes the selection
-- at edit time, so anything added later silently falls outside the standard.
--
-- DEFAULT FALSE, so every existing standard keeps resolving exactly as it does today. Setting
-- the flag is a deliberate admin choice; it is the one case where an export legitimately
-- returns the full corpus (see docs/PUBLIC_STANDARD_DOWNLOAD.md).
ALTER TABLE standard
    ADD COLUMN all_requirements BOOLEAN NOT NULL DEFAULT FALSE;
