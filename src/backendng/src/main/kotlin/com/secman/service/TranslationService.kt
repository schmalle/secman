package com.secman.service

import com.secman.domain.TranslationConfig
import com.secman.repository.TranslationConfigRepository
import com.fasterxml.jackson.databind.ObjectMapper
import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import io.micronaut.http.MediaType
import io.micronaut.http.uri.UriBuilder
import io.micronaut.serde.annotation.Serdeable
import jakarta.annotation.PostConstruct
import jakarta.inject.Named
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient as JdkHttpClient
import java.net.http.HttpRequest as JdkHttpRequest
import java.net.http.HttpResponse.BodyHandlers
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicInteger

@Singleton
open class TranslationService(
    private val translationConfigRepository: TranslationConfigRepository,
    private val objectMapper: ObjectMapper,
    @Named("translation") private val translationExecutor: ExecutorService
) {

    private val logger = LoggerFactory.getLogger(TranslationService::class.java)

    private companion object {
        const val MAX_ATTEMPTS = 3
        const val INITIAL_BACKOFF_MS = 1_000L
        const val MAX_BACKOFF_MS = 8_000L
        const val BACKOFF_MULTIPLIER = 2.0
        const val CACHE_MAX_SIZE = 10_000L
        val CACHE_TTL: Duration = Duration.ofHours(24)
        val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(10)
        val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(120)
    }

    // In-process cache keyed by (sourceText, targetLanguage). Holds only successful
    // translations — refusals and fallbacks to the original text are NEVER cached,
    // so a transient model refusal won't pin a bad result for 24h.
    private val translationCache: Cache<Pair<String, String>, String> = Caffeine.newBuilder()
        .maximumSize(CACHE_MAX_SIZE)
        .expireAfterWrite(CACHE_TTL)
        .recordStats()
        .build()

    // JDK HttpClient — explicit, observable connection behavior. HTTP/1.1 forces one TCP
    // connection per concurrent request (no HTTP/2 stream multiplexing limit).
    //
    // CRITICAL: do NOT call .executor(translationExecutor) here. JDK HttpClient uses its
    // configured executor for response-completion tasks (reading headers, decoding body).
    // If all 8 worker threads are blocked inside .send(), and the same pool is supposed to
    // process incoming responses, the responses cannot be delivered → indefinite hang.
    // Letting JDK use its default internal cached pool decouples I/O completion from our
    // worker threads, so .send() unblocks normally.
    private val jdkHttpClient: JdkHttpClient = JdkHttpClient.newBuilder()
        .connectTimeout(CONNECT_TIMEOUT)
        .version(JdkHttpClient.Version.HTTP_1_1)
        .build()

    // Diagnostic counter — bumped at the start of each HTTP call, decremented on completion.
    // Logged with the thread name so you can verify true concurrency in production logs.
    private val inFlight = AtomicInteger(0)

    @PostConstruct
    fun logExecutorInfo() {
        logger.info(
            "TranslationService initialized: executor={}, jdkHttpClient version={}",
            translationExecutor.javaClass.name,
            JdkHttpClient.Version.HTTP_1_1
        )
    }

    @Serdeable
    data class TranslateRequest(
        val model: String,
        val messages: List<Message>,
        val max_tokens: Int? = null,
        val temperature: Double? = null
    )

    @Serdeable
    data class Message(
        val role: String,
        // Nullable: OpenRouter returns content=null for refusals, safety-filtered prompts,
        // and reasoning-only responses (e.g. o1, claude-thinking). Treated as a translation
        // miss downstream — we keep the original text rather than blowing up deserialization.
        val content: String? = null,
        val refusal: String? = null,
        val reasoning: String? = null
    )

    @Serdeable
    data class TranslateResponse(
        val id: String? = null,
        val choices: List<Choice>? = null,
        val error: ErrorDetail? = null,
        val provider: String? = null,
        val model: String? = null,
        val `object`: String? = null,
        val created: Long? = null,
        val usage: Usage? = null
    )

    @Serdeable
    data class Choice(
        val message: Message? = null,
        val finish_reason: String? = null,
        val logprobs: Any? = null,
        val native_finish_reason: String? = null,
        val index: Int? = null
    )

    @Serdeable
    data class ErrorDetail(
        val message: String? = null,
        val type: String? = null,
        val code: String? = null
    )

    @Serdeable
    data class Usage(
        val prompt_tokens: Int? = null,
        val completion_tokens: Int? = null,
        val total_tokens: Int? = null
    )

    @Serdeable
    data class TestResult(
        val success: Boolean,
        val message: String,
        val details: String? = null
    )

    /**
     * Get the active translation configuration
     */
    fun getActiveConfig(): TranslationConfig? {
        return translationConfigRepository.findActiveConfig().orElse(null)
    }

    /**
     * Translate text from English to the specified target language.
     *
     * Synchronous portion (config check + cache lookup) runs on the caller thread.
     * Actual HTTP work is dispatched to the named "translation" executor via
     * supplyAsync, which works regardless of self-invocation (unlike @Async).
     */
    open fun translateText(text: String, targetLanguage: String): CompletableFuture<String> {
        val config = getActiveConfig()
            ?: return CompletableFuture.completedFuture(text)

        if (!config.isValid()) {
            logger.warn("Translation config is not valid: {}", config)
            return CompletableFuture.completedFuture(text)
        }

        val cacheKey = text to targetLanguage
        translationCache.getIfPresent(cacheKey)?.let { cached ->
            logger.debug("Translation cache hit for '{}' [{}]", text.take(40), targetLanguage)
            return CompletableFuture.completedFuture(cached)
        }

        return CompletableFuture.supplyAsync(
            { doTranslateBlocking(text, targetLanguage, config, cacheKey) },
            translationExecutor
        )
    }

    /**
     * Blocking translation worker — runs on a translationExecutor thread.
     * Always returns a non-null String: either the translated text on success,
     * or the original text as a fallback on any failure.
     */
    private fun doTranslateBlocking(
        text: String,
        targetLanguage: String,
        config: TranslationConfig,
        cacheKey: Pair<String, String>
    ): String {
        val prompt = buildTranslationPrompt(text, targetLanguage)
        val request = TranslateRequest(
            model = config.modelName,
            messages = listOf(Message("user", prompt)),
            max_tokens = config.maxTokens,
            temperature = config.temperature
        )

        val fullUrl = "${config.baseUrl}/chat/completions"
        val requestJson = objectMapper.writeValueAsString(request)
        val jdkRequest = JdkHttpRequest.newBuilder()
            .uri(URI.create(fullUrl))
            .timeout(REQUEST_TIMEOUT)
            .header("Content-Type", MediaType.APPLICATION_JSON)
            .header("Authorization", "Bearer ${config.apiKey}")
            .header("HTTP-Referer", "https://secman.local")
            .header("X-Title", "SecMan Translation Service")
            .POST(JdkHttpRequest.BodyPublishers.ofString(requestJson))
            .build()

        return try {
            val translated = executeWithRetry("translateText[${targetLanguage}]") {
                val current = inFlight.incrementAndGet()
                logger.debug(
                    "Making translation request to: {} (in-flight={}, thread={})",
                    fullUrl, current, Thread.currentThread().name
                )
                val response = try {
                    jdkHttpClient.send(jdkRequest, BodyHandlers.ofString())
                } finally {
                    inFlight.decrementAndGet()
                }
                val statusCode = response.statusCode()
                if (statusCode in 500..599 || statusCode == 408 || statusCode == 429) {
                    throw IOException("Translation API HTTP $statusCode")
                }
                if (statusCode !in 200..299) {
                    logger.error("Translation API returned non-success status {}: {}", statusCode, response.body()?.take(200))
                    throw RuntimeException("Translation API HTTP $statusCode")
                }
                val body = response.body()
                if (body.isNullOrBlank()) {
                    throw IOException("Empty response body from translation API")
                }
                val parsed = objectMapper.readValue(body, TranslateResponse::class.java)
                if (parsed.error != null) {
                    logger.error("Translation API error: {}", parsed.error.message)
                    null
                } else {
                    val msg = parsed.choices?.firstOrNull()?.message
                    if (msg != null && msg.content.isNullOrBlank()) {
                        logger.warn(
                            "Translation produced no content (refusal='{}', reasoning='{}', finish_reason='{}') for text: {}",
                            msg.refusal?.take(120),
                            msg.reasoning?.take(120),
                            parsed.choices.firstOrNull()?.finish_reason,
                            text.take(80)
                        )
                    }
                    msg?.content
                }
            }
            if (!translated.isNullOrBlank()) {
                val trimmed = translated.trim()
                translationCache.put(cacheKey, trimmed)
                logger.info("Translation successful: '{}' -> '{}'", text.take(50), trimmed.take(50))
                trimmed
            } else {
                logger.warn("Empty translation response for text: {}", text.take(50))
                text
            }
        } catch (e: Exception) {
            logger.error("Translation failed for text after retries: {}", text.take(50), e)
            text
        }
    }

    /**
     * Run [block] with bounded exponential backoff retries on transient HTTP failures
     * (read timeouts, connection issues, 408, 429, 5xx). All other exceptions and
     * non-retryable parse-level outcomes propagate unchanged so callers can fall back.
     */
    private fun <T> executeWithRetry(operation: String, block: () -> T): T {
        var attempt = 1
        var delay = INITIAL_BACKOFF_MS
        while (true) {
            try {
                return block()
            } catch (e: Exception) {
                if (attempt >= MAX_ATTEMPTS || !isRetryable(e)) {
                    throw e
                }
                val jitter = (Math.random() * delay / 4).toLong()
                val sleep = (delay + jitter).coerceAtMost(MAX_BACKOFF_MS)
                logger.warn(
                    "{} failed (attempt {}/{}): {} — retrying in {} ms",
                    operation, attempt, MAX_ATTEMPTS, e.javaClass.simpleName, sleep
                )
                Thread.sleep(sleep)
                attempt++
                delay = (delay * BACKOFF_MULTIPLIER).toLong().coerceAtMost(MAX_BACKOFF_MS)
            }
        }
    }

    private fun isRetryable(e: Throwable): Boolean = when (e) {
        is IOException -> true            // covers JDK send() IOExceptions, our 5xx/408/429 wraps
        is InterruptedException -> false  // surface interruption immediately
        else -> false
    }

    /**
     * Translate multiple texts concurrently.
     *
     * Each translateText call submits its blocking work to translationExecutor via
     * supplyAsync, so this method fans out N futures that run in parallel up to the
     * executor's pool size (n-threads=8 in application.yml). No @Async here — this
     * method just orchestrates futures and returns immediately.
     */
    open fun translateTexts(texts: List<String>, targetLanguage: String): CompletableFuture<List<String>> {
        if (texts.isEmpty()) {
            return CompletableFuture.completedFuture(emptyList())
        }
        val futures = texts.map { translateText(it, targetLanguage) }
        return CompletableFuture.allOf(*futures.toTypedArray())
            .thenApply { _ -> futures.map { it.get() } }
    }

    /**
     * Test the translation configuration
     */
    fun testConfiguration(config: TranslationConfig): TestResult {
        if (!config.isValid()) {
            return TestResult(
                success = false,
                message = "Configuration validation failed",
                details = "Invalid API key, base URL, or model settings"
            )
        }

        return try {
            val testText = "Hello, this is a test."
            val prompt = buildTranslationPrompt(testText, "de")

            val request = TranslateRequest(
                model = config.modelName,
                messages = listOf(Message("user", prompt)),
                max_tokens = 100,
                temperature = 0.1
            )

            val fullUrl = UriBuilder.of(config.baseUrl).path("/chat/completions").build().toString()
            val jdkRequest = JdkHttpRequest.newBuilder()
                .uri(URI.create(fullUrl))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer ${config.apiKey}")
                .header("HTTP-Referer", "https://secman.local")
                .header("X-Title", "SecMan Translation Service Test")
                .POST(JdkHttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(request)))
                .build()

            val response = jdkHttpClient.send(jdkRequest, BodyHandlers.ofString())
            when (response.statusCode()) {
                200 -> {
                    val body = response.body()?.let { objectMapper.readValue(it, TranslateResponse::class.java) }
                    when {
                        body?.error != null -> TestResult(
                            success = false,
                            message = "API returned error: ${body.error.message}",
                            details = "Error type: ${body.error.type}, Code: ${body.error.code}"
                        )
                        body?.choices?.isNotEmpty() == true -> {
                            val translatedText = body.choices.firstOrNull()?.message?.content
                            TestResult(
                                success = true,
                                message = "Translation test successful",
                                details = "Test translation: '$testText' -> '${translatedText?.take(50)}'"
                            )
                        }
                        else -> TestResult(
                            success = false,
                            message = "Empty or invalid API response",
                            details = "Response body: ${response.body()?.take(200)}"
                        )
                    }
                }
                401 -> TestResult(success = false, message = "Authentication failed", details = "Invalid API key or insufficient permissions")
                429 -> TestResult(success = false, message = "Rate limit exceeded", details = "Too many requests to the translation API")
                else -> TestResult(success = false, message = "HTTP error ${response.statusCode()}", details = "Body: ${response.body()?.take(200)}")
            }
        } catch (e: Exception) {
            logger.error("Translation configuration test failed", e)
            TestResult(
                success = false,
                message = "Connection or request failed",
                details = e.message ?: "Unknown error"
            )
        }
    }

    /**
     * Build translation prompt for the AI model
     */
    private fun buildTranslationPrompt(text: String, targetLanguage: String): String {
        val languageName = TranslationConfig.SUPPORTED_LANGUAGES[targetLanguage] ?: targetLanguage
        
        return """
            Translate the following text from English to $languageName.
            
            Requirements:
            - Maintain the original meaning and context
            - Preserve any technical terminology appropriately
            - Keep formatting and structure intact
            - Provide only the translation without additional commentary
            
            Text to translate:
            $text
        """.trimIndent()
    }

    /**
     * Get supported languages
     */
    fun getSupportedLanguages(): Map<String, String> {
        return TranslationConfig.SUPPORTED_LANGUAGES
    }

    /**
     * Get available models
     */
    fun getAvailableModels(): Map<String, String> {
        return TranslationConfig.AVAILABLE_MODELS
    }
}