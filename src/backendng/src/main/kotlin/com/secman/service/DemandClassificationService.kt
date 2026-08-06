package com.secman.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.secman.domain.*
import com.secman.repository.DemandClassificationResultRepository
import com.secman.repository.DemandClassificationRuleRepository
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory
import java.time.LocalDateTime

@Singleton
class DemandClassificationService(
    private val ruleRepository: DemandClassificationRuleRepository,
    private val resultRepository: DemandClassificationResultRepository,
    private val objectMapper: ObjectMapper
) {
    
    private val log = LoggerFactory.getLogger(DemandClassificationService::class.java)
    
    fun classifyDemand(input: ClassificationInput, demandId: Long? = null): ClassificationResponse {
        val evaluationLog = mutableListOf<String>()
        evaluationLog.add("Starting classification evaluation...")
        
        try {
            // Get active rules ordered by priority
            val activeRules = ruleRepository.findByActiveTrueOrderByPriorityOrderAsc()
            
            if (activeRules.isEmpty()) {
                evaluationLog.add("No active classification rules found, defaulting to C")
                return createDefaultResponse(evaluationLog)
            }
            
            evaluationLog.add("Found ${activeRules.size} active rules")
            
            // Evaluate each rule in priority order
            for (dbRule in activeRules) {
                evaluationLog.add("Evaluating rule: ${dbRule.name}")
                
                try {
                    val rule = parseRule(dbRule.ruleJson)
                    val matches = evaluateCondition(input, rule.condition, evaluationLog)
                    
                    if (matches) {
                        evaluationLog.add("Rule '${rule.name}' matched! Applying classification: ${rule.classification}")
                        
                        val timestamp = LocalDateTime.now()
                        val hash = DemandClassificationResult.generateHash(demandId, rule.classification, timestamp)
                        
                        // Save result if demand ID is provided
                        if (demandId != null) {
                            saveClassificationResult(
                                demandId = demandId,
                                classification = rule.classification,
                                hash = hash,
                                appliedRule = dbRule,
                                evaluationLog = evaluationLog.joinToString("\n"),
                                inputData = objectMapper.writeValueAsString(input),
                                confidenceScore = rule.confidenceScore
                            )
                        }
                        
                        return ClassificationResponse(
                            classification = rule.classification,
                            classificationHash = hash,
                            confidenceScore = rule.confidenceScore,
                            appliedRuleName = rule.name,
                            evaluationLog = evaluationLog,
                            timestamp = timestamp
                        )
                    } else {
                        evaluationLog.add("Rule '${rule.name}' did not match")
                    }
                } catch (e: Exception) {
                    log.error("Error evaluating rule '${dbRule.name}': ${e.message}", e)
                    evaluationLog.add("Error evaluating rule '${dbRule.name}': ${e.message}")
                }
            }
            
            // No rules matched, default to C
            evaluationLog.add("No rules matched, applying default classification: C")
            return createDefaultResponse(evaluationLog)
            
        } catch (e: Exception) {
            log.error("Error during classification: ${e.message}", e)
            evaluationLog.add("Error during classification: ${e.message}")
            return createDefaultResponse(evaluationLog)
        }
    }
    
    private fun evaluateCondition(input: ClassificationInput, condition: RuleCondition, log: MutableList<String>): Boolean {
        return when (condition.type) {
            ConditionType.AND -> {
                log.add("  Evaluating AND condition...")
                condition.conditions?.all { evaluateCondition(input, it, log) } ?: true
            }
            ConditionType.OR -> {
                log.add("  Evaluating OR condition...")
                condition.conditions?.any { evaluateCondition(input, it, log) } ?: false
            }
            ConditionType.NOT -> {
                log.add("  Evaluating NOT condition...")
                condition.conditions?.firstOrNull()?.let { !evaluateCondition(input, it, log) } ?: true
            }
            ConditionType.IF -> {
                log.add("  Evaluating IF condition...")
                condition.conditions?.firstOrNull()?.let { evaluateCondition(input, it, log) } ?: false
            }
            ConditionType.COMPARISON -> {
                evaluateComparison(input, condition, log)
            }
        }
    }
    
    private fun evaluateComparison(input: ClassificationInput, condition: RuleCondition, log: MutableList<String>): Boolean {
        val field = condition.field ?: return false
        val operator = condition.operator ?: return false
        val expectedValue = condition.value
        
        val actualValue = getFieldValue(input, field)
        log.add("    Comparing field '$field': $actualValue $operator $expectedValue")
        
        return when (operator) {
            ComparisonOperator.EQUALS -> actualValue == expectedValue
            ComparisonOperator.NOT_EQUALS -> actualValue != expectedValue
            ComparisonOperator.CONTAINS -> {
                actualValue?.toString()?.contains(expectedValue?.toString() ?: "") ?: false
            }
            ComparisonOperator.NOT_CONTAINS -> {
                !(actualValue?.toString()?.contains(expectedValue?.toString() ?: "") ?: false)
            }
            ComparisonOperator.STARTS_WITH -> {
                actualValue?.toString()?.startsWith(expectedValue?.toString() ?: "") ?: false
            }
            ComparisonOperator.ENDS_WITH -> {
                actualValue?.toString()?.endsWith(expectedValue?.toString() ?: "") ?: false
            }
            ComparisonOperator.GREATER_THAN -> {
                compareValues(actualValue, expectedValue) > 0
            }
            ComparisonOperator.LESS_THAN -> {
                compareValues(actualValue, expectedValue) < 0
            }
            ComparisonOperator.GREATER_THAN_OR_EQUAL -> {
                compareValues(actualValue, expectedValue) >= 0
            }
            ComparisonOperator.LESS_THAN_OR_EQUAL -> {
                compareValues(actualValue, expectedValue) <= 0
            }
            ComparisonOperator.IN -> {
                when (expectedValue) {
                    is List<*> -> expectedValue.contains(actualValue)
                    is Array<*> -> expectedValue.contains(actualValue)
                    else -> false
                }
            }
            ComparisonOperator.NOT_IN -> {
                when (expectedValue) {
                    is List<*> -> !expectedValue.contains(actualValue)
                    is Array<*> -> !expectedValue.contains(actualValue)
                    else -> true
                }
            }
            ComparisonOperator.IS_NULL -> actualValue == null
            ComparisonOperator.IS_NOT_NULL -> actualValue != null
        }
    }
    
    private fun getFieldValue(input: ClassificationInput, field: String): Any? {
        return when (field) {
            "title" -> input.title
            "description" -> input.description
            "demandType" -> input.demandType
            "priority" -> input.priority
            "businessJustification" -> input.businessJustification
            "assetType" -> input.assetType
            "assetOwner" -> input.assetOwner
            else -> {
                // Check custom fields
                if (field.startsWith("custom.")) {
                    val customField = field.substring(7)
                    input.customFields?.get(customField)
                } else {
                    null
                }
            }
        }
    }
    
    private fun compareValues(a: Any?, b: Any?): Int {
        if (a == null && b == null) return 0
        if (a == null) return -1
        if (b == null) return 1
        
        return when {
            a is Number && b is Number -> a.toDouble().compareTo(b.toDouble())
            a is String && b is String -> a.compareTo(b)
            else -> a.toString().compareTo(b.toString())
        }
    }
    
    fun parseRule(ruleJson: String): ClassificationRule {
        return objectMapper.readValue(ruleJson, ClassificationRule::class.java)
    }
    
    private fun saveClassificationResult(
        demandId: Long,
        classification: Classification,
        hash: String,
        appliedRule: DemandClassificationRule?,
        evaluationLog: String,
        inputData: String,
        confidenceScore: Double
    ) {
        val result = DemandClassificationResult(
            demand = null, // Will be set when loading with demand
            classification = classification,
            confidenceScore = confidenceScore,
            appliedRule = appliedRule,
            ruleEvaluationLog = evaluationLog,
            classificationHash = hash,
            inputData = inputData,
            classifiedAt = LocalDateTime.now(),
            isManualOverride = false
        )
        
        // Note: We'll need to handle demand association in the controller
        resultRepository.save(result)
    }
    
    private fun createDefaultResponse(evaluationLog: List<String>): ClassificationResponse {
        val timestamp = LocalDateTime.now()
        return ClassificationResponse(
            classification = Classification.C,
            classificationHash = DemandClassificationResult.generateHash(null, Classification.C, timestamp),
            confidenceScore = 0.5,
            appliedRuleName = "Default",
            evaluationLog = evaluationLog,
            timestamp = timestamp
        )
    }
    
    fun saveRule(rule: ClassificationRule, createdBy: User? = null): DemandClassificationRule {
        val existingRule = ruleRepository.findByName(rule.name)
        
        val dbRule = if (existingRule.isPresent) {
            val existing = existingRule.get()
            existing.ruleJson = objectMapper.writeValueAsString(rule)
            existing.description = rule.description
            existing
        } else {
            DemandClassificationRule(
                name = rule.name,
                description = rule.description,
                ruleJson = objectMapper.writeValueAsString(rule),
                active = true,
                priorityOrder = ruleRepository.count().toInt(),
                createdBy = createdBy
            )
        }
        
        return ruleRepository.save(dbRule)
    }
    
    fun importRulesFromFile(content: String, createdBy: User? = null): List<DemandClassificationRule> {
        val rules: List<ClassificationRule> = objectMapper.readValue(content, objectMapper.typeFactory.constructCollectionType(List::class.java, ClassificationRule::class.java))
        return rules.map { saveRule(it, createdBy) }
    }
    
    fun exportRulesToJson(): String {
        val activeRules = ruleRepository.findByActiveTrueOrderByPriorityOrderAsc()
        val rules = activeRules.map { parseRule(it.ruleJson) }
        return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(rules)
    }
}