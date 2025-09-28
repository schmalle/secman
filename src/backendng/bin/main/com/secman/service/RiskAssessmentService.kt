package com.secman.service

import com.secman.domain.RiskAssessment
import com.secman.domain.AssessmentBasisType
import com.secman.repository.RiskAssessmentRepository
import jakarta.inject.Inject
import jakarta.inject.Singleton
import java.time.LocalDate
import java.time.LocalDateTime

@Singleton
class RiskAssessmentService(
    @Inject private val riskAssessmentRepository: RiskAssessmentRepository
) {

    fun getAssessments(limit: Int? = null): List<RiskAssessment> {
        val assessments = riskAssessmentRepository.findAll()
        return if (limit != null) assessments.take(limit) else assessments
    }

    fun getAssessmentById(id: Long): RiskAssessment? {
        return riskAssessmentRepository.findById(id).orElse(null)
    }

    fun searchAssessments(query: String, limit: Int? = null): List<RiskAssessment> {
        // Search by status or notes containing the query
        val statusMatches = riskAssessmentRepository.findByStatus(query)
        val allAssessments = riskAssessmentRepository.findAll()
        val notesMatches = allAssessments.filter {
            it.notes?.contains(query, ignoreCase = true) == true
        }

        val combined = (statusMatches + notesMatches).distinctBy { it.id }
        return if (limit != null) combined.take(limit) else combined
    }

    fun getAssessmentsByBasisType(basisType: AssessmentBasisType, limit: Int? = null): List<RiskAssessment> {
        val assessments = riskAssessmentRepository.findByAssessmentBasisType(basisType)
        return if (limit != null) assessments.take(limit) else assessments
    }

    fun getAssessmentsByStatus(status: String, limit: Int? = null): List<RiskAssessment> {
        val assessments = riskAssessmentRepository.findByStatus(status)
        return if (limit != null) assessments.take(limit) else assessments
    }

    fun getAssessmentsByDateRange(startDate: LocalDate, endDate: LocalDate, limit: Int? = null): List<RiskAssessment> {
        val assessments = riskAssessmentRepository.findByDateRange(startDate, endDate)
        return if (limit != null) assessments.take(limit) else assessments
    }

    fun getAssessmentsByAssessorId(assessorId: Long, limit: Int? = null): List<RiskAssessment> {
        val assessments = riskAssessmentRepository.findByAssessorId(assessorId)
        return if (limit != null) assessments.take(limit) else assessments
    }

    fun getAssessmentsByRequestorId(requestorId: Long, limit: Int? = null): List<RiskAssessment> {
        val assessments = riskAssessmentRepository.findByRequestorId(requestorId)
        return if (limit != null) assessments.take(limit) else assessments
    }

    fun getAssessmentsByDemandId(demandId: Long, limit: Int? = null): List<RiskAssessment> {
        val assessments = riskAssessmentRepository.findByDemandId(demandId)
        return if (limit != null) assessments.take(limit) else assessments
    }

    fun getAssessmentsByAssetId(assetId: Long, limit: Int? = null): List<RiskAssessment> {
        val assessments = riskAssessmentRepository.findByAssetId(assetId)
        return if (limit != null) assessments.take(limit) else assessments
    }

    fun createAssessment(assessment: RiskAssessment): RiskAssessment {
        assessment.createdAt = LocalDateTime.now()
        assessment.updatedAt = LocalDateTime.now()
        return riskAssessmentRepository.save(assessment)
    }

    fun updateAssessment(id: Long, updatedAssessment: RiskAssessment): RiskAssessment? {
        return getAssessmentById(id)?.let { existing ->
            val updated = existing.copy(
                startDate = updatedAssessment.startDate,
                endDate = updatedAssessment.endDate,
                status = updatedAssessment.status,
                notes = updatedAssessment.notes,
                isReleaseLocked = updatedAssessment.isReleaseLocked,
                respondent = updatedAssessment.respondent,
                useCases = updatedAssessment.useCases,
                updatedAt = LocalDateTime.now()
            )
            riskAssessmentRepository.update(updated)
            updated
        }
    }

    fun deleteAssessment(id: Long): Boolean {
        return if (riskAssessmentRepository.existsById(id)) {
            riskAssessmentRepository.deleteById(id)
            true
        } else {
            false
        }
    }

    fun getAssessmentsByUsecaseId(usecaseId: Long): List<RiskAssessment> {
        return riskAssessmentRepository.findByUsecaseId(usecaseId)
    }

    fun getAllByInvolvedAssetId(assetId: Long): List<RiskAssessment> {
        return riskAssessmentRepository.findAllByInvolvedAssetId(assetId)
    }
}