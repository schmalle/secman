-- Track the creator of a workgroup so non-privileged users can only delete
-- workgroups they created. Existing rows remain NULL and are admin-managed.
ALTER TABLE workgroup
    ADD COLUMN created_by_id BIGINT NULL;

CREATE INDEX idx_workgroup_created_by ON workgroup(created_by_id);

ALTER TABLE workgroup
    ADD CONSTRAINT fk_workgroup_created_by
        FOREIGN KEY (created_by_id) REFERENCES users(id)
        ON DELETE SET NULL;
