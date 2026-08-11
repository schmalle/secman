package com.secman.dto

import com.secman.domain.AccountOnboardingMode
import com.secman.domain.IpRangeType
import com.secman.domain.UserMapping
import io.micronaut.serde.annotation.Serdeable

/**
 * User Mapping Response DTO
 * Features: 013-user-mapping-upload, 020-i-want-to (IP mapping), 042-future-user-mappings
 */
@Serdeable
data class UserMappingResponse(
    val id: Long,
    val email: String,
    val awsAccountId: String?,
    val domain: String?,
    val ipAddress: String?,
    val ipRangeType: IpRangeType?,
    val ipCount: Long?,
    val userId: Long?,                 // Feature 042: Nullable user reference
    val appliedAt: String?,             // Feature 042: Timestamp when mapping was applied
    val isFutureMapping: Boolean,       // Feature 042: True if user=null AND appliedAt=null
    val createdAt: String,
    val updatedAt: String
)

@Serdeable
data class CreateUserMappingRequest(
    val email: String,
    val awsAccountId: String?,
    val domain: String?,
    val ipAddress: String?
)

@Serdeable
data class UpdateUserMappingRequest(
    val email: String,
    val awsAccountId: String?,
    val domain: String?,
    val ipAddress: String?
)

@Serdeable
data class BulkUserMappingEntry(
    val email: String,
    val awsAccountId: String? = null,
    val domain: String? = null
)

@Serdeable
data class BulkUserMappingRequest(
    val mappings: List<BulkUserMappingEntry>,
    val dryRun: Boolean = false,
    val notifyNewAccounts: Boolean = false,
    val notifyAddress: String? = null,
    /**
     * When true, a risk assessment is started for the owner of every
     * brand-new (DB-wide) AWS account created by this import.
     * Requires [riskAssessmentUseCase]; deadline defaults to 7 days.
     *
     * Predates [onboardingMode] and is still what every existing client sends. On its own it
     * resolves to [AccountOnboardingMode.DIRECT] with no welcome mail — byte-identical to the
     * behaviour before onboarding modes existed. See [AccountOnboardingMode.resolve].
     */
    val startRiskAssessment: Boolean = false,
    /** Name of the use case the auto-started risk assessments are based on. */
    val riskAssessmentUseCase: String? = null,
    /** Days from today until the risk assessment deadline (endDate). Default 7. */
    val riskAssessmentDeadlineDays: Int? = null,

    /**
     * What to do for the owner of every brand-new AWS account this import introduces.
     *
     * Null means "fall back to [startRiskAssessment]" — which is what keeps every existing
     * caller working unchanged. Passing this explicitly opts into the welcome mail as well;
     * see [sendWelcomeEmail].
     */
    val onboardingMode: AccountOnboardingMode? = null,

    /**
     * Override for the welcome mail.
     *
     * Null resolves to "true iff [onboardingMode] was passed explicitly". That asymmetry is
     * the backward-compatibility contract: an existing caller sending only
     * `startRiskAssessment=true` must not suddenly start sending owners a second mail.
     */
    val sendWelcomeEmail: Boolean? = null,

    /**
     * Days the [AccountOnboardingMode.GUIDED] questionnaire link stays valid.
     * Default 14, range 1..90. Ignored in the other two modes.
     */
    val questionnaireExpiryDays: Int? = null
)

@Serdeable
data class NewAccountImportInfo(
    val awsAccountId: String,
    val emails: List<String>
)

/**
 * Outcome of auto-starting a risk assessment for one (new AWS account, owner)
 * pair during a mapping import. Exactly one of three shapes:
 *
 * - **started**  — [riskAssessmentId] set, [skipped] false, [error] null
 * - **skipped**  — [skipped] true and [skipReason] set, pointing at the already-open
 *                  assessment in [riskAssessmentId]. Not a failure: the import did what
 *                  it was asked to, the assessment simply already existed. Callers must
 *                  not count these towards a non-zero exit status.
 * - **failed**   — [error] set
 */
@Serdeable
data class AccountRiskAssessmentInfo(
    val awsAccountId: String,
    val ownerEmail: String,
    val riskAssessmentId: Long? = null,
    val assessor: String? = null,
    val endDate: String? = null,
    /**
     * Use case(s) the assessment is scoped to, comma-joined.
     *
     * Kept singular in name and shape for the clients that already read it. Guided onboarding
     * scopes an assessment to the *union* of every matching rule's use cases, so this can now
     * carry several names; [useCases] is the structured form.
     */
    val useCase: String? = null,
    /** The same use cases, individually. Null for assessments started before this existed. */
    val useCases: List<String>? = null,
    /** Version of the ACTIVE release the assessment is pinned to (the "standard"). */
    val releaseVersion: String? = null,
    /** Number of requirements the pinned release contributes for [useCase]. */
    val requirementCount: Int? = null,
    /** True when an open assessment already existed and none was created (idempotent no-op). */
    val skipped: Boolean = false,
    /** Why the pair was skipped. Set iff [skipped]. */
    val skipReason: String? = null,
    val error: String? = null
)

@Serdeable
data class BulkUserMappingResponse(
    val totalProcessed: Int,
    val created: Int,
    val createdPending: Int,
    val skipped: Int,
    val errors: List<String>,
    val comparison: MappingComparisonResponse? = null,
    val newAccounts: List<NewAccountImportInfo> = emptyList(),
    val notificationSent: Boolean = false,
    val notificationRecipient: String? = null,
    val notificationError: String? = null,
    /** Auto-started risk assessments (one entry per new account/owner pair). */
    val riskAssessments: List<AccountRiskAssessmentInfo> = emptyList(),
    /**
     * What onboarding did for each new account/owner pair — welcome mails, questionnaire
     * invites, and (in DIRECT mode) a pointer to the assessment also listed in
     * [riskAssessments]. Empty when no onboarding mode was requested.
     */
    val onboarding: List<AccountOnboardingInfo> = emptyList()
)

/**
 * Outcome of onboarding one (new AWS account, owner) pair.
 *
 * Deliberately the same three shapes as [AccountRiskAssessmentInfo], so every surface — the
 * CLI printout, the MCP result, the admin UI — renders both lists by one rule:
 *
 * - **done**     — [error] null, [skipped] false. What was done depends on [mode]:
 *                  a welcome mail ([welcomeEmailSent]), a questionnaire invite
 *                  ([questionnaireInviteId] + [questionnaireExpiresAt]), and/or an
 *                  assessment ([riskAssessmentId]).
 * - **skipped**  — [skipped] true and [skipReason] set. A no-op, not a failure: the pair
 *                  already has a live invite or assessment. Callers must not let these drive
 *                  a non-zero exit status.
 * - **failed**   — [error] set.
 *
 * [dryRun] entries describe what *would* have happened. Nothing was persisted or sent, and in
 * GUIDED mode no token was minted — a token in a dry-run log would be a leaked credential.
 */
@Serdeable
data class AccountOnboardingInfo(
    val awsAccountId: String,
    val ownerEmail: String,
    val mode: String,
    val welcomeEmailSent: Boolean = false,
    val questionnaireInviteId: Long? = null,
    val questionnaireExpiresAt: String? = null,
    val riskAssessmentId: Long? = null,
    val dryRun: Boolean = false,
    val skipped: Boolean = false,
    val skipReason: String? = null,
    val error: String? = null
)

/** One active rule, rendered for a dry run, the CLI matrix and the MCP listing. */
@Serdeable
data class OnboardingRuleSummary(
    val id: Long?,
    val name: String,
    val description: String? = null,
    val isDefault: Boolean = false,
    val active: Boolean = true,
    /** `questionKey=choiceKey` per choice, sorted. Empty for the default rule. */
    val combination: List<String> = emptyList(),
    val useCases: List<String> = emptyList()
)

/**
 * What a GUIDED dry run reports instead of minting anything: the questions that would be
 * asked and the complete rule matrix that would apply.
 */
@Serdeable
data class OnboardingRuleMatrix(
    val questionCount: Int,
    val choiceCount: Int,
    val activeRuleCount: Int,
    val hasDefaultRule: Boolean,
    val rules: List<OnboardingRuleSummary> = emptyList(),
    /** Every use case any active rule can resolve to. */
    val reachableUseCases: List<String> = emptyList(),
    /** Requirements the ACTIVE release contributes for [reachableUseCases]. */
    val reachableRequirementCount: Int = 0,
    val releaseVersion: String? = null
)

@Serdeable
data class MappingComparisonResponse(
    val dbMappingCount: Int,
    val fileMappingCount: Int,
    val newCount: Int,
    val unchangedCount: Int,
    val removedCount: Int
)

/**
 * Convert UserMapping entity to UserMappingResponse DTO
 * Feature 042: Extended to include user reference and appliedAt timestamp
 */
fun UserMapping.toResponse(): UserMappingResponse {
    val ipCount = if (ipRangeStart != null && ipRangeEnd != null) {
        ipRangeEnd!! - ipRangeStart!! + 1
    } else {
        null
    }

    return UserMappingResponse(
        id = this.id!!,
        email = this.email,
        awsAccountId = this.awsAccountId,
        domain = this.domain,
        ipAddress = this.ipAddress,
        ipRangeType = this.ipRangeType,
        ipCount = ipCount,
        userId = this.user?.id,                            // Feature 042
        appliedAt = this.appliedAt?.toString(),            // Feature 042
        isFutureMapping = this.isFutureMapping(),          // Feature 042
        createdAt = this.createdAt.toString(),
        updatedAt = this.updatedAt.toString()
    )
}
