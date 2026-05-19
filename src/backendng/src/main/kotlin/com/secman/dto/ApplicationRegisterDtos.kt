package com.secman.dto

import com.secman.domain.ApplicationRegister
import com.secman.domain.Asset
import io.micronaut.serde.annotation.Serdeable
import java.time.LocalDate
import java.time.LocalDateTime

@Serdeable
data class ApplicationRegisterRequest(
    val carId: String? = null,
    val name: String,
    val criticality: String? = null,
    val operationalStatus: String? = null,
    val businessOwner: String,
    val applicationManager: String,
    val applicationTechnology: String? = null,
    val applicationArchitecture: String? = null,
    val lastQualityCheck: LocalDate? = null,
    val informationClassification: String? = null,
    val processingOfPersonalData: String? = null,
    val icsRelevant: String? = null,
    val applicationExportControlRelevant: String? = null,
    val operationModel: String? = null,
    val productionOperatingHours: String? = null,
    val serviceOperatingHours: String? = null,
    val backupRecoveryUrl: String? = null,
    val incidentAssignmentGroup: String? = null,
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
    val criticality: String?,
    val operationalStatus: String?,
    val businessOwner: String,
    val applicationManager: String,
    val applicationTechnology: String?,
    val applicationArchitecture: String?,
    val lastQualityCheck: LocalDate?,
    val informationClassification: String?,
    val processingOfPersonalData: String?,
    val icsRelevant: String?,
    val applicationExportControlRelevant: String?,
    val operationModel: String?,
    val productionOperatingHours: String?,
    val serviceOperatingHours: String?,
    val backupRecoveryUrl: String?,
    val incidentAssignmentGroup: String?,
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
                criticality = application.criticality,
                operationalStatus = application.operationalStatus,
                businessOwner = application.businessOwner,
                applicationManager = application.applicationManager,
                applicationTechnology = application.applicationTechnology,
                applicationArchitecture = application.applicationArchitecture,
                lastQualityCheck = application.lastQualityCheck,
                informationClassification = application.informationClassification,
                processingOfPersonalData = application.processingOfPersonalData,
                icsRelevant = application.icsRelevant,
                applicationExportControlRelevant = application.applicationExportControlRelevant,
                operationModel = application.operationModel,
                productionOperatingHours = application.productionOperatingHours,
                serviceOperatingHours = application.serviceOperatingHours,
                backupRecoveryUrl = application.backupRecoveryUrl,
                incidentAssignmentGroup = application.incidentAssignmentGroup,
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
