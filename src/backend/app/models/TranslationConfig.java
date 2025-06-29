package models;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDateTime;

@Entity
@Table(name = "translation_config")
public class TranslationConfig {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "api_key", nullable = false, length = 512)
    @NotNull
    private String apiKey;

    @Column(name = "base_url", nullable = false, length = 255)
    @NotNull
    private String baseUrl = "https://openrouter.ai/api/v1";

    @Column(name = "model_name", nullable = false, length = 255)
    @NotNull
    private String modelName = "anthropic/claude-3-haiku";

    @Column(name = "is_active")
    private Boolean isActive = true;

    @Column(name = "max_tokens")
    private Integer maxTokens = 4000;

    @Column(name = "temperature")
    private Double temperature = 0.3;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    // Constructors
    public TranslationConfig() {}

    public TranslationConfig(String apiKey, String baseUrl, String modelName) {
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.modelName = modelName;
        this.isActive = true;
    }

    // Helper methods
    public boolean isConfigured() {
        return apiKey != null && !apiKey.trim().isEmpty() &&
               baseUrl != null && !baseUrl.trim().isEmpty() &&
               modelName != null && !modelName.trim().isEmpty();
    }

    // Getters and Setters
    public Long getId() {
        return id;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getModelName() {
        return modelName;
    }

    public void setModelName(String modelName) {
        this.modelName = modelName;
    }

    public Boolean getIsActive() {
        return isActive;
    }

    public void setIsActive(Boolean isActive) {
        this.isActive = isActive;
    }

    public Integer getMaxTokens() {
        return maxTokens;
    }

    public void setMaxTokens(Integer maxTokens) {
        this.maxTokens = maxTokens;
    }

    public Double getTemperature() {
        return temperature;
    }

    public void setTemperature(Double temperature) {
        this.temperature = temperature;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    // Lifecycle methods
    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = createdAt;
        if (isActive == null) {
            isActive = true;
        }
        if (maxTokens == null) {
            maxTokens = 4000;
        }
        if (temperature == null) {
            temperature = 0.3;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    @Override
    public String toString() {
        return "TranslationConfig{" +
                "id=" + id +
                ", baseUrl='" + baseUrl + '\'' +
                ", modelName='" + modelName + '\'' +
                ", isActive=" + isActive +
                ", maxTokens=" + maxTokens +
                ", temperature=" + temperature +
                ", createdAt=" + createdAt +
                ", updatedAt=" + updatedAt +
                '}';
    }
}