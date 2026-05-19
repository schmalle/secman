package com.secman.dto

import com.secman.domain.ApplicationRegister
import com.secman.domain.Asset
import io.micronaut.serde.annotation.Serdeable
import java.time.LocalDate
import java.time.LocalDateTime

@Serdeable
data class ApplicationRegisterRequest(
    val carId: String,
    val name: String,
    val processCluster: String? = null,
    val processArea: String? = null,
    val criticality: String? = null,
    val operationalStatus: String? = null,
    val businessOwner: String,
    val applicationChampion: String? = null,
    val applicationManager: String,
    val applicationTechnology: String? = null,
    val applicationArchitecture: String? = null,
    val lastQualityCheck: LocalDate? = null,
    val informationClassification: String? = null,
    val processingOfPersonalData: String? = null,
    val icsRelevant: String? = null,
    val legalRegulatory: String? = null,
    val legalRegulatoryRationaleImpact: String? = null,
    val dataExportControlRelevant: String? = null,
    val applicationExportControlRelevant: String? = null,
    val operationModel: String? = null,
    val productionOperatingHours: String? = null,
    val serviceOperatingHours: String? = null,
    val sslCertificatesUsed: String? = null,
    val allMachineUsers: String? = null,
    val recoveryPlanUrl: String? = null,
    val authorizationConceptUrl: String? = null,
    val passwordStorageTool: String? = null,
    val availabilitySupportUrl: String? = null,
    val recurringTasksResponsibilitiesUrl: String? = null,
    val backupRecoveryUrl: String? = null,
    val monitoringEscalationUrl: String? = null,
    val toolsUsedForMonitoringUrl: String? = null,
    val licenseManagementUrl: String? = null,
    val communicationChannelsUrl: String? = null,
    val incidentAssignmentGroup: String? = null,
    val solverGroupC: String? = null,
    val changeApprovalGroup: String? = null,
    val cabApprovalGroup: String? = null,
    val changeFulfillmentGroup: String? = null,
    val runAndChange: String? = null,
    val managedServiceRun: String? = null,
    val managedServiceChange: String? = null,
    val extendedWorkbenchChange: String? = null,
    val extendedWorkbenchRun: String? = null,
    val managedInternally: String? = null,
    val notes: String? = null,
    val cmdbWorkspaceUrl: String? = null
)

@Serdeable
data class ApplicationRegisterSummary(
    val id: Long,
    val carId: String,
    val name: String,
    val criticality: String?,
    val operationalStatus: String?,
    val businessOwner: String,
    val applicationManager: String,
    val updatedAt: LocalDateTime?
) {
    companion object {
        fun from(application: ApplicationRegister): ApplicationRegisterSummary {
            return ApplicationRegisterSummary(
                id = application.id ?: 0,
                carId = application.carId,
                name = application.name,
                criticality = application.criticality,
                operationalStatus = application.operationalStatus,
                businessOwner = application.businessOwner,
                applicationManager = application.applicationManager,
                updatedAt = application.updatedAt
            )
        }
    }
}

@Serdeable
data class ApplicationRegisterAssetSummary(
    val id: Long,
    val name: String,
    val type: String,
    val owner: String,
    val ip: String?
) {
    companion object {
        fun from(asset: Asset): ApplicationRegisterAssetSummary {
            return ApplicationRegisterAssetSummary(
                id = asset.id ?: 0,
                name = asset.name,
                type = asset.type,
                owner = asset.owner,
                ip = asset.ip
            )
        }
    }
}

@Serdeable
data class ApplicationRegisterDetail(
    val id: Long,
    val carId: String,
    val name: String,
    val processCluster: String?,
    val processArea: String?,
    val criticality: String?,
    val operationalStatus: String?,
    val businessOwner: String,
    val applicationChampion: String?,
    val applicationManager: String,
    val applicationTechnology: String?,
    val applicationArchitecture: String?,
    val lastQualityCheck: LocalDate?,
    val informationClassification: String?,
    val processingOfPersonalData: String?,
    val icsRelevant: String?,
    val legalRegulatory: String?,
    val legalRegulatoryRationaleImpact: String?,
    val dataExportControlRelevant: String?,
    val applicationExportControlRelevant: String?,
    val operationModel: String?,
    val productionOperatingHours: String?,
    val serviceOperatingHours: String?,
    val sslCertificatesUsed: String?,
    val allMachineUsers: String?,
    val recoveryPlanUrl: String?,
    val authorizationConceptUrl: String?,
    val passwordStorageTool: String?,
    val availabilitySupportUrl: String?,
    val recurringTasksResponsibilitiesUrl: String?,
    val backupRecoveryUrl: String?,
    val monitoringEscalationUrl: String?,
    val toolsUsedForMonitoringUrl: String?,
    val licenseManagementUrl: String?,
    val communicationChannelsUrl: String?,
    val incidentAssignmentGroup: String?,
    val solverGroupC: String?,
    val changeApprovalGroup: String?,
    val cabApprovalGroup: String?,
    val changeFulfillmentGroup: String?,
    val runAndChange: String?,
    val managedServiceRun: String?,
    val managedServiceChange: String?,
    val extendedWorkbenchChange: String?,
    val extendedWorkbenchRun: String?,
    val managedInternally: String?,
    val notes: String?,
    val cmdbWorkspaceUrl: String?,
    val createdAt: LocalDateTime?,
    val updatedAt: LocalDateTime?,
    val createdBy: String?,
    val updatedBy: String?,
    val assets: List<ApplicationRegisterAssetSummary>
) {
    companion object {
        fun from(application: ApplicationRegister): ApplicationRegisterDetail {
            return ApplicationRegisterDetail(
                id = application.id ?: 0,
                carId = application.carId,
                name = application.name,
                processCluster = application.processCluster,
                processArea = application.processArea,
                criticality = application.criticality,
                operationalStatus = application.operationalStatus,
                businessOwner = application.businessOwner,
                applicationChampion = application.applicationChampion,
                applicationManager = application.applicationManager,
                applicationTechnology = application.applicationTechnology,
                applicationArchitecture = application.applicationArchitecture,
                lastQualityCheck = application.lastQualityCheck,
                informationClassification = application.informationClassification,
                processingOfPersonalData = application.processingOfPersonalData,
                icsRelevant = application.icsRelevant,
                legalRegulatory = application.legalRegulatory,
                legalRegulatoryRationaleImpact = application.legalRegulatoryRationaleImpact,
                dataExportControlRelevant = application.dataExportControlRelevant,
                applicationExportControlRelevant = application.applicationExportControlRelevant,
                operationModel = application.operationModel,
                productionOperatingHours = application.productionOperatingHours,
                serviceOperatingHours = application.serviceOperatingHours,
                sslCertificatesUsed = application.sslCertificatesUsed,
                allMachineUsers = application.allMachineUsers,
                recoveryPlanUrl = application.recoveryPlanUrl,
                authorizationConceptUrl = application.authorizationConceptUrl,
                passwordStorageTool = application.passwordStorageTool,
                availabilitySupportUrl = application.availabilitySupportUrl,
                recurringTasksResponsibilitiesUrl = application.recurringTasksResponsibilitiesUrl,
                backupRecoveryUrl = application.backupRecoveryUrl,
                monitoringEscalationUrl = application.monitoringEscalationUrl,
                toolsUsedForMonitoringUrl = application.toolsUsedForMonitoringUrl,
                licenseManagementUrl = application.licenseManagementUrl,
                communicationChannelsUrl = application.communicationChannelsUrl,
                incidentAssignmentGroup = application.incidentAssignmentGroup,
                solverGroupC = application.solverGroupC,
                changeApprovalGroup = application.changeApprovalGroup,
                cabApprovalGroup = application.cabApprovalGroup,
                changeFulfillmentGroup = application.changeFulfillmentGroup,
                runAndChange = application.runAndChange,
                managedServiceRun = application.managedServiceRun,
                managedServiceChange = application.managedServiceChange,
                extendedWorkbenchChange = application.extendedWorkbenchChange,
                extendedWorkbenchRun = application.extendedWorkbenchRun,
                managedInternally = application.managedInternally,
                notes = application.notes,
                cmdbWorkspaceUrl = application.cmdbWorkspaceUrl,
                createdAt = application.createdAt,
                updatedAt = application.updatedAt,
                createdBy = application.createdBy,
                updatedBy = application.updatedBy,
                assets = application.assets.sortedBy { it.name }.map { ApplicationRegisterAssetSummary.from(it) }
            )
        }
    }
}

@Serdeable
data class ApplicationRegisterAssetUpdateRequest(
    val assetIds: List<Long> = emptyList()
)
