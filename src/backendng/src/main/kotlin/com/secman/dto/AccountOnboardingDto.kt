package com.secman.dto

import com.secman.domain.AccountOnboardingMode
import io.micronaut.core.annotation.Nullable
import io.micronaut.serde.annotation.Serdeable
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull

// ---------------------------------------------------------------------------
// Public questionnaire (unauthenticated, token-scoped)
// ---------------------------------------------------------------------------

/**
 * What the owner's browser is told about their invite.
 *
 * Deliberately thin. The caller holds a token and nothing else, so the response carries only
 * what is needed to render the form: no owner email, no assessor, no release, no other
 * account, and the AWS account id **masked** to its last four digits. A stranger who guesses
 * a token must not be able to confirm a full account id from the response.
 */
@Serdeable
data class PublicQuestionnaireResponse(
    /** `****6789`. Enough for the owner to recognise their own account, useless to anyone else. */
    val maskedAccountId: String,
    val expiresAt: String,
    val questions: List<PublicQuestionResponse>
)

@Serdeable
data class PublicQuestionResponse(
    val questionKey: String,
    val label: String,
    val helpText: String? = null,
    /** SINGLE_SELECT | MULTI_SELECT | BOOLEAN */
    val inputType: String,
    val required: Boolean,
    val choices: List<PublicChoiceResponse>
)

@Serdeable
data class PublicChoiceResponse(
    val choiceKey: String,
    val label: String
)

@Serdeable
data class SubmitAnswersRequest(
    @NotNull val answers: List<SubmittedAnswer> = emptyList()
)

@Serdeable
data class SubmittedAnswer(
    @NotBlank val questionKey: String,
    val choiceKeys: List<String> = emptyList()
)

/** Success shape of a submission. Still discloses nothing about other accounts. */
@Serdeable
data class SubmitAnswersResponse(
    val status: String,
    val riskAssessmentId: Long? = null,
    val useCases: List<String> = emptyList(),
    val requirementCount: Int = 0,
    val deadline: String? = null
)

/**
 * The one error shape the public endpoint returns.
 *
 * Every token failure — malformed, unknown, expired, already used, cancelled — answers with a
 * byte-identical body, so the endpoint cannot be used to distinguish "this token existed" from
 * "it never did". Validation errors of the *answers* are separate and may be specific: the
 * caller already proved they hold a valid token.
 */
@Serdeable
data class PublicErrorResponse(
    val error: String,
    val message: String
)

// ---------------------------------------------------------------------------
// Admin: questions, choices, rules
// ---------------------------------------------------------------------------

@Serdeable
data class OnboardingQuestionRequest(
    @NotBlank val questionKey: String,
    @NotBlank val label: String,
    @Nullable val helpText: String? = null,
    val inputType: String = "SINGLE_SELECT",
    val displayOrder: Int = 0,
    val required: Boolean = true,
    val active: Boolean = true
)

@Serdeable
data class OnboardingChoiceRequest(
    @NotBlank val choiceKey: String,
    @NotBlank val label: String,
    val displayOrder: Int = 0,
    val active: Boolean = true
)

@Serdeable
data class OnboardingQuestionResponse(
    val id: Long?,
    val questionKey: String,
    val label: String,
    val helpText: String?,
    val inputType: String,
    val displayOrder: Int,
    val required: Boolean,
    val active: Boolean,
    val choices: List<OnboardingChoiceResponse> = emptyList(),
    /** How many rules reference any of this question's choices — what blocks a delete. */
    val referencedByRules: Long = 0
)

@Serdeable
data class OnboardingChoiceResponse(
    val id: Long?,
    val choiceKey: String,
    val label: String,
    val displayOrder: Int,
    val active: Boolean
)

/**
 * A rule as the admin UI edits it.
 *
 * The combination is expressed as choice **ids**, not keys: the editor works from the list the
 * server just returned, and ids make the "(any)" case (a question simply absent from the list)
 * unambiguous.
 */
@Serdeable
data class OnboardingRuleRequest(
    @NotBlank val name: String,
    @Nullable val description: String? = null,
    val active: Boolean = true,
    val priorityOrder: Int = 0,
    val isDefault: Boolean = false,
    val choiceIds: List<Long> = emptyList(),
    val useCaseIds: List<Long> = emptyList()
)

@Serdeable
data class OnboardingRuleResponse(
    val id: Long?,
    val name: String,
    val description: String?,
    val active: Boolean,
    val priorityOrder: Int,
    val isDefault: Boolean,
    val choiceIds: List<Long> = emptyList(),
    /** `questionKey=choiceKey`, sorted — the human-readable form of [choiceIds]. */
    val combination: List<String> = emptyList(),
    val useCaseIds: List<Long> = emptyList(),
    val useCases: List<String> = emptyList(),
    val createdBy: String? = null
)

@Serdeable
data class ReorderRequest(
    val ids: List<Long> = emptyList()
)

/** One row of the coverage matrix — what a given set of answers would produce. */
@Serdeable
data class OnboardingCoverageRow(
    val combination: List<String>,
    val matchedRules: List<String>,
    val useCases: List<String>,
    val requirementCount: Int,
    val usedDefault: Boolean,
    /** True when an owner submitting this would hit a dead end. Rendered red in the UI. */
    val deadEnd: Boolean
)

@Serdeable
data class OnboardingCoverageResponse(
    val rows: List<OnboardingCoverageRow> = emptyList(),
    /** True when the combination space exceeded the cap and [rows] is a prefix, not the whole. */
    val truncated: Boolean = false,
    val hasDefaultRule: Boolean = false,
    val releaseVersion: String? = null
)

/** Answers in, resolution out. Writes nothing — the admin twin of a dry run. */
@Serdeable
data class OnboardingPreviewRequest(
    val answers: List<SubmittedAnswer> = emptyList()
)

@Serdeable
data class OnboardingPreviewResponse(
    val matchedRules: List<String> = emptyList(),
    val useCases: List<String> = emptyList(),
    val requirementCount: Int = 0,
    val usedDefault: Boolean = false,
    val releaseVersion: String? = null,
    /** NO_RULE_MATCHED | EMPTY_QUESTIONNAIRE | NO_ACTIVE_RELEASE, or null when the answers resolve. */
    val failure: String? = null
)

// ---------------------------------------------------------------------------
// Admin: simulate
// ---------------------------------------------------------------------------

/**
 * Run the whole onboarding path against a made-up account and address.
 *
 * The point of this surface is that it is not a parallel implementation: it calls exactly what
 * a real import calls. That makes it a genuine test of production behaviour, and it is also
 * why it is held to the same authorization bar and rate-limited — a live run really does mail
 * the address given.
 */
@Serdeable
data class SimulateOnboardingRequest(
    @NotBlank val awsAccountId: String,
    @NotBlank val ownerEmail: String,
    @NotNull val mode: AccountOnboardingMode,
    @Nullable val riskAssessmentUseCase: String? = null,
    @Nullable val riskAssessmentDeadlineDays: Int? = null,
    @Nullable val questionnaireExpiryDays: Int? = null,
    @Nullable val sendWelcomeEmail: Boolean? = null,
    val dryRun: Boolean = false
)

@Serdeable
data class SimulateOnboardingResponse(
    val awsAccountId: String,
    val ownerEmail: String,
    val mode: String,
    val dryRun: Boolean,
    val onboarding: List<AccountOnboardingInfo> = emptyList(),
    val riskAssessments: List<AccountRiskAssessmentInfo> = emptyList(),
    /** Present for GUIDED, so a dry run shows what the owner's answers could resolve to. */
    val ruleMatrix: OnboardingRuleMatrix? = null
)
