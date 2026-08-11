package com.secman.controller

import com.secman.domain.AccountOnboardingChoice
import com.secman.domain.AccountOnboardingQuestion
import com.secman.domain.AccountOnboardingRule
import com.secman.domain.OnboardingInputType
import com.secman.dto.OnboardingChoiceRequest
import com.secman.dto.OnboardingChoiceResponse
import com.secman.dto.OnboardingCoverageResponse
import com.secman.dto.OnboardingCoverageRow
import com.secman.dto.OnboardingPreviewRequest
import com.secman.dto.OnboardingPreviewResponse
import com.secman.dto.OnboardingQuestionRequest
import com.secman.dto.OnboardingQuestionResponse
import com.secman.dto.OnboardingRuleRequest
import com.secman.dto.OnboardingRuleResponse
import com.secman.dto.PublicErrorResponse
import com.secman.dto.ReorderRequest
import com.secman.dto.SimulateOnboardingRequest
import com.secman.dto.SimulateOnboardingResponse
import com.secman.repository.AccountOnboardingChoiceRepository
import com.secman.repository.AccountOnboardingQuestionRepository
import com.secman.repository.AccountOnboardingRuleRepository
import com.secman.repository.UseCaseRepository
import com.secman.repository.UserRepository
import com.secman.service.AccountOnboardingRateLimiter
import com.secman.service.AccountOnboardingRuleMatcher
import com.secman.service.AccountOnboardingService
import com.secman.util.EmailAddressValidator
import io.micronaut.http.HttpResponse
import io.micronaut.http.HttpStatus
import io.micronaut.http.annotation.Body
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Delete
import io.micronaut.http.annotation.Get
import io.micronaut.http.annotation.PathVariable
import io.micronaut.http.annotation.Post
import io.micronaut.http.annotation.Put
import io.micronaut.scheduling.TaskExecutors
import io.micronaut.scheduling.annotation.ExecuteOn
import io.micronaut.security.annotation.Secured
import io.micronaut.security.authentication.Authentication
import io.micronaut.transaction.annotation.Transactional
import jakarta.validation.Valid
import org.slf4j.LoggerFactory

/**
 * Configure what the account owner is asked, and what their answers mean.
 *
 * **ADMIN or SECCHAMPION, for reads and writes alike.** That is a deliberate departure from
 * [DemandClassificationController], which lets both roles read but only ADMIN write: deciding
 * which security requirements apply to a cloud account is exactly a security champion's job,
 * and routing every rule edit through an ADMIN would make the feature unusable by the people
 * who own it. Both roles are enforced here *and* mirrored in the UI (Principle 2) — this
 * annotation is the boundary, the UI check is UX.
 *
 * Everything on this controller writes or reveals configuration only. The one endpoint with a
 * real-world side effect is [simulate], which sends mail and creates rows on purpose; it is
 * rate-limited and audited accordingly.
 */
@Controller("/api/account-onboarding")
@Secured("ADMIN", "SECCHAMPION")
@ExecuteOn(TaskExecutors.BLOCKING)
open class AccountOnboardingController(
    private val questionRepository: AccountOnboardingQuestionRepository,
    private val choiceRepository: AccountOnboardingChoiceRepository,
    private val ruleRepository: AccountOnboardingRuleRepository,
    private val useCaseRepository: UseCaseRepository,
    private val userRepository: UserRepository,
    private val ruleMatcher: AccountOnboardingRuleMatcher,
    private val onboardingService: AccountOnboardingService,
    private val rateLimiter: AccountOnboardingRateLimiter
) {
    private val log = LoggerFactory.getLogger(AccountOnboardingController::class.java)

    private fun error(status: HttpStatus, code: String, message: String): HttpResponse<PublicErrorResponse> =
        HttpResponse.status<PublicErrorResponse>(status).body(PublicErrorResponse(code, message))

    private fun badRequest(message: String) = error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message)
    private fun notFound(message: String) = error(HttpStatus.NOT_FOUND, "NOT_FOUND", message)
    private fun conflict(message: String) = error(HttpStatus.CONFLICT, "CONFLICT", message)

    // --- Questions ---------------------------------------------------------

    @Get("/questions")
    @Secured("ADMIN", "SECCHAMPION")
    @Transactional(readOnly = true)
    open fun listQuestions(): HttpResponse<*> {
        val questions = questionRepository.findAllByOrderByDisplayOrderAscIdAsc()
        return HttpResponse.ok(questions.map { toQuestionResponse(it) })
    }

    @Post("/questions")
    @Secured("ADMIN", "SECCHAMPION")
    @Transactional
    open fun createQuestion(@Valid @Body request: OnboardingQuestionRequest): HttpResponse<*> {
        validateQuestion(request)?.let { return badRequest(it) }
        if (questionRepository.count() >= AccountOnboardingQuestion.MAX_QUESTIONS) {
            return badRequest("At most ${AccountOnboardingQuestion.MAX_QUESTIONS} questions are supported")
        }
        if (questionRepository.findByQuestionKeyIgnoreCase(request.questionKey.trim()).isPresent) {
            return conflict("A question with key '${request.questionKey.trim()}' already exists")
        }
        val saved = questionRepository.save(
            AccountOnboardingQuestion(
                questionKey = request.questionKey.trim().lowercase(),
                label = request.label.trim(),
                helpText = request.helpText?.trim(),
                inputType = OnboardingInputType.valueOf(request.inputType),
                displayOrder = request.displayOrder,
                required = request.required,
                active = request.active
            )
        )
        log.info("AUDIT: operation=ONBOARDING_QUESTION_CREATE, questionKey={}, outcome=SUCCESS", saved.questionKey)
        return HttpResponse.created(toQuestionResponse(saved))
    }

    @Put("/questions/{id}")
    @Secured("ADMIN", "SECCHAMPION")
    @Transactional
    open fun updateQuestion(@PathVariable id: Long, @Valid @Body request: OnboardingQuestionRequest): HttpResponse<*> {
        validateQuestion(request)?.let { return badRequest(it) }
        val question = questionRepository.findById(id).orElse(null)
            ?: return notFound("Question $id not found")
        val newKey = request.questionKey.trim().lowercase()
        if (!newKey.equals(question.questionKey, ignoreCase = true)) {
            // The key is what an exported rule set and a stored answer reference. Renaming one
            // that rules depend on would silently re-point them.
            if (choiceRepository.countRulesReferencingQuestion(id) > 0) {
                return conflict("Cannot change the key of a question that rules reference")
            }
            if (questionRepository.findByQuestionKeyIgnoreCase(newKey).isPresent) {
                return conflict("A question with key '$newKey' already exists")
            }
            question.questionKey = newKey
        }
        question.label = request.label.trim()
        question.helpText = request.helpText?.trim()
        question.inputType = OnboardingInputType.valueOf(request.inputType)
        question.displayOrder = request.displayOrder
        question.required = request.required
        question.active = request.active
        val saved = questionRepository.update(question)
        log.info("AUDIT: operation=ONBOARDING_QUESTION_UPDATE, questionId={}, outcome=SUCCESS", id)
        return HttpResponse.ok(toQuestionResponse(saved))
    }

    /**
     * Delete a question and its choices.
     *
     * Refused (409) while any rule references one of its choices. The DB cascade would happily
     * remove the edges, leaving a rule matching a combination nobody can submit — a rule that
     * silently never fires is worse than a delete that refuses.
     */
    @Delete("/questions/{id}")
    @Secured("ADMIN", "SECCHAMPION")
    @Transactional
    open fun deleteQuestion(@PathVariable id: Long): HttpResponse<*> {
        if (!questionRepository.existsById(id)) return notFound("Question $id not found")
        val referencing = choiceRepository.countRulesReferencingQuestion(id)
        if (referencing > 0) {
            return conflict("$referencing rule(s) reference this question's choices - remove them first")
        }
        choiceRepository.deleteByQuestionId(id)
        questionRepository.deleteById(id)
        log.info("AUDIT: operation=ONBOARDING_QUESTION_DELETE, questionId={}, outcome=SUCCESS", id)
        return HttpResponse.noContent<Any>()
    }

    /** Reorder questions in one call — mirrors `PUT /api/classification/rules/priority`. */
    @Put("/questions/order")
    @Secured("ADMIN", "SECCHAMPION")
    @Transactional
    open fun reorderQuestions(@Valid @Body request: ReorderRequest): HttpResponse<*> {
        request.ids.forEachIndexed { index, questionId ->
            questionRepository.findById(questionId).ifPresent {
                it.displayOrder = index
                questionRepository.update(it)
            }
        }
        return HttpResponse.ok(questionRepository.findAllByOrderByDisplayOrderAscIdAsc().map { toQuestionResponse(it) })
    }

    // --- Choices -----------------------------------------------------------

    @Post("/questions/{questionId}/choices")
    @Secured("ADMIN", "SECCHAMPION")
    @Transactional
    open fun createChoice(
        @PathVariable questionId: Long,
        @Valid @Body request: OnboardingChoiceRequest
    ): HttpResponse<*> {
        val question = questionRepository.findById(questionId).orElse(null)
            ?: return notFound("Question $questionId not found")
        validateChoice(request)?.let { return badRequest(it) }
        if (choiceRepository.countByQuestionId(questionId) >= AccountOnboardingChoice.MAX_CHOICES_PER_QUESTION) {
            return badRequest(
                "At most ${AccountOnboardingChoice.MAX_CHOICES_PER_QUESTION} choices per question are supported"
            )
        }
        val key = request.choiceKey.trim().lowercase()
        if (choiceRepository.findByQuestionIdAndChoiceKeyIgnoreCase(questionId, key).isPresent) {
            return conflict("This question already has a choice keyed '$key'")
        }
        val saved = choiceRepository.save(
            AccountOnboardingChoice(
                question = question,
                choiceKey = key,
                label = request.label.trim(),
                displayOrder = request.displayOrder,
                active = request.active
            )
        )
        return HttpResponse.created(toChoiceResponse(saved))
    }

    @Put("/questions/{questionId}/choices/{choiceId}")
    @Secured("ADMIN", "SECCHAMPION")
    @Transactional
    open fun updateChoice(
        @PathVariable questionId: Long,
        @PathVariable choiceId: Long,
        @Valid @Body request: OnboardingChoiceRequest
    ): HttpResponse<*> {
        validateChoice(request)?.let { return badRequest(it) }
        val choice = choiceRepository.findById(choiceId).orElse(null)
            ?: return notFound("Choice $choiceId not found")
        if (choice.question.id != questionId) return notFound("Choice $choiceId not found")

        val newKey = request.choiceKey.trim().lowercase()
        if (!newKey.equals(choice.choiceKey, ignoreCase = true)) {
            if (choiceRepository.countRulesReferencingChoice(choiceId) > 0) {
                return conflict("Cannot change the key of a choice that rules reference")
            }
            if (choiceRepository.findByQuestionIdAndChoiceKeyIgnoreCase(questionId, newKey).isPresent) {
                return conflict("This question already has a choice keyed '$newKey'")
            }
            choice.choiceKey = newKey
        }
        choice.label = request.label.trim()
        choice.displayOrder = request.displayOrder
        choice.active = request.active
        return HttpResponse.ok(toChoiceResponse(choiceRepository.update(choice)))
    }

    @Delete("/questions/{questionId}/choices/{choiceId}")
    @Secured("ADMIN", "SECCHAMPION")
    @Transactional
    open fun deleteChoice(@PathVariable questionId: Long, @PathVariable choiceId: Long): HttpResponse<*> {
        val choice = choiceRepository.findById(choiceId).orElse(null)
            ?: return notFound("Choice $choiceId not found")
        if (choice.question.id != questionId) return notFound("Choice $choiceId not found")
        val referencing = choiceRepository.countRulesReferencingChoice(choiceId)
        if (referencing > 0) {
            return conflict("$referencing rule(s) reference this choice - remove them first")
        }
        choiceRepository.deleteById(choiceId)
        return HttpResponse.noContent<Any>()
    }

    // --- Rules -------------------------------------------------------------

    @Get("/rules")
    @Secured("ADMIN", "SECCHAMPION")
    @Transactional(readOnly = true)
    open fun listRules(): HttpResponse<*> =
        HttpResponse.ok(ruleRepository.findAllByOrderByPriorityOrderAscIdAsc().map { toRuleResponse(it) })

    @Post("/rules")
    @Secured("ADMIN", "SECCHAMPION")
    @Transactional
    open fun createRule(@Valid @Body request: OnboardingRuleRequest, authentication: Authentication): HttpResponse<*> {
        val rule = AccountOnboardingRule(name = request.name.trim())
        applyRule(rule, request)?.let { return it }
        if (ruleRepository.count() >= AccountOnboardingRule.MAX_RULES) {
            return badRequest("At most ${AccountOnboardingRule.MAX_RULES} rules are supported")
        }
        if (ruleRepository.findByNameIgnoreCase(request.name.trim()).isPresent) {
            return conflict("A rule named '${request.name.trim()}' already exists")
        }
        rule.createdBy = userRepository.findByUsername(authentication.name).orElse(null)
        val saved = ruleRepository.save(rule)
        log.info(
            "AUDIT: operation=ONBOARDING_RULE_CREATE, actor={}, rule={}, choices={}, useCases={}, outcome=SUCCESS",
            authentication.name, saved.name, saved.choices.size, saved.useCases.map { it.name }
        )
        return HttpResponse.created(toRuleResponse(saved))
    }

    @Put("/rules/{id}")
    @Secured("ADMIN", "SECCHAMPION")
    @Transactional
    open fun updateRule(
        @PathVariable id: Long,
        @Valid @Body request: OnboardingRuleRequest,
        authentication: Authentication
    ): HttpResponse<*> {
        val rule = ruleRepository.findById(id).orElse(null) ?: return notFound("Rule $id not found")
        val existingName = ruleRepository.findByNameIgnoreCase(request.name.trim()).orElse(null)
        if (existingName != null && existingName.id != id) {
            return conflict("A rule named '${request.name.trim()}' already exists")
        }
        rule.name = request.name.trim()
        applyRule(rule, request, excludeId = id)?.let { return it }
        val saved = ruleRepository.update(rule)
        log.info("AUDIT: operation=ONBOARDING_RULE_UPDATE, actor={}, rule={}, outcome=SUCCESS", authentication.name, saved.name)
        return HttpResponse.ok(toRuleResponse(saved))
    }

    @Delete("/rules/{id}")
    @Secured("ADMIN", "SECCHAMPION")
    @Transactional
    open fun deleteRule(@PathVariable id: Long, authentication: Authentication): HttpResponse<*> {
        if (!ruleRepository.existsById(id)) return notFound("Rule $id not found")
        ruleRepository.deleteById(id)
        log.info("AUDIT: operation=ONBOARDING_RULE_DELETE, actor={}, ruleId={}, outcome=SUCCESS", authentication.name, id)
        return HttpResponse.noContent<Any>()
    }

    /**
     * Populate a rule from a request, refusing every shape that would produce a rule the owner
     * can never satisfy or that resolves to nothing. Returns an error response, or null on success.
     */
    private fun applyRule(
        rule: AccountOnboardingRule,
        request: OnboardingRuleRequest,
        excludeId: Long? = null
    ): HttpResponse<PublicErrorResponse>? {
        if (request.name.isBlank()) return badRequest("Rule name is required")
        if (request.useCaseIds.isEmpty()) {
            return badRequest("A rule must resolve to at least one use case")
        }
        // Only the default rule may name no choices — everything else would match everything.
        if (!request.isDefault && request.choiceIds.isEmpty()) {
            return badRequest("A rule must name at least one choice, or be marked as the default fallback")
        }
        if (request.isDefault) {
            val otherDefault = ruleRepository.findByIsDefaultTrue().firstOrNull { it.id != excludeId }
            if (otherDefault != null) {
                return conflict("'${otherDefault.name}' is already the default fallback rule")
            }
        }

        val choices = request.choiceIds.map { choiceId ->
            choiceRepository.findById(choiceId).orElse(null)
                ?: return badRequest("Choice $choiceId does not exist")
        }
        val useCases = request.useCaseIds.map { useCaseId ->
            useCaseRepository.findById(useCaseId).orElse(null)
                ?: return badRequest("Use case $useCaseId does not exist")
        }
        // A combination naming two choices of the same SINGLE_SELECT question can never be
        // submitted — the owner picks one. Caught here so it cannot be saved at all.
        val singleSelectClash = choices
            .filter { !it.question.inputType.allowsMultiple() }
            .groupBy { it.question.id }
            .filterValues { it.size > 1 }
        if (singleSelectClash.isNotEmpty()) {
            val question = choices.first { it.question.id == singleSelectClash.keys.first() }.question
            return badRequest(
                "This rule names two answers to '${question.label}', which only accepts one - " +
                    "no owner could ever match it"
            )
        }

        rule.description = request.description?.trim()
        rule.active = request.active
        rule.priorityOrder = request.priorityOrder
        rule.isDefault = request.isDefault
        rule.choices = choices.toMutableSet()
        rule.useCases = useCases.toMutableSet()
        return null
    }

    // --- Coverage and preview ----------------------------------------------

    /**
     * Every combination of answers and what it would produce.
     *
     * The screen that makes a misconfigured rule set visible before an owner walks into it: a
     * row with no matching rule, or one resolving to zero requirements, is a dead end somebody
     * would otherwise discover only after clicking a link.
     */
    @Get("/rules/coverage")
    @Secured("ADMIN", "SECCHAMPION")
    @Transactional(readOnly = true)
    open fun coverage(): HttpResponse<*> {
        val (rows, truncated) = ruleMatcher.coverageMatrix()
        val matrix = onboardingService.describeRules()
        return HttpResponse.ok(
            OnboardingCoverageResponse(
                rows = rows.map {
                    OnboardingCoverageRow(
                        combination = it.combination,
                        matchedRules = it.matchedRuleNames,
                        useCases = it.useCaseNames,
                        requirementCount = it.requirementCount,
                        usedDefault = it.usedDefault,
                        deadEnd = it.isDeadEnd
                    )
                },
                truncated = truncated,
                hasDefaultRule = matrix.hasDefaultRule,
                releaseVersion = matrix.releaseVersion
            )
        )
    }

    /** Answers in, resolution out. Writes nothing — the admin twin of a dry run. */
    @Post("/rules/preview")
    @Secured("ADMIN", "SECCHAMPION")
    @Transactional(readOnly = true)
    open fun preview(@Valid @Body request: OnboardingPreviewRequest): HttpResponse<*> {
        val questions = questionRepository.findActiveWithChoices()
        val byKey = questions.associateBy { it.questionKey.lowercase() }
        val choiceIds = mutableSetOf<Long>()
        for (answer in request.answers) {
            val question = byKey[answer.questionKey.trim().lowercase()]
                ?: return badRequest("Unknown question '${EmailAddressValidator.sanitizeForEcho(answer.questionKey, 64)}'")
            for (choiceKey in answer.choiceKeys) {
                val choice = question.choices.firstOrNull { it.choiceKey.equals(choiceKey.trim(), ignoreCase = true) }
                    ?: return badRequest(
                        "Unknown choice '${EmailAddressValidator.sanitizeForEcho(choiceKey, 64)}' " +
                            "for question '${question.questionKey}'"
                    )
                choice.id?.let { choiceIds.add(it) }
            }
        }
        val resolution = ruleMatcher.resolve(choiceIds)
        return HttpResponse.ok(
            OnboardingPreviewResponse(
                matchedRules = resolution.matchedRuleNames,
                useCases = resolution.useCases.map { it.name }.sorted(),
                requirementCount = resolution.requirementCount,
                usedDefault = resolution.usedDefault,
                releaseVersion = resolution.releaseVersion,
                failure = resolution.failure?.name
            )
        )
    }

    /** The rule set as the CLI and MCP report it. Also what a GUIDED dry run prints. */
    @Get("/rules/matrix")
    @Secured("ADMIN", "SECCHAMPION")
    @Transactional(readOnly = true)
    open fun matrix(): HttpResponse<*> = HttpResponse.ok(onboardingService.describeRules())

    // --- Simulate ----------------------------------------------------------

    /**
     * Run the whole onboarding path against a made-up account id and address.
     *
     * Not a mock: it calls exactly what a real import calls, which is what makes it a genuine
     * test and also why it needs real controls. A live run creates rows and sends mail on
     * purpose — you must be able to click the link that arrives.
     *
     * The controls, and why each is here rather than being assumed:
     * - **ADMIN or SECCHAMPION**, enforced by the class-level `@Secured` and repeated here.
     * - **Shape validation** on both inputs. `ownerEmail` becomes an SMTP recipient, so it goes
     *   through the same anti-header-injection boundary every other recipient does.
     * - **Rate limited per actor**, with a much looser bucket for dry runs, so this cannot be
     *   turned into a mailer.
     * - **Every simulated mail says so and names the actor** (`{ifSimulated}`), which removes
     *   nearly all of the phishing value of an arbitrary-recipient mail sender.
     * - **Rows are marked `simulated`** and audited with actor, target and outcome, so test
     *   data is identifiable and sweepable rather than indistinguishable from production.
     */
    @Post("/simulate")
    @Secured("ADMIN", "SECCHAMPION")
    open fun simulate(
        @Valid @Body request: SimulateOnboardingRequest,
        authentication: Authentication
    ): HttpResponse<*> {
        if (!ACCOUNT_ID_PATTERN.matches(request.awsAccountId.trim())) {
            return badRequest("awsAccountId must be exactly 12 digits")
        }
        if (!EmailAddressValidator.isValidRecipient(request.ownerEmail)) {
            return badRequest("ownerEmail must be a valid single email address")
        }

        val bucket = if (request.dryRun) AccountOnboardingRateLimiter.Bucket.SIMULATE_DRY
        else AccountOnboardingRateLimiter.Bucket.SIMULATE
        if (!rateLimiter.tryAcquire(bucket, authentication.name)) {
            return error(
                HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED",
                "Too many simulations. Try again later, or use dryRun=true."
            )
        }

        val actor = userRepository.findByUsername(authentication.name).orElse(null)
        val plan = onboardingService.planFrom(
            explicitMode = request.mode,
            startRiskAssessment = false,
            sendWelcomeEmail = request.sendWelcomeEmail,
            useCaseName = request.riskAssessmentUseCase,
            deadlineDays = request.riskAssessmentDeadlineDays,
            expiryDays = request.questionnaireExpiryDays,
            simulated = true,
            simulatedBy = actor?.email?.ifBlank { actor.username } ?: authentication.name
        ) ?: return badRequest("mode is required")

        onboardingService.validateRequest(plan, newAccountCount = 1)?.let { return badRequest(it) }

        val outcome = onboardingService.onboardNewAccounts(
            newAccounts = listOf(
                com.secman.dto.NewAccountImportInfo(request.awsAccountId.trim(), listOf(request.ownerEmail.trim()))
            ),
            plan = plan,
            requestorUserId = actor?.id,
            dryRun = request.dryRun
        )

        log.info(
            "AUDIT: operation=ACCOUNT_ONBOARDING_SIMULATE, actor={}, awsAccountId={}, ownerEmail={}, " +
                "mode={}, dryRun={}, outcome={}",
            authentication.name, request.awsAccountId.trim(),
            EmailAddressValidator.sanitizeForEcho(request.ownerEmail), plan.mode, request.dryRun,
            if (outcome.onboarding.any { it.error != null }) "PARTIAL" else "SUCCESS"
        )

        return HttpResponse.ok(
            SimulateOnboardingResponse(
                awsAccountId = request.awsAccountId.trim(),
                ownerEmail = request.ownerEmail.trim(),
                mode = plan.mode.name,
                dryRun = request.dryRun,
                onboarding = outcome.onboarding,
                riskAssessments = outcome.riskAssessments,
                ruleMatrix = if (plan.mode == com.secman.domain.AccountOnboardingMode.GUIDED) {
                    onboardingService.describeRules()
                } else null
            )
        )
    }

    // --- Mapping -----------------------------------------------------------

    private fun toQuestionResponse(question: AccountOnboardingQuestion) = OnboardingQuestionResponse(
        id = question.id,
        questionKey = question.questionKey,
        label = question.label,
        helpText = question.helpText,
        inputType = question.inputType.name,
        displayOrder = question.displayOrder,
        required = question.required,
        active = question.active,
        choices = choiceRepository.findByQuestionIdOrderByDisplayOrderAscIdAsc(question.id!!)
            .map { toChoiceResponse(it) },
        referencedByRules = choiceRepository.countRulesReferencingQuestion(question.id!!)
    )

    private fun toChoiceResponse(choice: AccountOnboardingChoice) = OnboardingChoiceResponse(
        id = choice.id,
        choiceKey = choice.choiceKey,
        label = choice.label,
        displayOrder = choice.displayOrder,
        active = choice.active
    )

    private fun toRuleResponse(rule: AccountOnboardingRule) = OnboardingRuleResponse(
        id = rule.id,
        name = rule.name,
        description = rule.description,
        active = rule.active,
        priorityOrder = rule.priorityOrder,
        isDefault = rule.isDefault,
        choiceIds = rule.choices.mapNotNull { it.id }.sorted(),
        combination = rule.choices.map { "${it.question.questionKey}=${it.choiceKey}" }.sorted(),
        useCaseIds = rule.useCases.mapNotNull { it.id }.sorted(),
        useCases = rule.useCases.map { it.name }.sorted(),
        createdBy = rule.createdBy?.username
    )

    // --- Input validation --------------------------------------------------

    private fun validateQuestion(request: OnboardingQuestionRequest): String? {
        val key = request.questionKey.trim().lowercase()
        if (!AccountOnboardingQuestion.KEY_PATTERN.matches(key)) {
            return "questionKey must be lowercase letters, digits and hyphens (max 64 characters)"
        }
        if (request.label.isBlank()) return "label is required"
        if (request.label.length > 500) return "label must be at most 500 characters"
        if (request.helpText != null && request.helpText.length > 1024) {
            return "helpText must be at most 1024 characters"
        }
        if (OnboardingInputType.entries.none { it.name == request.inputType }) {
            return "inputType must be one of ${OnboardingInputType.entries.joinToString(", ") { it.name }}"
        }
        return null
    }

    private fun validateChoice(request: OnboardingChoiceRequest): String? {
        val key = request.choiceKey.trim().lowercase()
        if (!AccountOnboardingChoice.KEY_PATTERN.matches(key)) {
            return "choiceKey must be lowercase letters, digits and hyphens (max 64 characters)"
        }
        if (request.label.isBlank()) return "label is required"
        if (request.label.length > 500) return "label must be at most 500 characters"
        return null
    }

    companion object {
        private val ACCOUNT_ID_PATTERN = Regex("^\\d{12}$")
    }
}
