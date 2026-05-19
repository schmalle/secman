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
        if (repository.existsByCarIdIgnoreCase(cleaned.carId)) {
            throw IllegalArgumentException("carId already exists")
        }

        val application = ApplicationRegister(
            carId = cleaned.carId,
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
        repository.findByCarIdIgnoreCase(cleaned.carId).ifPresent { existing ->
            if (existing.id != id) {
                throw IllegalArgumentException("carId already exists")
            }
        }

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
        val carId = request.carId.trim()
        val name = request.name.trim()
        val businessOwner = request.businessOwner.trim()
        val applicationManager = request.applicationManager.trim()
        val errors = mutableListOf<String>()
        if (carId.isBlank()) errors.add("carId is required")
        if (name.isBlank()) errors.add("name is required")
        if (businessOwner.isBlank()) errors.add("businessOwner is required")
        if (applicationManager.isBlank()) errors.add("applicationManager is required")
        if (errors.isNotEmpty()) {
            throw IllegalArgumentException(errors.joinToString("; "))
        }

        return request.copy(
            carId = carId,
            name = name,
            businessOwner = businessOwner,
            applicationManager = applicationManager,
            processCluster = request.processCluster.clean(),
            processArea = request.processArea.clean(),
            criticality = request.criticality.clean(),
            operationalStatus = request.operationalStatus.clean(),
            applicationChampion = request.applicationChampion.clean(),
            applicationTechnology = request.applicationTechnology.clean(),
            applicationArchitecture = request.applicationArchitecture.clean(),
            informationClassification = request.informationClassification.clean(),
            processingOfPersonalData = request.processingOfPersonalData.clean(),
            icsRelevant = request.icsRelevant.clean(),
            legalRegulatory = request.legalRegulatory.clean(),
            legalRegulatoryRationaleImpact = request.legalRegulatoryRationaleImpact.clean(),
            dataExportControlRelevant = request.dataExportControlRelevant.clean(),
            applicationExportControlRelevant = request.applicationExportControlRelevant.clean(),
            operationModel = request.operationModel.clean(),
            productionOperatingHours = request.productionOperatingHours.clean(),
            serviceOperatingHours = request.serviceOperatingHours.clean(),
            sslCertificatesUsed = request.sslCertificatesUsed.clean(),
            allMachineUsers = request.allMachineUsers.clean(),
            recoveryPlanUrl = request.recoveryPlanUrl.clean(),
            authorizationConceptUrl = request.authorizationConceptUrl.clean(),
            passwordStorageTool = request.passwordStorageTool.clean(),
            availabilitySupportUrl = request.availabilitySupportUrl.clean(),
            recurringTasksResponsibilitiesUrl = request.recurringTasksResponsibilitiesUrl.clean(),
            backupRecoveryUrl = request.backupRecoveryUrl.clean(),
            monitoringEscalationUrl = request.monitoringEscalationUrl.clean(),
            toolsUsedForMonitoringUrl = request.toolsUsedForMonitoringUrl.clean(),
            licenseManagementUrl = request.licenseManagementUrl.clean(),
            communicationChannelsUrl = request.communicationChannelsUrl.clean(),
            incidentAssignmentGroup = request.incidentAssignmentGroup.clean(),
            solverGroupC = request.solverGroupC.clean(),
            changeApprovalGroup = request.changeApprovalGroup.clean(),
            cabApprovalGroup = request.cabApprovalGroup.clean(),
            changeFulfillmentGroup = request.changeFulfillmentGroup.clean(),
            runAndChange = request.runAndChange.clean(),
            managedServiceRun = request.managedServiceRun.clean(),
            managedServiceChange = request.managedServiceChange.clean(),
            extendedWorkbenchChange = request.extendedWorkbenchChange.clean(),
            extendedWorkbenchRun = request.extendedWorkbenchRun.clean(),
            managedInternally = request.managedInternally.clean(),
            notes = request.notes.clean(),
            cmdbWorkspaceUrl = request.cmdbWorkspaceUrl.clean()
        )
    }

    private fun apply(application: ApplicationRegister, request: ApplicationRegisterRequest) {
        application.carId = request.carId
        application.name = request.name
        application.processCluster = request.processCluster
        application.processArea = request.processArea
        application.criticality = request.criticality
        application.operationalStatus = request.operationalStatus
        application.businessOwner = request.businessOwner
        application.applicationChampion = request.applicationChampion
        application.applicationManager = request.applicationManager
        application.applicationTechnology = request.applicationTechnology
        application.applicationArchitecture = request.applicationArchitecture
        application.lastQualityCheck = request.lastQualityCheck
        application.informationClassification = request.informationClassification
        application.processingOfPersonalData = request.processingOfPersonalData
        application.icsRelevant = request.icsRelevant
        application.legalRegulatory = request.legalRegulatory
        application.legalRegulatoryRationaleImpact = request.legalRegulatoryRationaleImpact
        application.dataExportControlRelevant = request.dataExportControlRelevant
        application.applicationExportControlRelevant = request.applicationExportControlRelevant
        application.operationModel = request.operationModel
        application.productionOperatingHours = request.productionOperatingHours
        application.serviceOperatingHours = request.serviceOperatingHours
        application.sslCertificatesUsed = request.sslCertificatesUsed
        application.allMachineUsers = request.allMachineUsers
        application.recoveryPlanUrl = request.recoveryPlanUrl
        application.authorizationConceptUrl = request.authorizationConceptUrl
        application.passwordStorageTool = request.passwordStorageTool
        application.availabilitySupportUrl = request.availabilitySupportUrl
        application.recurringTasksResponsibilitiesUrl = request.recurringTasksResponsibilitiesUrl
        application.backupRecoveryUrl = request.backupRecoveryUrl
        application.monitoringEscalationUrl = request.monitoringEscalationUrl
        application.toolsUsedForMonitoringUrl = request.toolsUsedForMonitoringUrl
        application.licenseManagementUrl = request.licenseManagementUrl
        application.communicationChannelsUrl = request.communicationChannelsUrl
        application.incidentAssignmentGroup = request.incidentAssignmentGroup
        application.solverGroupC = request.solverGroupC
        application.changeApprovalGroup = request.changeApprovalGroup
        application.cabApprovalGroup = request.cabApprovalGroup
        application.changeFulfillmentGroup = request.changeFulfillmentGroup
        application.runAndChange = request.runAndChange
        application.managedServiceRun = request.managedServiceRun
        application.managedServiceChange = request.managedServiceChange
        application.extendedWorkbenchChange = request.extendedWorkbenchChange
        application.extendedWorkbenchRun = request.extendedWorkbenchRun
        application.managedInternally = request.managedInternally
        application.notes = request.notes
        application.cmdbWorkspaceUrl = request.cmdbWorkspaceUrl
    }

    private fun String?.clean(): String? = this?.trim()?.ifBlank { null }
}
