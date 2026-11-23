-- Recalculate population-dependent statistics using the new 24-hour observation window.
UPDATE vulnerability_statistics_cache SET valid_until = NULL WHERE valid_until IS NOT NULL;
