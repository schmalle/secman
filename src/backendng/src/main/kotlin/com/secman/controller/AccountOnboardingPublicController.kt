package com.secman.controller

import com.secman.domain.AccountOnboardingInvite
import com.secman.dto.PublicChoiceResponse
import com.secman.dto.PublicErrorResponse
import com.secman.dto.PublicQuestionResponse
import com.secman.dto.PublicQuestionnaireResponse
import com.secman.dto.SubmitAnswersRequest
import com.secman.dto.SubmitAnswersResponse
import com.secman.repository.AccountOnboardingQuestionRepository
import com.secman.service.AccountOnboardingRateLimiter
import com.secman.service.AccountOnboardingService
import com.secman.service.TrustedProxyResolver
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpResponse
import io.micronaut.http.HttpStatus
import io.micronaut.http.annotation.Body
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Get
import io.micronaut.http.annotation.PathVariable
import io.micronaut.http.annotation.Post
import io.micronaut.scheduling.TaskExecutors
import io.micronaut.scheduling.annotation.ExecuteOn
import io.micronaut.security.annotation.Secured
import io.micronaut.security.rules.SecurityRule
import jakarta.validation.Valid
import org.slf4j.LoggerFactory
import java.time.format.DateTimeFormatter

/**
 * The account owner's questionnaire — the one unauthenticated write path in SecMan.
 *
 * The capability is the token in the URL and nothing else. There is no session, no cookie and
 * no header involved, which shapes every decision here:
 *
 * - **`@Secured(SecurityRule.IS_ANONYMOUS)` is declared explicitly, on the class and on every
 *   method.** Not inherited, not omitted. `ResponseController`'s token route omits the
 *   annotation entirely; that is a pre-existing A01 gap, not a pattern to copy.
 * - **Every token failure returns the same bytes.** Unknown, malformed, expired, already used
 *   and cancelled all produce [notFoundBody]. A response that distinguished them would turn
 *   this endpoint into an oracle for "does this token exist".
 * - **The response discloses the minimum.** A masked account id, the expiry, and the questions.
 *   Never the owner's address, the assessor, the release, or anything about another account.
 * - **The full token is never logged.** Log lines carry [AccountOnboardingInvite.redact]'s
 *   8-character prefix. A token in a log is a credential in a log.
 * - **Rate limited per client address**, with a separate, much tighter bucket for *failed*
 *   lookups — the bucket an enumeration attempt lands in exclusively.
 * - **Single use is decided by the database**, in a guarded UPDATE claimed before the
 *   assessment is created. See [AccountOnboardingService.submitAnswers].
 *
 * CSRF does not apply: the request carries no ambient credential a browser would attach on the
 * caller's behalf, so there is nothing for a cross-site POST to ride on. The token must
 * therefore never be read from a cookie — only from the path.
 */
@Controller("/api/public/account-onboarding")
@Secured(SecurityRule.IS_ANONYMOUS)
@ExecuteOn(TaskExecutors.BLOCKING)
open class AccountOnboardingPublicController(
    private val onboardingService: AccountOnboardingService,
    private val questionRepository: AccountOnboardingQuestionRepository,
    private val rateLimiter: AccountOnboardingRateLimiter,
    private val trustedProxyResolver: TrustedProxyResolver
) {
    private val log = LoggerFactory.getLogger(AccountOnboardingPublicController::class.java)

    companion object {
        private val EXPIRY_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

        /**
         * The single message every token failure produces.
         *
         * Kept as one constant so a future edit cannot accidentally reintroduce a distinction
         * between "expired" and "never existed".
         */
        private const val NOT_FOUND_MESSAGE = "This link is invalid or has expired."
    }

    /**
     * Render the questionnaire.
     *
     * The path variable is a plain `{token}`: Micronaut's URI template parses `{64}` as a
     * variable, so a `[a-f0-9]{64}` constraint cannot live in the route. The format is checked
     * in code instead, and a malformed token is refused without touching the database.
     */
    @Get("/{token}")
    @Secured(SecurityRule.IS_ANONYMOUS)
    open fun getQuestionnaire(@PathVariable token: String, request: HttpRequest<*>): HttpResponse<*> {
        val client = clientKey(request)
        if (!rateLimiter.tryAcquire(AccountOnboardingRateLimiter.Bucket.PUBLIC_GET, client)) {
            return tooManyRequests(AccountOnboardingRateLimiter.Bucket.PUBLIC_GET)
        }
        if (!AccountOnboardingInvite.TOKEN_PATTERN.matches(token)) {
            return refuse(client, token, "malformed")
        }

        val invite = onboardingService.questionnaireFor(token)
            ?: return refuse(client, token, "unusable")

        val questions = questionRepository.findActiveWithChoices().map { question ->
            PublicQuestionResponse(
                questionKey = question.questionKey,
                label = question.label,
                helpText = question.helpText,
                inputType = question.inputType.name,
                required = question.required,
                choices = question.choices
                    .filter { it.active }
                    .sortedWith(compareBy({ it.displayOrder }, { it.id }))
                    .map { PublicChoiceResponse(it.choiceKey, it.label) }
            )
        }.filter { it.choices.isNotEmpty() }

        log.info(
            "Onboarding questionnaire opened: token={}, awsAccountId={}, questions={}",
            AccountOnboardingInvite.redact(token), invite.awsAccountId, questions.size
        )

        return HttpResponse.ok(
            PublicQuestionnaireResponse(
                // Masked: the holder of a guessed token must not learn a real account id.
                maskedAccountId = invite.maskedAccountId(),
                expiresAt = invite.expiresAt.format(EXPIRY_FORMAT),
                questions = questions
            )
        )
    }

    /**
     * Submit the answers.
     *
     * Answer-shape errors are reported specifically — the caller already proved they hold a
     * valid token, so there is nothing left to hide from them and a vague error would just
     * strand the owner. Token errors stay generic.
     */
    @Post("/{token}")
    @Secured(SecurityRule.IS_ANONYMOUS)
    open fun submit(
        @PathVariable token: String,
        @Valid @Body body: SubmitAnswersRequest,
        request: HttpRequest<*>
    ): HttpResponse<*> {
        val client = clientKey(request)
        if (!rateLimiter.tryAcquire(AccountOnboardingRateLimiter.Bucket.PUBLIC_POST, client)) {
            return tooManyRequests(AccountOnboardingRateLimiter.Bucket.PUBLIC_POST)
        }
        if (!AccountOnboardingInvite.TOKEN_PATTERN.matches(token)) {
            return refuse(client, token, "malformed")
        }

        val answers = body.answers.map { it.questionKey to it.choiceKeys }

        return try {
            when (val result = onboardingService.submitAnswers(token, answers)) {
                is AccountOnboardingService.SubmissionResult.Created ->
                    HttpResponse.ok(
                        SubmitAnswersResponse(
                            status = "SUBMITTED",
                            riskAssessmentId = result.riskAssessmentId,
                            useCases = result.useCases,
                            requirementCount = result.requirementCount,
                            deadline = result.deadline
                        )
                    )

                is AccountOnboardingService.SubmissionResult.NotUsable ->
                    refuse(client, token, "unusable")

                is AccountOnboardingService.SubmissionResult.InvalidAnswers ->
                    HttpResponse.badRequest(PublicErrorResponse("VALIDATION_ERROR", result.message))

                is AccountOnboardingService.SubmissionResult.Unresolved ->
                    HttpResponse.status<PublicErrorResponse>(HttpStatus.CONFLICT).body(
                        PublicErrorResponse(
                            result.failure.name,
                            "Your answers have been recorded. They do not yet map to a set of " +
                                "requirements, so a security champion will follow up with you."
                        )
                    )
            }
        } catch (e: Exception) {
            // Generic to the client, detail to the log (A05). A stack trace or a driver message
            // here would leak schema detail to an unauthenticated caller.
            log.error(
                "Onboarding submission failed for token {}: {}",
                AccountOnboardingInvite.redact(token), e.message, e
            )
            HttpResponse.serverError(
                PublicErrorResponse("INTERNAL_ERROR", "Your answers could not be processed. Please try again later.")
            )
        }
    }

    /**
     * The one refusal. Counts against the failed-lookup bucket and returns [NOT_FOUND_MESSAGE]
     * regardless of [reason] — which exists only for the server log.
     */
    private fun refuse(client: String, token: String, reason: String): HttpResponse<PublicErrorResponse> {
        rateLimiter.tryAcquire(AccountOnboardingRateLimiter.Bucket.FAILED_LOOKUP, client)
        log.info(
            "Onboarding token refused: token={}, reason={}",
            AccountOnboardingInvite.redact(token), reason
        )
        return HttpResponse.notFound(notFoundBody())
    }

    private fun notFoundBody() = PublicErrorResponse("NOT_FOUND", NOT_FOUND_MESSAGE)

    private fun tooManyRequests(bucket: AccountOnboardingRateLimiter.Bucket): HttpResponse<PublicErrorResponse> =
        HttpResponse.status<PublicErrorResponse>(HttpStatus.TOO_MANY_REQUESTS)
            .header("Retry-After", rateLimiter.retryAfterSeconds(bucket).toString())
            .body(PublicErrorResponse("RATE_LIMITED", "Too many requests. Please try again later."))

    /**
     * What the limiter counts against.
     *
     * `X-Forwarded-For` is trusted only when the immediate TCP peer is a configured trusted
     * proxy (`secman.account-onboarding.trusted-proxy-cidrs`, loopback by default — see
     * [TrustedProxyResolver]), and even then only its right-most, non-proxy hop is used —
     * never the first, since that is exactly the entry an unauthenticated caller controls.
     * Without a trusted peer, the header is ignored entirely and the socket address is used,
     * so this is spoofable only if a caller can reach the app directly, bypassing the proxy.
     * Used *only* for rate limiting, never for authorization.
     */
    private fun clientKey(request: HttpRequest<*>): String {
        val forwarded = request.headers.get("X-Forwarded-For")?.takeIf { it.length <= 256 }
        return trustedProxyResolver.resolveClientAddress(request.remoteAddress?.address, forwarded)
    }
}
