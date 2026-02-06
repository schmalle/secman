-- Rename release statuses: 078-release-rework
UPDATE releases SET status = 'PREPARATION' WHERE status = 'DRAFT';
UPDATE releases SET status = 'ALIGNMENT' WHERE status = 'IN_REVIEW';
UPDATE releases SET status = 'ARCHIVED' WHERE status IN ('LEGACY', 'PUBLISHED');
