package com.secman.service

import com.secman.config.AiRiskAssessmentConfig
import com.secman.config.OpenRouterConfig
import com.secman.domain.ConfidenceBand
import com.secman.domain.Requirement
import com.secman.domain.SuggestedAnswerType
import com.secman.dto.AssessmentContext
import com.secman.dto.Citation
import com.secman.dto.SuggestionResult
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import io.micronaut.http.MediaType
import io.micronaut.serde.annotation.Serdeable
import jakarta.annotation.PostConstruct
import jakarta.inject.Named
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory
import java.io.IOException
import java.math.BigDecimal
import java.math.RoundingMode
import java.net.URI
import java.net.http.HttpClient as JdkHttpClient
import java.net.http.HttpRequest as JdkHttpRequest
import java.net.http.HttpResponse.BodyHandlers
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService

/**
 * Feature 088 — AI-Assisted Risk Assessment Answers.
 *
 * Outbound bridge to OpenRouter for one requirement at a time. Builds the
 * prompt (via PromptBuilder), POSTs to /chat/completions with JSON-strict
 * output, parses the result, validates citations, scores confidence, returns
 * a SuggestionResult.
 *
 * Mirrors TranslationService idioms: explicit JDK HttpClient, dedicated
 * executor pool ("ai"), Caffeine cache. See TranslationService.kt:54-66 for
 * the executor-deadlock note that informs the HttpClient construction.
 */
@Singleton
open class ComplianceAssistantService(
    private val config: AiRiskAssessmentConfig,
    private val openRouterConfig: OpenRouterConfig,
    private val objectMapper: ObjectMapper,
    private val promptBuilder: PromptBuilder,
    private val confidenceScorer: ConfidenceScorer,
    private val citationValidator: CitationValidator,
    @Named("ai") private val aiExecutor: ExecutorService
) {
    private val log = LoggerFactory.getLogger(ComplianceAssistantService::class.java)

    private companion object {
        const val MAX_ATTEMPTS = 3
        const val INITIAL_BACKOFF_MS = 1_000L
        const val MAX_BACKOFF_MS = 8_000L
        const val BACKOFF_MULTIPLIER = 2.0
        const val CACHE_MAX_SIZE = 5_000L
        val CACHE_TTL: Duration = Duration.ofHours(24)
        val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(10)
        const val PROMPT_RESOURCE = "ai-prompts/compliance-assistant.txt"
    }

    private val httpClient: JdkHttpClient = JdkHttpClient.newBuilder()
        .connectTimeout(CONNECT_TIMEOUT)
        .version(JdkHttpClient.Version.HTTP_1_1)
        .build()

    // Keyed by hash(systemPrompt + userPrompt + model). Only successful
    // suggestions are cached — failures and refusals never get pinned for 24h.
    private val cache: Cache<String, SuggestionResult> = Caffeine.newBuilder()
        .maximumSize(CACHE_MAX_SIZE)
        .expireAfterWrite(CACHE_TTL)
        .recordStats()
        .build()

    /** Loaded once at startup. Version is the first line ("VERSION: 001"). */
    @Volatile private var systemPrompt: String = ""
    @Volatile private var promptVersion: String = "000"

    @PostConstruct
    fun init() {
        loadSystemPrompt()
        log.info(
            "ComplianceAssistantService initialized: enabled={}, model={}, promptVersion={}, maxCost={}, maxConcurrent={}",
            config.enabled, config.model, promptVersion, config.maxCostPerJobUsd, config.maxConcurrentJobsGlobal
        )
    }

    private fun loadSystemPrompt() {
        val stream = javaClass.classLoader.getResourceAsStream(PROMPT_RESOURCE)
        if (stream == null) {
            log.error("AI prompt resource '{}' not found on classpath", PROMPT_RESOURCE)
            systemPrompt = ""
            promptVersion = "000"
            return
        }
        val raw = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        systemPrompt = raw
        // First line conventionally "VERSION: NNN" — captured for audit trail.
        val firstLine = raw.lineSequence().firstOrNull()?.trim().orEmpty()
        promptVersion = firstLine.removePrefix("VERSION:").trim().ifBlank { "000" }
    }

    /**
     * True when the feature flag is on AND a key is configured. Controllers
     * gate POSTs on this; tests with isEnabled() mocked can exercise the
     * service end-to-end without a live API key.
     */
    fun isEnabled(): Boolean = config.enabled && openRouterConfig.apiKey.isNotBlank()

    /**
     * Cheap pre-flight cost projection from the configured per-1k pricing.
     * Used by AiSuggestionJobService to reject obviously over-budget runs
     * before any HTTP call.
     */
    fun estimateCostUsd(requirementCount: Int): BigDecimal {
        val pricing = config.pricingPer1kTokens[config.model]
        val inPer1k = pricing?.input ?: 0.005   // conservative default
        val outPer1k = pricing?.output ?: 0.020 // conservative default
        val perReq = (config.tokenEstimate.inputPerRequirement * inPer1k +
                      config.tokenEstimate.outputPerRequirement * outPer1k) / 1000.0
        return BigDecimal.valueOf(perReq * requirementCount).setScale(6, RoundingMode.HALF_UP)
    }

    val currentPromptVersion: String get() = promptVersion
    val currentModel: String get() = config.model

    /**
     * Produce a suggestion for the given requirement, asynchronously on the
     * "ai" executor.  Returns a failed future with [IllegalStateException] if
     * the feature is disabled.
     */
    open fun suggest(
        requirement: Requirement,
        context: AssessmentContext
    ): CompletableFuture<SuggestionResult> {
        if (!isEnabled()) {
            return CompletableFuture.failedFuture(
                IllegalStateException("AI risk-assessment feature is disabled")
            )
        }

        val userPrompt = promptBuilder.build(requirement, context)
        val cacheKey = hashKey(systemPrompt, userPrompt, config.model)
        cache.getIfPresent(cacheKey)?.let {
            log.debug("Cache hit for requirement={} key={}", requirement.id, cacheKey.take(12))
            return CompletableFuture.completedFuture(it.copy(cacheHit = true))
        }

        return CompletableFuture.supplyAsync({
            try {
                val result = doSuggestBlocking(userPrompt)
                cache.put(cacheKey, result)
                result
            } catch (e: Exception) {
                log.warn("AI suggest failed for requirement={}: {}", requirement.id, e.message)
                throw e
            }
        }, aiExecutor)
    }

    // --- Internals ----------------------------------------------------------

    @Serdeable
    private data class Msg(val role: String, val content: String? = null, val annotations: List<JsonNode>? = null)
    @Serdeable
    private data class ChatRequest(
        val model: String,
        val messages: List<Map<String, Any>>,
        val temperature: Double = 0.1,
        val max_tokens: Int = 800
    )

    private fun doSuggestBlocking(userPrompt: String): SuggestionResult {
        val body = ChatRequest(
            model = config.model,
            messages = listOf(
                mapOf("role" to "system", "content" to systemPrompt),
                mapOf("role" to "user", "content" to userPrompt)
            )
        )
        val payload = objectMapper.writeValueAsString(body)
        val req = JdkHttpRequest.newBuilder()
            .uri(URI.create("${openRouterConfig.baseUrl.trimEnd('/')}/chat/completions"))
            .timeout(Duration.ofSeconds(config.perRequestTimeoutSeconds))
            .header("Content-Type", MediaType.APPLICATION_JSON)
            .header("Authorization", "Bearer ${openRouterConfig.apiKey}")
            .header("HTTP-Referer", "https://secman.local")
            .header("X-Title", "SecMan AI Risk Assessment")
            .POST(JdkHttpRequest.BodyPublishers.ofString(payload))
            .build()

        val raw = executeWithRetry("openrouter-chat") {
            val resp = httpClient.send(req, BodyHandlers.ofString())
            val sc = resp.statusCode()
            if (sc in 500..599 || sc == 408 || sc == 429) throw IOException("OpenRouter HTTP $sc")
            if (sc !in 200..299) throw RuntimeException("OpenRouter HTTP $sc — ${resp.body()?.take(200)}")
            resp.body() ?: throw IOException("Empty OpenRouter response body")
        }
        return parseAndScore(raw)
    }

    /**
     * Visible for testing — parse + score without doing HTTP.
     */
    internal fun parseAndScore(rawJson: String): SuggestionResult {
        val root = objectMapper.readTree(rawJson)

        // OpenRouter returns the usual OpenAI shape under choices[0].
        val choice = root.path("choices").firstOrNull()
            ?: throw RuntimeException("OpenRouter response has no choices")
        val message = choice.path("message")
        val content = message.path("content").asText("").trim()
        if (content.isEmpty()) throw RuntimeException("OpenRouter returned empty content (refusal=${message.path("refusal").asText("")})")

        val inner = parseInnerJson(content)
        val answer = SuggestedAnswerType.valueOf(inner.path("answer").asText("UNKNOWN").uppercase())
        val rationale = inner.path("rationale").asText("").trim().ifBlank { null }
        val modelSelf = inner.path("confidence").asDouble(0.0).coerceIn(0.0, 1.0)

        // Citations live in two places: (a) the JSON the model wrote inside
        // `content` under `citations`, and (b) OpenRouter's own
        // `message.annotations[].url_citation` injected for :online models.
        // We union both sources, then validate.
        val merged = mutableListOf<Citation>()
        inner.path("citations").takeIf { it.isArray }?.forEach { c ->
            val url = c.path("url").asText("")
            if (url.isNotBlank()) merged += Citation(
                title = c.path("title").asText("").ifBlank { null },
                url = url,
                snippet = c.path("snippet").asText("").ifBlank { null }
            )
        }
        var webSearchUsed = false
        message.path("annotations").takeIf { it.isArray }?.forEach { a ->
            val u = a.path("url_citation")
            val url = u.path("url").asText("")
            if (url.isNotBlank()) {
                webSearchUsed = true
                merged += Citation(
                    title = u.path("title").asText("").ifBlank { null },
                    url = url,
                    snippet = u.path("content").asText("").ifBlank { null }
                )
            }
        }
        val cleaned = citationValidator.validate(merged)
        val (raw, band) = confidenceScorer.score(modelSelf, cleaned.size, answer)

        val usage = root.path("usage")
        val inputTokens = if (usage.has("prompt_tokens")) usage.get("prompt_tokens").asInt() else null
        val outputTokens = if (usage.has("completion_tokens")) usage.get("completion_tokens").asInt() else null
        val cost = computeCost(inputTokens, outputTokens)

        return SuggestionResult(
            answer = answer,
            rationale = rationale,
            rawConfidence = raw,
            confidenceBand = band,
            citations = cleaned,
            model = root.path("model").asText(config.model).ifBlank { config.model },
            promptVersion = promptVersion,
            inputTokens = inputTokens,
            outputTokens = outputTokens,
            costUsd = cost,
            webSearchUsed = webSearchUsed
        )
    }

    /**
     * The model's `content` should be strict JSON. Some models wrap it in a
     * markdown fence anyway — strip a leading/trailing ```json block defensively.
     */
    private fun parseInnerJson(content: String): JsonNode {
        var s = content.trim()
        if (s.startsWith("```")) {
            s = s.removePrefix("```json").removePrefix("```").trim()
            val close = s.lastIndexOf("```")
            if (close > 0) s = s.substring(0, close).trim()
        }
        return objectMapper.readTree(s)
    }

    private fun computeCost(inputTokens: Int?, outputTokens: Int?): BigDecimal? {
        val pricing = config.pricingPer1kTokens[config.model] ?: return null
        val inCost = (inputTokens ?: 0) * pricing.input / 1000.0
        val outCost = (outputTokens ?: 0) * pricing.output / 1000.0
        return BigDecimal.valueOf(inCost + outCost).setScale(6, RoundingMode.HALF_UP)
    }

    private fun <T> executeWithRetry(op: String, block: () -> T): T {
        var attempt = 1
        var delay = INITIAL_BACKOFF_MS
        while (true) {
            try {
                return block()
            } catch (e: IOException) {
                if (attempt >= MAX_ATTEMPTS) throw e
                val sleep = delay.coerceAtMost(MAX_BACKOFF_MS)
                log.warn("{} failed (attempt {}/{}): {} — retrying in {}ms", op, attempt, MAX_ATTEMPTS, e.message, sleep)
                Thread.sleep(sleep)
                attempt++
                delay = (delay * BACKOFF_MULTIPLIER).toLong().coerceAtMost(MAX_BACKOFF_MS)
            }
        }
    }

    private fun hashKey(systemPrompt: String, userPrompt: String, model: String): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        md.update(systemPrompt.toByteArray(Charsets.UTF_8))
        md.update(0)
        md.update(userPrompt.toByteArray(Charsets.UTF_8))
        md.update(0)
        md.update(model.toByteArray(Charsets.UTF_8))
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
