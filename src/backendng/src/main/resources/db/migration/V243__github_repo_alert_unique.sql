-- Race-condition hardening: concurrent GitHub imports (CLI + UI "Import now")
-- could interleave the per-repo delete+reinsert of Dependabot alerts and leave
-- duplicated (github_repository_id, alert_number) rows. The import now takes a
-- per-repo row lock; this constraint is the database-level backstop.

-- 1) Remove any duplicates already produced by the unguarded window
--    (keep the lowest id per (repo, alert_number)).
DELETE a FROM github_repo_dependabot_alert a
JOIN github_repo_dependabot_alert b
  ON a.github_repository_id = b.github_repository_id
 AND a.alert_number = b.alert_number
 AND a.id > b.id;

-- 2) Enforce uniqueness going forward.
ALTER TABLE github_repo_dependabot_alert
    ADD CONSTRAINT uk_ghalert_repo_alert UNIQUE (github_repository_id, alert_number);
