package com.secman.service

import com.secman.domain.Norm
import com.secman.domain.Requirement
import com.secman.dto.*
import com.secman.repository.NormRepository
import com.secman.repository.RequirementRepository
import com.secman.repository.TranslationConfigRepository
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.micronaut.http.HttpRequest
import io.micronaut.http.MediaType
import io.micronaut.http.client.HttpClient
import io.micronaut.serde.annotation.Serdeable
import io.micronaut.transaction.annotation.Transactional
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory

@Singleton
open class NormMappingService(
    private val requirementRepository: RequirementRepository,
    private val normRepository: NormRepository,
    private val translationConfigRepository: TranslationConfigRepository,
    private val httpClient: HttpClient,
    private val objectMapper: ObjectMapper
) {

    private val logger = LoggerFactory.getLogger(NormMappingService::class.java)

    companion object {
        // Default to Claude 3 Opus if not configured - uses OpenRouter model naming
        const val DEFAULT_MODEL = "anthropic/claude-3-opus"
        const val MAX_REQUIREMENT_TEXT_LENGTH = 500
        // Process requirements in batches to avoid timeout
        const val BATCH_SIZE = 10
    }

    // OpenRouter API DTOs
    @Serdeable
    data class OpenRouterRequest(
        val model: String,
        val messages: List<OpenRouterMessage>,
        val max_tokens: Int = 4096,
        val temperature: Double = 0.1
    )

    @Serdeable
    data class OpenRouterMessage(
        val role: String,
        val content: String
    )

    @Serdeable
    data class OpenRouterResponse(
        val id: String? = null,
        val choices: List<OpenRouterChoice>? = null,
        val error: OpenRouterError? = null
    )

    @Serdeable
    data class OpenRouterChoice(
        val message: OpenRouterMessage? = null,
        val finish_reason: String? = null
    )

    @Serdeable
    data class OpenRouterError(
        val message: String? = null,
        val type: String? = null,
        val code: String? = null
    )

    // AI Response parsing DTOs
    @Serdeable
    data class AIResponseWrapper(
        val mappings: List<AIRequirementMapping>
    )

    @Serdeable
    data class AIRequirementMapping(
        val requirementIndex: Int,
        val suggestions: List<AISuggestion>
    )

    @Serdeable
    data class AISuggestion(
        val standard: String,
        val control: String,
        val controlName: String,
        val confidence: Int,
        val reasoning: String
    )

    /**
     * Get all requirements that have no norm mappings
     */
    fun getUnmappedRequirements(): List<Requirement> {
        logger.debug("Fetching all unmapped requirements")
        return requirementRepository.findAll()
            .filter { it.norms.isEmpty() }
            .also { logger.info("Found {} unmapped requirements", it.size) }
    }

    /**
     * Get count of unmapped requirements
     */
    fun getUnmappedCount(): Int {
        return getUnmappedRequirements().size
    }

    /**
     * Build AI prompt for batch requirement analysis
     */
    fun buildAIPrompt(requirements: List<Requirement>): String {
        val requirementsList = requirements.mapIndexed { index, req ->
            val truncatedText = if (req.shortreq.length > MAX_REQUIREMENT_TEXT_LENGTH) {
                req.shortreq.take(MAX_REQUIREMENT_TEXT_LENGTH) + "..."
            } else {
                req.shortreq
            }
            "${index + 1}. $truncatedText"
        }.joinToString("\n")

        return """
You are a security standards expert. For each security requirement below, suggest the most relevant control mappings from ISO 27001, IEC 62443, NIST SP 800-53, and NIST CSF 2.0.

Requirements to analyze:
$requirementsList

Respond in JSON format only (no markdown, no code blocks):
{
  "mappings": [
    {
      "requirementIndex": 1,
      "suggestions": [
        {
          "standard": "ISO 27001:2022",
          "control": "A.8.1.1",
          "controlName": "Inventory of assets",
          "confidence": 4,
          "reasoning": "Brief explanation"
        }
      ]
    }
  ]
}

Rules:
- Only suggest mappings with confidence >= 3
- Include up to 3 suggestions per requirement
- Confidence scale: 1 (weak) to 5 (strong match)
- Focus on: ISO 27001:2022, IEC 62443-3-3, IEC 62443-4-2, NIST SP 800-53 Rev. 5, NIST CSF 2.0
- If no relevant mapping exists, return empty suggestions array for that requirement
""".trim()
    }

    /**
     * Call OpenRouter API with the given prompt
     */
    fun callOpenRouter(prompt: String): String? {
        val config = translationConfigRepository.findActiveConfig().orElse(null)
        if (config == null) {
            logger.error("No active translation config found for AI mapping")
            throw IllegalStateException("AI configuration required: No active TranslationConfig with OpenRouter API key")
        }

        if (!config.isValid()) {
            logger.error("Translation config is invalid")
            throw IllegalStateException("AI configuration invalid: Check API key and base URL")
        }

        // Use model from config, or fall back to default
        val modelToUse = if (config.modelName.isNotBlank()) config.modelName else DEFAULT_MODEL

        val request = OpenRouterRequest(
            model = modelToUse,
            messages = listOf(
                OpenRouterMessage("user", prompt)
            ),
            max_tokens = config.maxTokens,
            temperature = config.temperature
        )

        // Construct full URL from database config (same pattern as TranslationService)
        val fullUrl = "${config.baseUrl}/chat/completions"
        val httpRequest = HttpRequest.POST(fullUrl, request)
            .contentType(MediaType.APPLICATION_JSON)
            .header("Authorization", "Bearer ${config.apiKey}")
            .header("HTTP-Referer", "https://secman.local")
            .header("X-Title", "SecMan Norm Mapping Service")

        logger.info("Calling OpenRouter API at {} with model: {}", fullUrl, modelToUse)

        return try {
            val response = httpClient.toBlocking().exchange(httpRequest, String::class.java)

            if (response.status.code == 200) {
                val responseBody = response.body()
                logger.debug("OpenRouter response received: {} chars", responseBody?.length)

                // Check for HTML response (error page from Cloudflare/proxy)
                if (responseBody != null && responseBody.trimStart().startsWith("<")) {
                    val preview = responseBody.take(500)
                    logger.error("OpenRouter returned HTML instead of JSON. Response preview: {}", preview)

                    // Try to extract error message from HTML
                    val errorMessage = when {
                        responseBody.contains("403") || responseBody.contains("Forbidden") ->
                            "Access forbidden - check API key or rate limits"
                        responseBody.contains("502") || responseBody.contains("Bad Gateway") ->
                            "OpenRouter service temporarily unavailable (502)"
                        responseBody.contains("503") || responseBody.contains("Service Unavailable") ->
                            "OpenRouter service temporarily unavailable (503)"
                        responseBody.contains("cloudflare", ignoreCase = true) ->
                            "Request blocked by Cloudflare protection - possible rate limiting"
                        responseBody.contains("401") || responseBody.contains("Unauthorized") ->
                            "Invalid API key - check your OpenRouter configuration"
                        else -> "Unexpected HTML response from API - check logs for details"
                    }
                    throw RuntimeException("AI service error: $errorMessage")
                }

                // Parse response to extract content
                val parsed = objectMapper.readValue<OpenRouterResponse>(responseBody ?: "")

                if (parsed.error != null) {
                    logger.error("OpenRouter API error: {}", parsed.error.message)
                    throw RuntimeException("AI service error: ${parsed.error.message}")
                }

                parsed.choices?.firstOrNull()?.message?.content
            } else {
                logger.error("OpenRouter API failed with status: {}", response.status.code)
                throw RuntimeException("AI service unavailable (HTTP ${response.status.code})")
            }
        } catch (e: io.micronaut.http.client.exceptions.HttpClientResponseException) {
            // Extract detailed error message from the response body
            val errorBody = try {
                e.response.getBody(String::class.java).orElse(null)
            } catch (ex: Exception) {
                null
            }
            logger.error("OpenRouter API call failed - Status: {}, Body: {}", e.status, errorBody, e)

            // Try to parse error details from response
            val errorMessage = if (errorBody != null) {
                try {
                    val errorResponse = objectMapper.readValue<OpenRouterResponse>(errorBody)
                    errorResponse.error?.message ?: "HTTP ${e.status.code}: ${e.message}"
                } catch (ex: Exception) {
                    "HTTP ${e.status.code}: $errorBody"
                }
            } else {
                "HTTP ${e.status.code}: ${e.message}"
            }

            throw RuntimeException("AI service error: $errorMessage", e)
        } catch (e: Exception) {
            logger.error("OpenRouter API call failed", e)
            throw RuntimeException("AI service error: ${e.message}", e)
        }
    }

    /**
     * Parse AI response JSON into NormSuggestion DTOs
     */
    fun parseAIResponse(responseJson: String, requirements: List<Requirement>): List<RequirementSuggestions> {
        logger.debug("Parsing AI response: {} chars", responseJson.length)

        // Clean up response - remove markdown code blocks if present
        val cleanJson = responseJson
            .replace(Regex("```json\\s*"), "")
            .replace(Regex("```\\s*"), "")
            .trim()

        return try {
            val aiResponse = objectMapper.readValue<AIResponseWrapper>(cleanJson)

            aiResponse.mappings.mapNotNull { mapping ->
                val reqIndex = mapping.requirementIndex - 1 // Convert 1-based to 0-based
                if (reqIndex < 0 || reqIndex >= requirements.size) {
                    logger.warn("Invalid requirement index in AI response: {}", mapping.requirementIndex)
                    return@mapNotNull null
                }

                val requirement = requirements[reqIndex]

                RequirementSuggestions(
                    requirementId = requirement.id!!,
                    requirementTitle = requirement.shortreq.take(100),
                    suggestions = mapping.suggestions.map { suggestion ->
                        // Check if norm already exists in DB
                        val normName = "${suggestion.standard}: ${suggestion.control}"
                        val existingNorm = normRepository.findByName(normName).orElse(null)

                        NormSuggestion(
                            standard = suggestion.standard,
                            control = suggestion.control,
                            controlName = suggestion.controlName,
                            confidence = suggestion.confidence.coerceIn(1, 5),
                            reasoning = suggestion.reasoning,
                            normId = existingNorm?.id
                        )
                    }
                )
            }
        } catch (e: Exception) {
            logger.error("Failed to parse AI response: {}", e.message)
            logger.debug("Raw response that failed to parse: {}", cleanJson.take(500))
            throw RuntimeException("Failed to parse AI suggestions: ${e.message}")
        }
    }

    /**
     * Main orchestration method: Get AI suggestions for unmapped requirements
     * Processes requirements in batches to avoid API timeout
     */
    fun suggestMappings(request: NormMappingSuggestionRequest? = null): NormMappingSuggestionResponse {
        logger.info("Starting norm mapping suggestion process")

        // Get requirements to analyze
        val requirements = if (request?.requirementIds != null && request.requirementIds.isNotEmpty()) {
            request.requirementIds.mapNotNull { id ->
                requirementRepository.findById(id).orElse(null)
            }.filter { it.norms.isEmpty() }
        } else {
            getUnmappedRequirements()
        }

        if (requirements.isEmpty()) {
            logger.info("No unmapped requirements found")
            return NormMappingSuggestionResponse(
                suggestions = emptyList(),
                totalRequirementsAnalyzed = 0,
                totalSuggestionsGenerated = 0
            )
        }

        logger.info("Analyzing {} unmapped requirements in batches of {}", requirements.size, BATCH_SIZE)

        // Process requirements in batches to avoid timeout
        val allSuggestions = mutableListOf<RequirementSuggestions>()
        val batches = requirements.chunked(BATCH_SIZE)

        for ((batchIndex, batch) in batches.withIndex()) {
            logger.info("Processing batch {}/{} ({} requirements)", batchIndex + 1, batches.size, batch.size)

            try {
                val prompt = buildAIPrompt(batch)
                val aiResponse = callOpenRouter(prompt)
                    ?: throw RuntimeException("No response from AI service for batch ${batchIndex + 1}")

                val batchSuggestions = parseAIResponse(aiResponse, batch)
                allSuggestions.addAll(batchSuggestions)

                logger.info("Batch {}/{} completed: {} suggestions generated",
                    batchIndex + 1, batches.size, batchSuggestions.sumOf { it.suggestions.size })
            } catch (e: Exception) {
                logger.error("Batch {}/{} failed: {}", batchIndex + 1, batches.size, e.message)
                // Continue with next batch instead of failing entirely
                // This allows partial results to be returned
            }
        }

        val totalSuggestions = allSuggestions.sumOf { it.suggestions.size }
        logger.info("Generated {} suggestions for {} requirements across {} batches",
            totalSuggestions, allSuggestions.size, batches.size)

        return NormMappingSuggestionResponse(
            suggestions = allSuggestions,
            totalRequirementsAnalyzed = requirements.size,
            totalSuggestionsGenerated = totalSuggestions
        )
    }

    /**
     * Find existing norm by name or create a new one
     */
    @Transactional
    open fun findOrCreateNorm(standard: String, control: String, version: String?): Norm {
        val normName = "$standard: $control"

        // Check if norm exists
        val existing = normRepository.findByName(normName)
        if (existing.isPresent) {
            logger.debug("Found existing norm: {}", normName)
            return existing.get()
        }

        // Extract year from standard if present
        val yearMatch = Regex("(\\d{4})").find(standard)
        val year = yearMatch?.value?.toIntOrNull()

        // Create new norm
        val newNorm = Norm(
            name = normName,
            version = version ?: yearMatch?.value ?: "",
            year = year
        )

        logger.info("Creating new norm: {}", normName)
        return normRepository.save(newNorm)
    }

    /**
     * Apply selected mappings to requirements
     */
    @Transactional
    open fun applyMappings(request: ApplyMappingsRequest): ApplyMappingsResponse {
        logger.info("Applying mappings for {} requirements", request.mappings.size)

        var updatedRequirements = 0
        var newNormsCreated = 0
        var existingNormsLinked = 0

        for ((requirementId, normsToApply) in request.mappings) {
            val requirement = requirementRepository.findById(requirementId).orElse(null)
            if (requirement == null) {
                logger.warn("Requirement not found: {}", requirementId)
                continue
            }

            var requirementUpdated = false

            for (normToApply in normsToApply) {
                val norm = if (normToApply.normId != null) {
                    // Use existing norm
                    val existing = normRepository.findById(normToApply.normId).orElse(null)
                    if (existing != null) {
                        existingNormsLinked++
                        existing
                    } else {
                        logger.warn("Norm not found by ID: {}", normToApply.normId)
                        continue
                    }
                } else if (normToApply.standard != null && normToApply.control != null) {
                    // Find or create norm
                    val normName = "${normToApply.standard}: ${normToApply.control}"
                    val existingByName = normRepository.findByName(normName)
                    if (existingByName.isPresent) {
                        existingNormsLinked++
                        existingByName.get()
                    } else {
                        newNormsCreated++
                        findOrCreateNorm(normToApply.standard, normToApply.control, normToApply.version)
                    }
                } else {
                    logger.warn("Invalid norm specification: neither normId nor standard/control provided")
                    continue
                }

                // Add norm to requirement if not already present
                if (!requirement.norms.any { it.id == norm.id }) {
                    requirement.norms.add(norm)
                    requirementUpdated = true
                    logger.debug("Added norm {} to requirement {}", norm.name, requirement.id)
                }
            }

            if (requirementUpdated) {
                requirementRepository.save(requirement)
                updatedRequirements++
            }
        }

        logger.info("Applied mappings: {} requirements updated, {} new norms created, {} existing norms linked",
            updatedRequirements, newNormsCreated, existingNormsLinked)

        return ApplyMappingsResponse(
            updatedRequirements = updatedRequirements,
            newNormsCreated = newNormsCreated,
            existingNormsLinked = existingNormsLinked
        )
    }
}
