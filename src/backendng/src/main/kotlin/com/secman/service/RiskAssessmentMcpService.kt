package com.secman.service

import com.secman.domain.AnswerType
import com.secman.domain.AssessmentBasisType
import com.secman.domain.AwsAccount
import com.secman.domain.Response
import com.secman.domain.ResponseSource
import com.secman.domain.RiskAssessment
import com.secman.dto.mcp.McpExecutionContext
import com.secman.repository.AwsAccountRepository
import com.secman.repository.AssetRepository
import com.secman.repository.RequirementRepository
import com.secman.repository.ResponseRepository
import com.secman.repository.RiskAssessmentRepository
import com.secman.repository.UseCaseRepository
import com.secman.repository.UserRepository
import io.micronaut.data.model.Pageable
import io.micronaut.transaction.annotation.Transactional
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory
import java.time.LocalDate

/**
 * Transactional application boundary for the MCP risk-assessment workflow.
 *
 * Every method receives the verified delegation context. Assessment ids and
 * requirement ids are untrusted MCP input and are resolved here before data is
 * returned or changed.
 */
@Singleton
open class RiskAssessmentMcpService(
    private val riskAssessmentRepository: RiskAssessmentRepository,
    private val responseRepository: ResponseRepository,
    private val requirementRepository: RequirementRepository,
    private val useCaseRepository: UseCaseRepository,
    private val awsAccountRepository: AwsAccountRepository,
    private val assetRepository: AssetRepository,
    private val userRepository: UserRepository,
    private val assetFilter: AssetFilterService,
    private val workflow: AssessmentWorkflowService,
    private val access: RiskAssessmentAccessService,
    private val releaseRequirementScopeService: ReleaseRequirementScopeService
) {
    private val log = LoggerFactory.getLogger(RiskAssessmentMcpService::class.java)

    data class AnswerInput(val requirementId: Long, val answerType: AnswerType, val comment: String?)

    data class OutstandingReminder(
        val assessmentId: Long,
        val recipientEmail: String,
        val awsAccountId: String?,
        val useCaseNames: List<String>,
        val endDate: LocalDate,
        val unansweredCount: Int,
        val requirementCount: Int,
        val assignmentId: Long = 0,
        val assignmentVersion: Long = 1,
        val accountless: Boolean = false
    )

    @Transactional(readOnly = true)
    open fun list(
        context: McpExecutionContext,
        status: String?,
        useCaseName: String?,
        page: Int,
        pageSize: Int
    ): Map<String, Any> {
        val viewerId = context.delegatedUserId ?: throw SecurityException("Delegation is required")
        val normalizedStatus = status?.uppercase()
        require(normalizedStatus == null || normalizedStatus in VALID_STATUSES) {
            "status must be STARTED or COMPLETED"
        }
        val privileged = context.isAdmin || context.delegatedUserRoles?.contains("SECCHAMPION") == true
        val result = riskAssessmentRepository.findForMcp(
            normalizedStatus,
            useCaseName?.trim()?.takeIf { it.isNotBlank() },
            viewerId,
            privileged,
            if (privileged) setOf(-1L) else context.accessibleAssetIds.orEmpty().ifEmpty { setOf(-1L) },
            if (privileged) setOf("") else assetFilter.getAccessibleAwsAccountIds(authentication(context)).ifEmpty { setOf("") },
            Pageable.from(page, pageSize)
        )
        return mapOf(
            // The repository predicate keeps paging/counts scoped, while this Kotlin
            // check remains the authorization boundary if that query changes later.
            "assessments" to result.content.filter { canAccess(context, it) }.map(::assessmentSummary),
            "page" to result.pageNumber,
            "pageSize" to result.size,
            "totalPages" to result.totalPages,
            "totalElements" to result.totalSize
        )
    }

    @Transactional(readOnly = true)
    open fun questionnaire(context: McpExecutionContext, assessmentId: Long): Map<String, Any> {
        val assessment = accessibleAssessment(context, assessmentId)
        val requirements = access.visibleRequirements(assessment, authentication(context), requirementsFor(assessment))
        val requirementIds = requirements.mapNotNull { it.id }.toSet()
        val responses = responseRepository.findByRiskAssessmentId(assessmentId).associateBy { it.requirement.id }
        return mapOf(
            "assessment" to assessmentSummary(assessment),
            "requirements" to requirements.map { requirement ->
                val response = responses[requirement.id]
                mapOf(
                    "id" to requirement.id,
                    "internalId" to requirement.internalId,
                    "shortreq" to requirement.shortreq,
                    "details" to requirement.details,
                    "chapter" to requirement.chapter,
                    "norm" to requirement.norm,
                    "response" to response?.let(::responseView)
                )
            },
            "answeredCount" to responses.count { it.key in requirementIds },
            "requirementCount" to requirements.size,
            "isComplete" to (requirements.isNotEmpty() && requirements.all { responses[it.id]?.answerType != null })
        )
    }

    @Transactional(readOnly = true)
    open fun answers(context: McpExecutionContext, assessmentId: Long): Map<String, Any> {
        val assessment = accessibleAssessment(context, assessmentId)
        val requirementIds = access.visibleRequirements(assessment, authentication(context), requirementsFor(assessment)).mapNotNull { it.id }.toSet()
        val answers = responseRepository.findByRiskAssessmentId(assessmentId)
            .filter { it.requirement.id in requirementIds }
            .map { response ->
                mapOf(
                    "requirementId" to response.requirement.id,
                    "internalId" to response.requirement.internalId,
                    "shortreq" to response.requirement.shortreq,
                    "answerType" to response.answerType?.name,
                    "comment" to response.comment,
                    "source" to response.source.name,
                    "respondentEmail" to response.respondentEmail,
                    "updatedAt" to response.updatedAt?.toString()
                )
            }
        return mapOf(
            "assessment" to assessmentSummary(assessment),
            "answers" to answers,
            "answeredCount" to answers.count { it["answerType"] != null },
            "requirementCount" to requirementIds.size
        )
    }

    @Transactional
    open fun create(
        context: McpExecutionContext,
        awsAccountId: String?,
        assetId: Long?,
        useCaseIds: List<Long>,
        assessorEmail: String,
        respondentEmail: String,
        endDate: LocalDate,
        notes: String?
    ): Map<String, Any?> {
        if (!access.isGlobal(authentication(context))) throw SecurityException("Assessment manager required")
        require((awsAccountId == null) != (assetId == null)) {
            "Provide exactly one assessment basis: awsAccountId or assetId"
        }
        if (awsAccountId != null) {
            require(AWS_ACCOUNT_ID.matches(awsAccountId)) { "awsAccountId must contain exactly 12 digits" }
        }
        require(!endDate.isBefore(LocalDate.now())) { "endDate must be today or later" }
        require(useCaseIds.isNotEmpty()) { "useCaseIds must not be empty" }
        require(useCaseIds.size <= MAX_USE_CASES) { "at most $MAX_USE_CASES use cases may be selected" }
        require(useCaseIds.distinct().size == useCaseIds.size) { "useCaseIds must not contain duplicates" }
        val awsAccount = awsAccountId?.let { findOrCreateAwsAccount(it, context.delegatedUserEmail) }
        val asset = assetId?.let { id ->
            require(context.canAccessAsset(id)) { "Asset not found" }
            assetRepository.findById(id).orElseThrow { NoSuchElementException("Asset not found") }
        }
        val useCases = useCaseIds.map { useCaseId ->
            useCaseRepository.findById(useCaseId).orElseThrow {
                NoSuchElementException("Use case $useCaseId not found")
            }
        }
        val activeRelease = releaseRequirementScopeService.findActiveRelease()
            ?: throw IllegalStateException("No ACTIVE release exists to base the risk assessment on")
        val activeReleaseId = activeRelease.id
            ?: throw IllegalStateException("ACTIVE release has no id")
        useCaseIds.forEach { useCaseId ->
            require(releaseRequirementScopeService.requirementsForRelease(activeReleaseId, useCaseId).isNotEmpty()) {
                "Use case $useCaseId has no requirements in ACTIVE release '${activeRelease.version}'"
            }
        }
        val assessor = userRepository.findByEmailIgnoreCase(assessorEmail.trim())
            .orElseThrow { NoSuchElementException("Assessor not found") }
        val respondent = userRepository.findByEmailIgnoreCase(respondentEmail.trim())
            .orElseThrow { NoSuchElementException("Respondent not found") }
        val requestor = userRepository.findById(context.delegatedUserId!!)
            .orElseThrow { NoSuchElementException("Delegated requestor not found") }

        val assessment = riskAssessmentRepository.save(
            RiskAssessment(
                startDate = LocalDate.now(),
                endDate = endDate,
                assessmentBasisType = if (asset != null) AssessmentBasisType.ASSET else AssessmentBasisType.AWS_ACCOUNT,
                assessmentBasisId = asset?.id ?: awsAccount!!.id!!,
                assessor = assessor,
                requestor = requestor,
                respondent = respondent,
                awsAccount = awsAccount,
                asset = asset,
                notes = notes?.trim()?.takeIf { it.isNotBlank() },
                useCases = useCases.toMutableSet(),
                lockedRelease = activeRelease,
                isReleaseLocked = true,
                contentSnapshotTaken = true
            )
        )
        workflow.initialize(assessment)
        log.info(
            "MCP actor {} created risk assessment {} for basis {} and respondent {}",
            context.delegatedUserId, assessment.id, awsAccountId ?: "asset:$assetId", respondent.id
        )
        return assessmentSummary(assessment)
    }

    @Transactional(readOnly = true)
    open fun prepareOutstandingReminder(context: McpExecutionContext, assessmentId: Long, email: String? = null): OutstandingReminder =
        workflow.prepareReminder(assessmentId, authentication(context), email)

    @Transactional
    open fun saveAnswers(
        context: McpExecutionContext,
        assessmentId: Long,
        answers: List<AnswerInput>
    ): Map<String, Any> {
        val initiator = workflow.mcpInitiator(context.apiKeyId, context.delegatedUserId ?: throw SecurityException("Delegation required"))
        val saved = workflow.save(assessmentId, authentication(context), answers.map {
            AssessmentWorkflowService.Answer(it.requirementId, it.answerType, it.comment)
        }, initiator, context.apiKeyId)
        return mapOf("assessmentId" to assessmentId, "savedCount" to saved.size)
    }

    @Transactional
    open fun submit(context: McpExecutionContext, assessmentId: Long): Map<String, Any> {
        workflow.mcpInitiator(context.apiKeyId, context.delegatedUserId ?: throw SecurityException("Delegation required"))
        val assessment = workflow.submit(assessmentId, authentication(context))
        return mapOf("assessmentId" to assessmentId, "status" to assessment.status)
    }

    @Transactional(readOnly = true)
    open fun evaluate(context: McpExecutionContext, assessmentId: Long): Map<String, Any> {
        val assessment = accessibleAssessment(context, assessmentId)
        val viewerId = context.delegatedUserId!!
        val canEvaluate = context.isAdmin ||
            context.delegatedUserRoles?.contains("SECCHAMPION") == true ||
            access.canReview(assessment, authentication(context))
        if (!canEvaluate) throw SecurityException("Only the assessor, requestor, ADMIN or SECCHAMPION may evaluate")
        check(assessment.status == "COMPLETED") { "Only a completed assessment can be evaluated" }

        val requirements = requirementsFor(assessment)
        val responses = responseRepository.findByRiskAssessmentId(assessmentId).associateBy { it.requirement.id }
        val answerCounts = AnswerType.entries.associate { type ->
            type.name to responses.values.count { it.answerType == type }
        }
        val findings = requirements.mapNotNull { requirement ->
            val response = responses[requirement.id] ?: return@mapNotNull null
            if (response.answerType == AnswerType.YES) return@mapNotNull null
            mapOf(
                "requirementId" to requirement.id,
                "internalId" to requirement.internalId,
                "shortreq" to requirement.shortreq,
                "answerType" to response.answerType?.name,
                "comment" to response.comment
            )
        }
        return mapOf(
            "assessment" to assessmentSummary(assessment),
            "verdict" to if (answerCounts[AnswerType.NO.name] == 0) "COMPLIANT" else "NON_COMPLIANT",
            "answerCounts" to answerCounts,
            "requirementCount" to requirements.size,
            "findings" to findings
        )
    }

    private fun accessibleAssessment(context: McpExecutionContext, assessmentId: Long): RiskAssessment {
        val assessment = riskAssessmentRepository.findById(assessmentId)
            .orElseThrow { NoSuchElementException("Risk assessment not found") }
        if (context.delegatedUserId == null) throw SecurityException("Delegation is required")
        if (!canAccess(context, assessment)) throw NoSuchElementException("Risk assessment not found")
        return assessment
    }

    private fun authentication(context: McpExecutionContext): io.micronaut.security.authentication.Authentication {
        val user = context.delegatedUserId?.let { userRepository.findById(it).orElse(null) }
            ?: throw SecurityException("Current delegate required")
        if (!user.enabled) throw SecurityException("Delegate disabled")
        return io.micronaut.security.authentication.Authentication.build(user.username, user.roles.map { it.name },
            mapOf("userId" to user.id!!, "email" to user.email))
    }

    private fun canAccess(context: McpExecutionContext, assessment: RiskAssessment) =
        access.canView(assessment, authentication(context))

    private fun requirementsFor(assessment: RiskAssessment) = workflow.requirementsFor(assessment)

    private fun assessmentSummary(assessment: RiskAssessment): Map<String, Any?> = mapOf(
        "id" to assessment.id,
        "status" to assessment.status,
        "basisType" to assessment.assessmentBasisType.name,
        "basisId" to assessment.assessmentBasisId,
        "awsAccountId" to assessment.awsAccount?.awsAccountId,
        "asset" to assessment.getAssetBasis()?.let {
            mapOf("id" to it.id, "name" to it.name, "type" to it.type, "uri" to it.uri)
        },
        "startDate" to assessment.startDate.toString(),
        "endDate" to assessment.endDate.toString(),
        "useCases" to assessment.useCases.map { mapOf("id" to it.id, "name" to it.name) },
        "assessor" to assessment.assessor.email,
        "requestor" to assessment.requestor.email,
        "respondent" to assessment.respondent?.email,
        "releaseVersion" to assessment.lockedRelease?.version,
        "notes" to assessment.notes
    )

    private fun responseView(response: Response): Map<String, Any?> = mapOf(
        "answerType" to response.answerType?.name,
        "comment" to response.comment,
        "source" to response.source.name,
        "respondentEmail" to response.respondentEmail,
        "updatedAt" to response.updatedAt?.toString()
    )

    private fun findOrCreateAwsAccount(accountId: String, actorEmail: String?): AwsAccount {
        awsAccountRepository.findByAwsAccountId(accountId).orElse(null)?.let { return it }
        return try {
            awsAccountRepository.save(AwsAccount(awsAccountId = accountId, updatedBy = actorEmail))
        } catch (e: Exception) {
            awsAccountRepository.findByAwsAccountId(accountId).orElseThrow { e }
        }
    }

    companion object {
        val VALID_STATUSES = setOf("STARTED", "COMPLETED")
        const val MAX_ANSWERS = 200
        const val MAX_USE_CASES = 50
        const val MAX_COMMENT_LENGTH = 4000
        val AWS_ACCOUNT_ID = Regex("^\\d{12}$")
    }
}
