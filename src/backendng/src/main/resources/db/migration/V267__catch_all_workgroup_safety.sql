ALTER TABLE app_settings
    ADD COLUMN catch_all_workgroup_user_threshold INT NOT NULL DEFAULT 100;

UPDATE workgroup w
SET w.enabled = FALSE
WHERE w.enabled = TRUE
  AND (
      SELECT COUNT(*)
      FROM user_workgroups uw
      WHERE uw.workgroup_id = w.id
  ) >= 100;
