package com.secman.cli.service

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpResponse
import io.micronaut.http.MediaType
import io.micronaut.http.client.DefaultHttpClientConfiguration
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.netty.DefaultHttpClient
import io.micronaut.http.ssl.AbstractClientSslConfiguration
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory
import java.net.URI
import java.time.Duration

/**
 * HTTP client for the account-onboarding endpoints.
 *
 * Kept separate from [UserMappingCliService] because it talks to a different controller and
 * carries no mapping-import concerns — but it authenticates the same way (the JWT arrives only
 * in the `secman_auth` Set-Cookie and is re-sent as a bearer token), so that logic is mirrored
 * rather than reinvented.
 */
@Singleton
class AccountOnboardingCliService(
    private val javaHttpClientFactory: CliJavaHttpClientFactory
) {
    private val log = LoggerFactory.getLogger(AccountOnboardingCliService::class.java)
    private val objectMapper = jacksonObjectMapper()

    private var httpClient: HttpClient? = null
    private var insecureMode: Boolean = false

    fun initHttpClient(backendUrl: String, insecure: Boolean) {
        val jvmInsecure = System.getProperty("secman.ssl.insecure")?.lowercase() == "true"
        this.insecureMode = insecure || jvmInsecure
        val config = DefaultHttpClientConfiguration().apply {
            setReadTimeout(Duration.ofSeconds(120))
            setConnectTimeout(Duration.ofSeconds(30))
        }
        if (this.insecureMode) {
            log.warn("SSL certificate verification is DISABLED (--insecure mode)")
            (config.sslConfiguration as AbstractClientSslConfiguration).isInsecureTrustAllCertificates = true
        }
        @Suppress("DEPRECATION")
        httpClient = DefaultHttpClient(URI.create(backendUrl), config)
    }

    private fun getClient(): HttpClient = httpClient
        ?: throw IllegalStateException("HTTP client not initialized. Call initHttpClient() first.")

    /**
     * Log in and return the JWT.
     *
     * The token comes back only in `Set-Cookie: secman_auth=…` — the login response body never
     * carries it — and external clients re-send it as `Authorization: Bearer`. Same contract as
     * [UserMappingCliService.authenticate].
     */
    fun authenticate(username: String, password: String, backendUrl: String): String? {
        return try {
            val request = HttpRequest.POST(
                "$backendUrl/api/auth/login",
                mapOf("username" to username, "password" to password)
            ).contentType(MediaType.APPLICATION_JSON)

            val response: HttpResponse<Map<*, *>> = getClient().toBlocking().exchange(request, Map::class.java)
            if (response.status.code != 200) {
                log.error("Authentication failed: status={}", response.status)
                return null
            }
            response.headers.getAll("Set-Cookie")
                ?.flatMap { it.split(";") }
                ?.firstOrNull { it.trim().startsWith("secman_auth=") }
                ?.substringAfter("=")
                ?.trim()
                ?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            log.error("Authentication error: {}", e.message)
            null
        }
    }

    /**
     * `POST /api/account-onboarding/simulate`.
     *
     * Every field the operator did not set is omitted rather than sent as null, so the backend
     * applies its own defaults instead of receiving an explicit "unset".
     */
    fun simulate(
        backendUrl: String,
        authToken: String,
        awsAccountId: String,
        ownerEmail: String,
        mode: String,
        riskUseCase: String? = null,
        riskDeadlineDays: Int? = null,
        questionnaireExpiryDays: Int? = null,
        sendWelcomeEmail: Boolean? = null,
        dryRun: Boolean = false
    ): SimulateOnboardingResult {
        val bodyMap = buildMap<String, Any> {
            put("awsAccountId", awsAccountId)
            put("ownerEmail", ownerEmail)
            put("mode", mode)
            put("dryRun", dryRun)
            riskUseCase?.let { put("riskAssessmentUseCase", it) }
            riskDeadlineDays?.let { put("riskAssessmentDeadlineDays", it) }
            questionnaireExpiryDays?.let { put("questionnaireExpiryDays", it) }
            sendWelcomeEmail?.let { put("sendWelcomeEmail", it) }
        }

        val javaClient = javaHttpClientFactory.create(insecureMode)
        val httpRequest = java.net.http.HttpRequest.newBuilder()
            .uri(URI.create("$backendUrl/api/account-onboarding/simulate"))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer $authToken")
            .POST(java.net.http.HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(bodyMap)))
            .timeout(Duration.ofSeconds(120))
            .build()

        val response = javaClient.send(httpRequest, java.net.http.HttpResponse.BodyHandlers.ofString())
        when (response.statusCode()) {
            200 -> Unit
            400 -> throw IllegalArgumentException(errorMessageOf(response.body()) ?: "Invalid request")
            401 -> throw IllegalArgumentException("Authentication required")
            403 -> throw IllegalArgumentException("Insufficient permissions (ADMIN or SECCHAMPION role required)")
            404 -> throw IllegalArgumentException(
                "Backend does not support account onboarding (/api/account-onboarding/simulate not available)"
            )
            429 -> throw IllegalArgumentException(
                errorMessageOf(response.body()) ?: "Too many simulations - try again later, or use --dry-run"
            )
            else -> throw IllegalArgumentException("Unexpected response ${response.statusCode()}")
        }

        @Suppress("UNCHECKED_CAST")
        val body = objectMapper.readValue(response.body(), Map::class.java) as Map<String, Any?>

        @Suppress("UNCHECKED_CAST")
        val onboarding = (body["onboarding"] as? List<Map<String, Any?>>)?.map {
            CliAccountOnboarding(
                awsAccountId = it["awsAccountId"]?.toString() ?: "",
                ownerEmail = it["ownerEmail"]?.toString() ?: "",
                mode = it["mode"]?.toString() ?: "",
                welcomeEmailSent = (it["welcomeEmailSent"] as? Boolean) ?: false,
                questionnaireInviteId = (it["questionnaireInviteId"] as? Number)?.toLong(),
                questionnaireExpiresAt = it["questionnaireExpiresAt"]?.toString(),
                riskAssessmentId = (it["riskAssessmentId"] as? Number)?.toLong(),
                dryRun = (it["dryRun"] as? Boolean) ?: false,
                skipped = (it["skipped"] as? Boolean) ?: false,
                skipReason = it["skipReason"]?.toString(),
                error = it["error"]?.toString()
            )
        } ?: emptyList()

        @Suppress("UNCHECKED_CAST")
        val riskAssessments = (body["riskAssessments"] as? List<Map<String, Any?>>)?.map {
            CliAccountRiskAssessment(
                awsAccountId = it["awsAccountId"]?.toString() ?: "",
                ownerEmail = it["ownerEmail"]?.toString() ?: "",
                riskAssessmentId = (it["riskAssessmentId"] as? Number)?.toLong(),
                assessor = it["assessor"]?.toString(),
                endDate = it["endDate"]?.toString(),
                useCase = it["useCase"]?.toString(),
                releaseVersion = it["releaseVersion"]?.toString(),
                requirementCount = (it["requirementCount"] as? Number)?.toInt(),
                skipped = (it["skipped"] as? Boolean) ?: false,
                skipReason = it["skipReason"]?.toString(),
                error = it["error"]?.toString()
            )
        } ?: emptyList()

        @Suppress("UNCHECKED_CAST")
        val matrixMap = body["ruleMatrix"] as? Map<String, Any?>
        val ruleMatrix = matrixMap?.let { m ->
            @Suppress("UNCHECKED_CAST")
            val rules = (m["rules"] as? List<Map<String, Any?>>)?.map { r ->
                CliOnboardingRule(
                    name = r["name"]?.toString() ?: "",
                    description = r["description"]?.toString(),
                    isDefault = (r["isDefault"] as? Boolean) ?: false,
                    active = (r["active"] as? Boolean) ?: true,
                    combination = (r["combination"] as? List<*>)?.map { c -> c.toString() } ?: emptyList(),
                    useCases = (r["useCases"] as? List<*>)?.map { u -> u.toString() } ?: emptyList()
                )
            } ?: emptyList()
            CliOnboardingRuleMatrix(
                questionCount = (m["questionCount"] as? Number)?.toInt() ?: 0,
                choiceCount = (m["choiceCount"] as? Number)?.toInt() ?: 0,
                activeRuleCount = (m["activeRuleCount"] as? Number)?.toInt() ?: 0,
                hasDefaultRule = (m["hasDefaultRule"] as? Boolean) ?: false,
                rules = rules,
                reachableUseCases = (m["reachableUseCases"] as? List<*>)?.map { u -> u.toString() } ?: emptyList(),
                reachableRequirementCount = (m["reachableRequirementCount"] as? Number)?.toInt() ?: 0,
                releaseVersion = m["releaseVersion"]?.toString()
            )
        }

        return SimulateOnboardingResult(
            awsAccountId = body["awsAccountId"]?.toString() ?: awsAccountId,
            ownerEmail = body["ownerEmail"]?.toString() ?: ownerEmail,
            mode = body["mode"]?.toString() ?: mode,
            dryRun = (body["dryRun"] as? Boolean) ?: dryRun,
            onboarding = onboarding,
            riskAssessments = riskAssessments,
            ruleMatrix = ruleMatrix
        )
    }

    /** Pull the server's message out of an error body, falling back to null rather than guessing. */
    private fun errorMessageOf(body: String?): String? = try {
        @Suppress("UNCHECKED_CAST")
        (objectMapper.readValue(body, Map::class.java) as Map<String, Any?>)["message"]?.toString()
    } catch (e: Exception) {
        null
    }
}

/** One active rule as the CLI prints it. */
data class CliOnboardingRule(
    val name: String,
    val description: String? = null,
    val isDefault: Boolean = false,
    val active: Boolean = true,
    /** `questionKey=choiceKey` per choice. Empty for the default fallback. */
    val combination: List<String> = emptyList(),
    val useCases: List<String> = emptyList()
)

/** The rule set as a GUIDED dry run reports it, in place of minting a token. */
data class CliOnboardingRuleMatrix(
    val questionCount: Int = 0,
    val choiceCount: Int = 0,
    val activeRuleCount: Int = 0,
    val hasDefaultRule: Boolean = false,
    val rules: List<CliOnboardingRule> = emptyList(),
    val reachableUseCases: List<String> = emptyList(),
    val reachableRequirementCount: Int = 0,
    val releaseVersion: String? = null
)

data class SimulateOnboardingResult(
    val awsAccountId: String,
    val ownerEmail: String,
    val mode: String,
    val dryRun: Boolean,
    val onboarding: List<CliAccountOnboarding> = emptyList(),
    val riskAssessments: List<CliAccountRiskAssessment> = emptyList(),
    val ruleMatrix: CliOnboardingRuleMatrix? = null
)
