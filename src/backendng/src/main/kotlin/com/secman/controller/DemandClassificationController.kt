package com.secman.controller

import com.fasterxml.jackson.databind.ObjectMapper
import com.secman.domain.*
import com.secman.repository.*
import com.secman.service.DemandClassificationService
import io.micronaut.core.annotation.Nullable
import io.micronaut.http.HttpResponse
import io.micronaut.http.HttpStatus
import io.micronaut.http.MediaType
import io.micronaut.http.annotation.*
import io.micronaut.http.multipart.CompletedFileUpload
import io.micronaut.scheduling.TaskExecutors
import io.micronaut.scheduling.annotation.ExecuteOn
import io.micronaut.security.annotation.Secured
import io.micronaut.security.authentication.Authentication
import io.micronaut.security.rules.SecurityRule
import io.micronaut.serde.annotation.Serdeable
import io.micronaut.transaction.annotation.Transactional
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import org.slf4j.LoggerFactory
import java.nio.charset.StandardCharsets

@Controller("/api/classification")
@ExecuteOn(TaskExecutors.BLOCKING)
open class DemandClassificationController(
    private val classificationService: DemandClassificationService,
    private val ruleRepository: DemandClassificationRuleRepository,
    private val resultRepository: DemandClassificationResultRepository,
    private val demandRepository: DemandRepository,
    private val userRepository: UserRepository
) {
    
    private val log = LoggerFactory.getLogger(DemandClassificationController::class.java)
    
    @Serdeable
    data class CreateRuleRequest(
        @NotBlank val name: String,
        val description: String? = null,
        @NotNull val condition: RuleCondition,
        @NotNull val classification: Classification,
        val confidenceScore: Double = 1.0
    )
    
    @Serdeable
    data class UpdateRuleRequest(
        val description: String? = null,
        val condition: RuleCondition? = null,
        val classification: Classification? = null,
        val confidenceScore: Double? = null,
        val active: Boolean? = null,
        val priorityOrder: Int? = null
    )
    
    @Serdeable
    data class UpdateRulePriorityRequest(
        @NotNull val ruleIds: List<Long>
    )
    
    @Serdeable
    data class ClassifyDemandRequest(
        @NotNull val demandId: Long
    )
    
    @Serdeable
    data class TestClassificationRequest(
        @NotNull val input: ClassificationInput,
        val ruleId: Long? = null
    )
    
    // Public endpoint for classification (no authentication required)
    @Post("/public/classify")
    @Secured(SecurityRule.IS_ANONYMOUS)
    @Transactional
    open fun publicClassify(@Valid @Body input: ClassificationInput): HttpResponse<ClassificationResponse> {
        return try {
            log.info("Public classification request received")
            val response = classificationService.classifyDemand(input)
            
            // Save the public classification result
            val result = DemandClassificationResult(
                demand = null,
                classification = response.classification,
                confidenceScore = response.confidenceScore,
                appliedRule = response.appliedRuleName?.let { 
                    ruleRepository.findActiveByName(it).orElse(null) 
                },
                ruleEvaluationLog = response.evaluationLog.joinToString("\n"),
                classificationHash = response.classificationHash,
                inputData = input.toString(),
                classifiedAt = response.timestamp
            )
            resultRepository.save(result)
            
            HttpResponse.ok(response)
        } catch (e: Exception) {
            log.error("Error in public classification", e)
            HttpResponse.serverError()
        }
    }
    
    // Authenticated endpoints for rule management
    @Get("/rules")
    @Secured(SecurityRule.IS_AUTHENTICATED)
    @Transactional(readOnly = true)
    open fun listRules(@QueryValue @Nullable active: Boolean?): HttpResponse<List<DemandClassificationRule>> {
        return try {
            val rules = if (active != null) {
                if (active) ruleRepository.findByActiveTrue()
                else ruleRepository.findAll().filter { !it.active }
            } else {
                ruleRepository.findAll()
            }
            
            HttpResponse.ok(rules.sortedBy { it.priorityOrder })
        } catch (e: Exception) {
            log.error("Error listing rules", e)
            HttpResponse.serverError()
        }
    }
    
    @Get("/rules/{id}")
    @Secured(SecurityRule.IS_AUTHENTICATED)
    @Transactional(readOnly = true)
    open fun getRule(id: Long): HttpResponse<DemandClassificationRule> {
        return ruleRepository.findById(id)
            .map { HttpResponse.ok(it) }
            .orElse(HttpResponse.notFound())
    }
    
    @Post("/rules")
    @Secured("ROLE_ADMIN")
    @Transactional
    open fun createRule(
        @Valid @Body request: CreateRuleRequest,
        authentication: Authentication
    ): HttpResponse<DemandClassificationRule> {
        return try {
            val user = userRepository.findByUsername(authentication.name).orElse(null)
            
            val rule = ClassificationRule(
                name = request.name,
                description = request.description,
                condition = request.condition,
                classification = request.classification,
                confidenceScore = request.confidenceScore
            )
            
            val savedRule = classificationService.saveRule(rule, user)
            log.info("Created classification rule: {}", savedRule.name)
            
            HttpResponse.status<DemandClassificationRule>(HttpStatus.CREATED).body(savedRule)
        } catch (e: Exception) {
            log.error("Error creating rule", e)
            HttpResponse.serverError()
        }
    }
    
    @Put("/rules/{id}")
    @Secured("ROLE_ADMIN")
    @Transactional
    open fun updateRule(
        id: Long,
        @Valid @Body request: UpdateRuleRequest
    ): HttpResponse<DemandClassificationRule> {
        return try {
            val rule = ruleRepository.findById(id).orElse(null)
                ?: return HttpResponse.notFound()
            
            request.description?.let { rule.description = it }
            request.active?.let { rule.active = it }
            request.priorityOrder?.let { rule.priorityOrder = it }
            
            // Update rule JSON if conditions changed
            if (request.condition != null || request.classification != null || request.confidenceScore != null) {
                val currentRule = classificationService.parseRule(rule.ruleJson)
                val updatedRule = currentRule.copy(
                    condition = request.condition ?: currentRule.condition,
                    classification = request.classification ?: currentRule.classification,
                    confidenceScore = request.confidenceScore ?: currentRule.confidenceScore
                )
                rule.ruleJson = ObjectMapper().writeValueAsString(updatedRule)
            }
            
            val updated = ruleRepository.update(rule)
            log.info("Updated classification rule: {}", updated.name)
            
            HttpResponse.ok(updated)
        } catch (e: Exception) {
            log.error("Error updating rule", e)
            HttpResponse.serverError()
        }
    }
    
    @Put("/rules/priority")
    @Secured("ROLE_ADMIN")
    @Transactional
    open fun updateRulePriorities(@Valid @Body request: UpdateRulePriorityRequest): HttpResponse<List<DemandClassificationRule>> {
        return try {
            val rules = request.ruleIds.mapIndexedNotNull { index, ruleId ->
                ruleRepository.findById(ruleId).orElse(null)?.apply {
                    priorityOrder = index
                }
            }
            
            val updated = rules.map { ruleRepository.update(it) }
            log.info("Updated priority for {} rules", updated.size)
            
            HttpResponse.ok(updated)
        } catch (e: Exception) {
            log.error("Error updating rule priorities", e)
            HttpResponse.serverError()
        }
    }
    
    @Delete("/rules/{id}")
    @Secured("ROLE_ADMIN")
    @Transactional
    open fun deleteRule(id: Long): HttpResponse<Map<String, String>> {
        return try {
            val rule = ruleRepository.findById(id).orElse(null)
                ?: return HttpResponse.notFound()
            
            // Check if rule has been used
            val usageCount = resultRepository.findByAppliedRuleId(id).size
            if (usageCount > 0) {
                // Soft delete - just deactivate
                rule.active = false
                ruleRepository.update(rule)
                log.info("Deactivated rule: {} (used in {} classifications)", rule.name, usageCount)
            } else {
                // Hard delete if never used
                ruleRepository.delete(rule)
                log.info("Deleted rule: {}", rule.name)
            }
            
            HttpResponse.ok(mapOf("message" to "Rule deleted successfully"))
        } catch (e: Exception) {
            log.error("Error deleting rule", e)
            HttpResponse.serverError()
        }
    }
    
    @Post("/rules/import", consumes = [MediaType.MULTIPART_FORM_DATA])
    @Secured("ROLE_ADMIN")
    @Transactional
    open fun importRules(
        file: CompletedFileUpload,
        authentication: Authentication
    ): HttpResponse<List<DemandClassificationRule>> {
        return try {
            val content = String(file.bytes, StandardCharsets.UTF_8)
            val user = userRepository.findByUsername(authentication.name).orElse(null)
            
            val imported = classificationService.importRulesFromFile(content, user)
            log.info("Imported {} classification rules", imported.size)
            
            HttpResponse.ok(imported)
        } catch (e: Exception) {
            log.error("Error importing rules", e)
            HttpResponse.serverError()
        }
    }
    
    @Get("/rules/export", produces = [MediaType.APPLICATION_JSON])
    @Secured(SecurityRule.IS_AUTHENTICATED)
    @Transactional(readOnly = true)
    open fun exportRules(): HttpResponse<String> {
        return try {
            val json = classificationService.exportRulesToJson()
            HttpResponse.ok(json)
                .header("Content-Disposition", "attachment; filename=\"classification-rules.json\"")
        } catch (e: Exception) {
            log.error("Error exporting rules", e)
            HttpResponse.serverError()
        }
    }
    
    @Post("/test")
    @Secured(SecurityRule.IS_AUTHENTICATED)
    @Transactional(readOnly = true)
    open fun testClassification(@Valid @Body request: TestClassificationRequest): HttpResponse<ClassificationResponse> {
        return try {
            val response = classificationService.classifyDemand(request.input)
            HttpResponse.ok(response)
        } catch (e: Exception) {
            log.error("Error testing classification", e)
            HttpResponse.serverError()
        }
    }
    
    @Post("/classify-demand")
    @Secured(SecurityRule.IS_AUTHENTICATED)
    @Transactional
    open fun classifyDemand(@Valid @Body request: ClassifyDemandRequest): HttpResponse<ClassificationResponse> {
        return try {
            val demand = demandRepository.findById(request.demandId).orElse(null)
                ?: return HttpResponse.notFound()
            
            val input = ClassificationInput(
                title = demand.title,
                description = demand.description,
                demandType = demand.demandType.name,
                priority = demand.priority.name,
                businessJustification = demand.businessJustification,
                assetType = demand.getAssetType(),
                assetOwner = demand.getAssetOwner()
            )
            
            val response = classificationService.classifyDemand(input, demand.id)
            
            log.info("Classified demand {} as {}", demand.id, response.classification)
            HttpResponse.ok(response)
        } catch (e: Exception) {
            log.error("Error classifying demand", e)
            HttpResponse.serverError()
        }
    }
    
    @Get("/results")
    @Secured(SecurityRule.IS_AUTHENTICATED)
    @Transactional(readOnly = true)
    open fun listResults(
        @QueryValue @Nullable classification: Classification?,
        @QueryValue @Nullable demandId: Long?
    ): HttpResponse<List<DemandClassificationResult>> {
        return try {
            val results = when {
                demandId != null -> listOf(resultRepository.findByDemandId(demandId).orElse(null)).filterNotNull()
                classification != null -> resultRepository.findByClassification(classification)
                else -> resultRepository.findAll()
            }
            
            HttpResponse.ok(results)
        } catch (e: Exception) {
            log.error("Error listing classification results", e)
            HttpResponse.serverError()
        }
    }
    
    @Get("/results/{hash}")
    @Secured(SecurityRule.IS_ANONYMOUS)
    @Transactional(readOnly = true)
    open fun getResultByHash(hash: String): HttpResponse<DemandClassificationResult> {
        return resultRepository.findByClassificationHash(hash)
            .map { HttpResponse.ok(it) }
            .orElse(HttpResponse.notFound())
    }
    
    @Get("/statistics")
    @Secured(SecurityRule.IS_AUTHENTICATED)
    @Transactional(readOnly = true)
    open fun getStatistics(): HttpResponse<Map<String, Any>> {
        return try {
            val stats = mapOf(
                "totalClassifications" to resultRepository.count(),
                "classificationCounts" to mapOf(
                    "A" to resultRepository.countByClassification(Classification.A),
                    "B" to resultRepository.countByClassification(Classification.B),
                    "C" to resultRepository.countByClassification(Classification.C)
                ),
                "activeRules" to ruleRepository.findByActiveTrue().size,
                "totalRules" to ruleRepository.count()
            )
            
            HttpResponse.ok(stats)
        } catch (e: Exception) {
            log.error("Error getting statistics", e)
            HttpResponse.serverError()
        }
    }
}