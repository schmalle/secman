package com.secman.domain

import io.micronaut.serde.annotation.Serdeable
import jakarta.persistence.*
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import jakarta.validation.constraints.Pattern
import java.time.LocalDateTime

@Entity
@Table(name = "translation_config")
@Serdeable
data class TranslationConfig(
    @Id
    @GeneratedValue
    var id: Long? = null,

    @Column(name = "api_key", length = 512, nullable = false)
    @NotBlank
    @Size(max = 512)
    var apiKey: String,

    @Column(name = "base_url", nullable = false)
    @NotBlank
    @Pattern(regexp = "^https?://.*", message = "Must be a valid URL")
    var baseUrl: String = "https://openrouter.ai/api/v1",

    @Column(name = "model_name", nullable = false)
    @NotBlank
    var modelName: String = "anthropic/claude-3-haiku",

    @Column(name = "max_tokens", nullable = false)
    @Min(100) @Max(8000)
    var maxTokens: Int = 4000,

    @Column(name = "temperature", nullable = false)
    @Min(0) @Max(1)
    var temperature: Double = 0.3,

    @Column(name = "is_active", nullable = false)
    var isActive: Boolean = false,

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: LocalDateTime? = null,

    @Column(name = "updated_at", nullable = false)
    var updatedAt: LocalDateTime? = null
) {
    
    companion object {
        const val API_KEY_MASK = "***HIDDEN***"
        
        // Popular AI models for dropdown
        val AVAILABLE_MODELS = mapOf(
            "anthropic/claude-3-haiku" to "Claude 3 Haiku (Fast & Affordable)",
            "anthropic/claude-3-sonnet" to "Claude 3 Sonnet (Balanced)",
            "anthropic/claude-3-opus" to "Claude 3 Opus (Most Capable)",
            "openai/gpt-4o" to "GPT-4o (Latest OpenAI)",
            "openai/gpt-4o-mini" to "GPT-4o Mini (Fast & Affordable)",
            "meta-llama/llama-3.1-8b-instruct" to "Llama 3.1 8B (Open Source)",
            "meta-llama/llama-3.1-70b-instruct" to "Llama 3.1 70B (Open Source)"
        )
        
        // Supported languages
        val SUPPORTED_LANGUAGES = mapOf(
            "en" to "English",
            "de" to "German",
            "fr" to "French",
            "es" to "Spanish",
            "it" to "Italian",
            "pt" to "Portuguese",
            "nl" to "Dutch",
            "sv" to "Swedish",
            "da" to "Danish",
            "no" to "Norwegian"
        )
    }

    @PrePersist
    fun onCreate() {
        val now = LocalDateTime.now()
        createdAt = now
        updatedAt = now
    }

    @PreUpdate
    fun onUpdate() {
        updatedAt = LocalDateTime.now()
    }

    /**
     * Create a copy with API key masked for API responses
     */
    fun toSafeResponse(): TranslationConfig {
        return this.copy(
            apiKey = if (apiKey.isNotBlank()) API_KEY_MASK else ""
        )
    }

    /**
     * Check if API key update is needed (not the masked value)
     */
    fun shouldUpdateApiKey(newApiKey: String?): Boolean {
        return newApiKey != null && newApiKey != API_KEY_MASK && newApiKey.isNotBlank()
    }

    /**
     * Check if configuration has valid settings for API calls
     */
    fun isValid(): Boolean {
        return apiKey.isNotBlank() && 
               baseUrl.isNotBlank() && 
               modelName.isNotBlank() &&
               maxTokens in 100..8000 &&
               temperature in 0.0..1.0
    }

    override fun toString(): String {
        return "TranslationConfig(id=$id, modelName='$modelName', baseUrl='$baseUrl', isActive=$isActive)"
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TranslationConfig) return false
        return id != null && id == other.id
    }

    override fun hashCode(): Int {
        return id?.hashCode() ?: 0
    }
}