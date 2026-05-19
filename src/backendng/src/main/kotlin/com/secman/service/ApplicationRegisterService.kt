package com.secman.service

import com.secman.domain.ApplicationRegister
import com.secman.dto.ApplicationRegisterDetail
import com.secman.dto.ApplicationRegisterRequest
import com.secman.dto.ApplicationRegisterSummary
import com.secman.repository.ApplicationRegisterRepository
import com.secman.repository.AssetRepository
import jakarta.inject.Singleton
import jakarta.transaction.Transactional

@Singleton
open class ApplicationRegisterService(
    private val repository: ApplicationRegisterRepository,
    private val assetRepository: AssetRepository
) {

    @Transactional
    open fun list(search: String?): List<ApplicationRegisterSummary> {
        return repository.search(search?.trim()).map { ApplicationRegisterSummary.from(it) }
    }

    @Transactional
    open fun get(id: Long): ApplicationRegisterDetail {
        return ApplicationRegisterDetail.from(getWithAssets(id))
    }

    @Transactional
    open fun create(request: ApplicationRegisterRequest, username: String): ApplicationRegisterDetail {
        val cleaned = clean(request)
        val carId = allocateCarId()

        val application = ApplicationRegister(
            carId = carId,
            name = cleaned.name,
            businessOwner = cleaned.businessOwner,
            applicationManager = cleaned.applicationManager
        )
        apply(application, cleaned)
        application.createdBy = username
        application.updatedBy = username

        return ApplicationRegisterDetail.from(repository.save(application))
    }

    @Transactional
    open fun update(id: Long, request: ApplicationRegisterRequest, username: String): ApplicationRegisterDetail {
        val application = getWithAssets(id)
        val cleaned = clean(request)

        apply(application, cleaned)
        application.updatedBy = username

        return ApplicationRegisterDetail.from(repository.update(application))
    }

    @Transactional
    open fun delete(id: Long) {
        if (!repository.existsById(id)) {
            throw NoSuchElementException("Application not found")
        }
        repository.deleteById(id)
    }

    @Transactional
    open fun replaceAssets(id: Long, assetIds: List<Long>, username: String): ApplicationRegisterDetail {
        val application = getWithAssets(id)
        val distinctIds = assetIds.distinct()
        val assets = if (distinctIds.isEmpty()) emptyList() else assetRepository.findByIdIn(distinctIds)
        val foundIds = assets.mapNotNull { it.id }.toSet()
        val missing = distinctIds.filterNot { foundIds.contains(it) }
        if (missing.isNotEmpty()) {
            throw IllegalArgumentException("Unknown asset ids: ${missing.joinToString(", ")}")
        }

        application.assets.clear()
        application.assets.addAll(assets)
        application.updatedBy = username

        return ApplicationRegisterDetail.from(repository.update(application))
    }

    private fun getWithAssets(id: Long): ApplicationRegister {
        return repository.findByIdWithAssets(id).orElseThrow {
            NoSuchElementException("Application not found")
        }
    }

    private fun clean(request: ApplicationRegisterRequest): ApplicationRegisterRequest {
        val name = request.name.trim()
        val businessOwner = request.businessOwner.trim()
        val applicationManager = request.applicationManager.trim()
        val errors = mutableListOf<String>()
        if (name.isBlank()) errors.add("name is required")
        if (businessOwner.isBlank()) errors.add("businessOwner is required")
        if (applicationManager.isBlank()) errors.add("applicationManager is required")
        if (errors.isNotEmpty()) {
            throw IllegalArgumentException(errors.joinToString("; "))
        }

        return request.copy(
            name = name,
            businessOwner = businessOwner,
            applicationManager = applicationManager,
            criticality = request.criticality.clean(),
            operationalStatus = request.operationalStatus.clean(),
            applicationTechnology = request.applicationTechnology.clean(),
            applicationArchitecture = request.applicationArchitecture.clean(),
            informationClassification = request.informationClassification.clean(),
            processingOfPersonalData = request.processingOfPersonalData.clean(),
            icsRelevant = request.icsRelevant.clean(),
            applicationExportControlRelevant = request.applicationExportControlRelevant.clean(),
            operationModel = request.operationModel.clean(),
            productionOperatingHours = request.productionOperatingHours.clean(),
            serviceOperatingHours = request.serviceOperatingHours.clean(),
            backupRecoveryUrl = request.backupRecoveryUrl.clean(),
            incidentAssignmentGroup = request.incidentAssignmentGroup.clean(),
            notes = request.notes.clean(),
            cmdbWorkspaceUrl = request.cmdbWorkspaceUrl.clean()
        )
    }

    private fun apply(application: ApplicationRegister, request: ApplicationRegisterRequest) {
        application.name = request.name
        application.criticality = request.criticality
        application.operationalStatus = request.operationalStatus
        application.businessOwner = request.businessOwner
        application.applicationManager = request.applicationManager
        application.applicationTechnology = request.applicationTechnology
        application.applicationArchitecture = request.applicationArchitecture
        application.lastQualityCheck = request.lastQualityCheck
        application.informationClassification = request.informationClassification
        application.processingOfPersonalData = request.processingOfPersonalData
        application.icsRelevant = request.icsRelevant
        application.applicationExportControlRelevant = request.applicationExportControlRelevant
        application.operationModel = request.operationModel
        application.productionOperatingHours = request.productionOperatingHours
        application.serviceOperatingHours = request.serviceOperatingHours
        application.backupRecoveryUrl = request.backupRecoveryUrl
        application.incidentAssignmentGroup = request.incidentAssignmentGroup
        application.notes = request.notes
        application.cmdbWorkspaceUrl = request.cmdbWorkspaceUrl
    }

    private fun allocateCarId(): String {
        val highest = repository.findAllCarIds()
            .mapNotNull { it.toLongOrNull() }
            .maxOrNull() ?: 0L
        var candidate = highest + 1
        while (repository.existsByCarIdIgnoreCase(candidate.toString())) {
            candidate += 1
        }
        return candidate.toString()
    }

    private fun String?.clean(): String? = this?.trim()?.ifBlank { null }
}
