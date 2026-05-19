package com.secman.domain

import com.fasterxml.jackson.annotation.JsonIgnore
import io.micronaut.serde.annotation.Serdeable
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.JoinColumn
import jakarta.persistence.JoinTable
import jakarta.persistence.ManyToMany
import jakarta.persistence.PrePersist
import jakarta.persistence.PreUpdate
import jakarta.persistence.Table
import jakarta.validation.constraints.NotBlank
import java.time.LocalDate
import java.time.LocalDateTime

@Entity
@Table(
    name = "application_register",
    indexes = [
        Index(name = "idx_application_register_car_id", columnList = "car_id", unique = true),
        Index(name = "idx_application_register_name", columnList = "name"),
        Index(name = "idx_application_register_owner", columnList = "business_owner"),
        Index(name = "idx_application_register_manager", columnList = "application_manager"),
        Index(name = "idx_application_register_status", columnList = "operational_status"),
        Index(name = "idx_application_register_criticality", columnList = "criticality")
    ]
)
@Serdeable
data class ApplicationRegister(
    @Id
    @GeneratedValue
    var id: Long? = null,

    @Column(name = "car_id", nullable = false, unique = true, length = 100)
    @NotBlank
    var carId: String,

    @Column(nullable = false, length = 255)
    @NotBlank
    var name: String,

    @Column(name = "process_cluster", columnDefinition = "TEXT")
    var processCluster: String? = null,

    @Column(name = "process_area", columnDefinition = "TEXT")
    var processArea: String? = null,

    @Column(length = 100)
    var criticality: String? = null,

    @Column(name = "operational_status", length = 100)
    var operationalStatus: String? = null,

    @Column(name = "business_owner", nullable = false, length = 255)
    @NotBlank
    var businessOwner: String,

    @Column(name = "application_champion", columnDefinition = "TEXT")
    var applicationChampion: String? = null,

    @Column(name = "application_manager", nullable = false, length = 255)
    @NotBlank
    var applicationManager: String,

    @Column(name = "application_technology", columnDefinition = "TEXT")
    var applicationTechnology: String? = null,

    @Column(name = "application_architecture", columnDefinition = "TEXT")
    var applicationArchitecture: String? = null,

    @Column(name = "last_quality_check")
    var lastQualityCheck: LocalDate? = null,

    @Column(name = "information_classification", columnDefinition = "TEXT")
    var informationClassification: String? = null,

    @Column(name = "processing_of_personal_data", columnDefinition = "TEXT")
    var processingOfPersonalData: String? = null,

    @Column(name = "ics_relevant", columnDefinition = "TEXT")
    var icsRelevant: String? = null,

    @Column(name = "legal_regulatory", columnDefinition = "TEXT")
    var legalRegulatory: String? = null,

    @Column(name = "legal_regulatory_rationale_impact", columnDefinition = "TEXT")
    var legalRegulatoryRationaleImpact: String? = null,

    @Column(name = "data_export_control_relevant", columnDefinition = "TEXT")
    var dataExportControlRelevant: String? = null,

    @Column(name = "application_export_control_relevant", columnDefinition = "TEXT")
    var applicationExportControlRelevant: String? = null,

    @Column(name = "operation_model", columnDefinition = "TEXT")
    var operationModel: String? = null,

    @Column(name = "production_operating_hours", columnDefinition = "TEXT")
    var productionOperatingHours: String? = null,

    @Column(name = "service_operating_hours", columnDefinition = "TEXT")
    var serviceOperatingHours: String? = null,

    @Column(name = "ssl_certificates_used", columnDefinition = "TEXT")
    var sslCertificatesUsed: String? = null,

    @Column(name = "all_machine_users", columnDefinition = "TEXT")
    var allMachineUsers: String? = null,

    @Column(name = "recovery_plan_url", columnDefinition = "TEXT")
    var recoveryPlanUrl: String? = null,

    @Column(name = "authorization_concept_url", columnDefinition = "TEXT")
    var authorizationConceptUrl: String? = null,

    @Column(name = "password_storage_tool", columnDefinition = "TEXT")
    var passwordStorageTool: String? = null,

    @Column(name = "availability_support_url", columnDefinition = "TEXT")
    var availabilitySupportUrl: String? = null,

    @Column(name = "recurring_tasks_responsibilities_url", columnDefinition = "TEXT")
    var recurringTasksResponsibilitiesUrl: String? = null,

    @Column(name = "backup_recovery_url", columnDefinition = "TEXT")
    var backupRecoveryUrl: String? = null,

    @Column(name = "monitoring_escalation_url", columnDefinition = "TEXT")
    var monitoringEscalationUrl: String? = null,

    @Column(name = "tools_used_for_monitoring_url", columnDefinition = "TEXT")
    var toolsUsedForMonitoringUrl: String? = null,

    @Column(name = "license_management_url", columnDefinition = "TEXT")
    var licenseManagementUrl: String? = null,

    @Column(name = "communication_channels_url", columnDefinition = "TEXT")
    var communicationChannelsUrl: String? = null,

    @Column(name = "incident_assignment_group", columnDefinition = "TEXT")
    var incidentAssignmentGroup: String? = null,

    @Column(name = "solver_group_c", columnDefinition = "TEXT")
    var solverGroupC: String? = null,

    @Column(name = "change_approval_group", columnDefinition = "TEXT")
    var changeApprovalGroup: String? = null,

    @Column(name = "cab_approval_group", columnDefinition = "TEXT")
    var cabApprovalGroup: String? = null,

    @Column(name = "change_fulfillment_group", columnDefinition = "TEXT")
    var changeFulfillmentGroup: String? = null,

    @Column(name = "run_and_change", columnDefinition = "TEXT")
    var runAndChange: String? = null,

    @Column(name = "managed_service_run", columnDefinition = "TEXT")
    var managedServiceRun: String? = null,

    @Column(name = "managed_service_change", columnDefinition = "TEXT")
    var managedServiceChange: String? = null,

    @Column(name = "extended_workbench_change", columnDefinition = "TEXT")
    var extendedWorkbenchChange: String? = null,

    @Column(name = "extended_workbench_run", columnDefinition = "TEXT")
    var extendedWorkbenchRun: String? = null,

    @Column(name = "managed_internally", columnDefinition = "TEXT")
    var managedInternally: String? = null,

    @Column(columnDefinition = "TEXT")
    var notes: String? = null,

    @Column(name = "cmdb_workspace_url", columnDefinition = "TEXT")
    var cmdbWorkspaceUrl: String? = null,

    @Column(name = "created_at", updatable = false)
    var createdAt: LocalDateTime? = null,

    @Column(name = "updated_at")
    var updatedAt: LocalDateTime? = null,

    @Column(name = "created_by", length = 255)
    var createdBy: String? = null,

    @Column(name = "updated_by", length = 255)
    var updatedBy: String? = null,

    @JsonIgnore
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
        name = "application_register_asset",
        joinColumns = [JoinColumn(name = "application_register_id")],
        inverseJoinColumns = [JoinColumn(name = "asset_id")]
    )
    var assets: MutableSet<Asset> = mutableSetOf()
) {
    @PrePersist
    fun onCreate() {
        val now = LocalDateTime.now()
        createdAt = now
        updatedAt = now
    }

    @PreUpdate
    fun onUpdate() {
        updatedAt = LocalDateTime.now()
    }
}
