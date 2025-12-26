package com.secman.service

import com.secman.domain.TranslationConfig
import com.secman.repository.TranslationConfigRepository
import com.fasterxml.jackson.databind.ObjectMapper
import io.micronaut.context.annotation.Bean
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpResponse
import io.micronaut.http.MediaType
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.http.uri.UriBuilder
import io.micronaut.jackson.annotation.JacksonFeatures
import io.micronaut.scheduling.annotation.Async
import io.micronaut.serde.annotation.Serdeable
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory
import java.util.concurrent.CompletableFuture

@Singleton
open class TranslationService(
    private val translationConfigRepository: TranslationConfigRepository,
    private val httpClient: HttpClient,
    private val objectMapper: ObjectMapper
) {
    
    private val logger = LoggerFactory.getLogger(TranslationService::class.java)

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
        val content: String,
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
     * Translate text from English to the specified target language
     */
    @Async
    open fun translateText(text: String, targetLanguage: String): CompletableFuture<String> {
        val config = getActiveConfig()
            ?: return CompletableFuture.completedFuture(text) // Return original if no config

        if (!config.isValid()) {
            logger.warn("Translation config is not valid: {}", config)
            return CompletableFuture.completedFuture(text)
        }

        return try {
            val prompt = buildTranslationPrompt(text, targetLanguage)
            val request = TranslateRequest(
                model = config.modelName,
                messages = listOf(
                    Message("user", prompt)
                ),
                max_tokens = config.maxTokens,
                temperature = config.temperature
            )

            val fullUrl = "${config.baseUrl}/chat/completions"
            val httpRequest = HttpRequest.POST(fullUrl, request)
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer ${config.apiKey}")
                .header("HTTP-Referer", "https://secman.local")
                .header("X-Title", "SecMan Translation Service")

            logger.debug("Making translation request to: {}", fullUrl)
            val response = httpClient.toBlocking().exchange(httpRequest, String::class.java)
            
            if (response.status.code == 200) {
                val responseBodyString = response.body()
                logger.debug("Raw API response: {}", responseBodyString?.take(200))
                
                if (responseBodyString.isNullOrBlank()) {
                    logger.error("Empty response body from translation API")
                    return CompletableFuture.completedFuture(text)
                }
                
                return try {
                    // Parse JSON using injected ObjectMapper (configured with Kotlin module)
                    val responseBody = objectMapper.readValue(responseBodyString, TranslateResponse::class.java)
                    
                    if (responseBody.error != null) {
                        logger.error("Translation API error: {}", responseBody.error.message)
                        return CompletableFuture.completedFuture(text)
                    }
                    
                    val translatedText = responseBody.choices?.firstOrNull()?.message?.content
                    if (!translatedText.isNullOrBlank()) {
                        logger.info("Translation successful: '{}' -> '{}'", text.take(50), translatedText.take(50))
                        CompletableFuture.completedFuture(translatedText.trim())
                    } else {
                        logger.warn("Empty translation response for text: {}", text.take(50))
                        CompletableFuture.completedFuture(text)
                    }
                } catch (e: Exception) {
                    logger.error("Failed to parse translation response: {}", responseBodyString?.take(200), e)
                    CompletableFuture.completedFuture(text)
                }
            } else {
                logger.error("Translation API request failed with status: {} {}", response.status.code, response.status.reason)
                return CompletableFuture.completedFuture(text)
            }
        } catch (e: Exception) {
            logger.error("Translation failed for text: {}", text.take(50), e)
            return CompletableFuture.completedFuture(text)
        }
    }

    /**
     * Translate multiple texts concurrently
     */
    @Async
    open fun translateTexts(texts: List<String>, targetLanguage: String): CompletableFuture<List<String>> {
        if (texts.isEmpty()) {
            return CompletableFuture.completedFuture(emptyList())
        }

        val futures = texts.map { text ->
            translateText(text, targetLanguage)
        }

        return CompletableFuture.allOf(*futures.toTypedArray())
            .thenApply { _ ->
                futures.map { it.get() }
            }
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

            val httpRequest = HttpRequest.POST(UriBuilder.of(config.baseUrl).path("/chat/completions").build(), request)
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer ${config.apiKey}")
                .header("HTTP-Referer", "https://secman.local")
                .header("X-Title", "SecMan Translation Service Test")

            val response = httpClient.toBlocking().exchange(httpRequest, TranslateResponse::class.java)
            
            when {
                response.status.code == 200 -> {
                    val body = response.body()
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
                            details = "Response body: $body"
                        )
                    }
                }
                response.status.code == 401 -> TestResult(
                    success = false,
                    message = "Authentication failed",
                    details = "Invalid API key or insufficient permissions"
                )
                response.status.code == 429 -> TestResult(
                    success = false,
                    message = "Rate limit exceeded",
                    details = "Too many requests to the translation API"
                )
                else -> TestResult(
                    success = false,
                    message = "HTTP error ${response.status.code}",
                    details = "Status: ${response.status}, Reason: ${response.status.reason}"
                )
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