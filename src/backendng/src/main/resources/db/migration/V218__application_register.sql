CREATE TABLE application_register (
    id BIGINT NOT NULL AUTO_INCREMENT,
    car_id VARCHAR(100) NOT NULL,
    name VARCHAR(255) NOT NULL,
    process_cluster TEXT NULL,
    process_area TEXT NULL,
    criticality VARCHAR(100) NULL,
    operational_status VARCHAR(100) NULL,
    business_owner VARCHAR(255) NOT NULL,
    application_champion TEXT NULL,
    application_manager VARCHAR(255) NOT NULL,
    application_technology TEXT NULL,
    application_architecture TEXT NULL,
    last_quality_check DATE NULL,
    information_classification TEXT NULL,
    processing_of_personal_data TEXT NULL,
    ics_relevant TEXT NULL,
    legal_regulatory TEXT NULL,
    legal_regulatory_rationale_impact TEXT NULL,
    data_export_control_relevant TEXT NULL,
    application_export_control_relevant TEXT NULL,
    operation_model TEXT NULL,
    production_operating_hours TEXT NULL,
    service_operating_hours TEXT NULL,
    ssl_certificates_used TEXT NULL,
    all_machine_users TEXT NULL,
    recovery_plan_url TEXT NULL,
    authorization_concept_url TEXT NULL,
    password_storage_tool TEXT NULL,
    availability_support_url TEXT NULL,
    recurring_tasks_responsibilities_url TEXT NULL,
    backup_recovery_url TEXT NULL,
    monitoring_escalation_url TEXT NULL,
    tools_used_for_monitoring_url TEXT NULL,
    license_management_url TEXT NULL,
    communication_channels_url TEXT NULL,
    incident_assignment_group TEXT NULL,
    solver_group_c TEXT NULL,
    change_approval_group TEXT NULL,
    cab_approval_group TEXT NULL,
    change_fulfillment_group TEXT NULL,
    run_and_change TEXT NULL,
    managed_service_run TEXT NULL,
    managed_service_change TEXT NULL,
    extended_workbench_change TEXT NULL,
    extended_workbench_run TEXT NULL,
    managed_internally TEXT NULL,
    notes TEXT NULL,
    cmdb_workspace_url TEXT NULL,
    created_at DATETIME NULL,
    updated_at DATETIME NULL,
    created_by VARCHAR(255) NULL,
    updated_by VARCHAR(255) NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_application_register_car_id UNIQUE (car_id)
);

CREATE INDEX idx_application_register_name ON application_register (name);
CREATE INDEX idx_application_register_owner ON application_register (business_owner);
CREATE INDEX idx_application_register_manager ON application_register (application_manager);
CREATE INDEX idx_application_register_status ON application_register (operational_status);
CREATE INDEX idx_application_register_criticality ON application_register (criticality);

CREATE TABLE application_register_asset (
    application_register_id BIGINT NOT NULL,
    asset_id BIGINT NOT NULL,
    PRIMARY KEY (application_register_id, asset_id),
    CONSTRAINT fk_application_register_asset_application
        FOREIGN KEY (application_register_id) REFERENCES application_register (id)
        ON DELETE CASCADE,
    CONSTRAINT fk_application_register_asset_asset
        FOREIGN KEY (asset_id) REFERENCES asset (id)
        ON DELETE CASCADE
);

CREATE INDEX idx_application_register_asset_asset ON application_register_asset (asset_id);
