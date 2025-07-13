package services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import models.TranslationConfig;
import play.db.jpa.JPAApi;
import play.libs.ws.WSClient;
import play.libs.ws.WSRequest;
import play.libs.ws.WSResponse;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.persistence.TypedQuery;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CompletableFuture;
import java.time.Duration;

@Singleton
public class TranslationService {

    private final JPAApi jpaApi;
    private final WSClient wsClient;
    private final ObjectMapper objectMapper;
    private static final play.Logger.ALogger logger = play.Logger.of(TranslationService.class);

    @Inject
    public TranslationService(JPAApi jpaApi, WSClient wsClient) {
        this.jpaApi = jpaApi;
        this.wsClient = wsClient;
        this.objectMapper = new ObjectMapper();
    }

    public CompletionStage<String> translateText(String text, String targetLanguage) {
        return getActiveConfiguration().thenCompose(configOpt -> {
            if (configOpt.isEmpty()) {
                logger.error("No active translation configuration found");
                return CompletableFuture.completedFuture(text); // Return original text if no config
            }

            TranslationConfig config = configOpt.get();
            return performTranslation(text, targetLanguage, config);
        });
    }

    private CompletionStage<java.util.Optional<TranslationConfig>> getActiveConfiguration() {
        return CompletableFuture.supplyAsync(() -> {
            return jpaApi.withTransaction(em -> {
                try {
                    TypedQuery<TranslationConfig> query = em.createQuery(
                        "SELECT tc FROM TranslationConfig tc WHERE tc.isActive = true ORDER BY tc.updatedAt DESC",
                        TranslationConfig.class
                    );
                    query.setMaxResults(1);
                    TranslationConfig config = query.getSingleResult();
                    return java.util.Optional.of(config);
                } catch (Exception e) {
                    logger.warn("No active translation configuration found: " + e.getMessage());
                    return java.util.Optional.<TranslationConfig>empty();
                }
            });
        });
    }

    private CompletionStage<String> performTranslation(String text, String targetLanguage, TranslationConfig config) {
        try {
            // Validate API key format
            String apiKey = config.getApiKey();
            if (apiKey == null || apiKey.trim().isEmpty()) {
                logger.error("API key is null or empty");
                return CompletableFuture.completedFuture(text);
            }
            
            // Log API key format for debugging (only first/last few characters)
            logger.info("Using API key format: {}...{} (length: {})", 
                       apiKey.substring(0, Math.min(8, apiKey.length())),
                       apiKey.length() > 8 ? apiKey.substring(apiKey.length() - 4) : "",
                       apiKey.length());

            // Create the request payload for OpenRouter API
            ObjectNode requestBody = objectMapper.createObjectNode();
            requestBody.put("model", config.getModelName());
            requestBody.put("max_tokens", config.getMaxTokens());
            requestBody.put("temperature", config.getTemperature());

            // Create messages array
            ArrayNode messages = objectMapper.createArrayNode();
            
            // System message
            ObjectNode systemMessage = objectMapper.createObjectNode();
            systemMessage.put("role", "system");
            systemMessage.put("content", buildSystemPrompt(targetLanguage));
            messages.add(systemMessage);

            // User message
            ObjectNode userMessage = objectMapper.createObjectNode();
            userMessage.put("role", "user");
            userMessage.put("content", text);
            messages.add(userMessage);

            requestBody.set("messages", messages);

            // Log request details for debugging
            logger.info("Making translation request to: {}", config.getBaseUrl() + "/chat/completions");
            logger.info("Request model: {}, max_tokens: {}, temperature: {}", 
                       config.getModelName(), config.getMaxTokens(), config.getTemperature());

            // Make the API call with proper OpenRouter headers
            WSRequest request = wsClient.url(config.getBaseUrl() + "/chat/completions")
                .addHeader("Authorization", "Bearer " + apiKey.trim())
                .addHeader("Content-Type", "application/json")
                .addHeader("HTTP-Referer", "https://secman.local")
                .addHeader("X-Title", "SecMan Translation Service")
                .setRequestTimeout(Duration.ofSeconds(30));

            return request.post(requestBody).thenApply(response -> {
                logger.info("Received response with status: {}", response.getStatus());
                logger.info("Recieved response body content: {}", response.getBody());
                return parseTranslationResponse(response);
            });

        } catch (Exception e) {
            logger.error("Error creating translation request", e);
            return CompletableFuture.completedFuture(text); // Return original text on error
        }
    }

    private String buildSystemPrompt(String targetLanguage) {
        String languageName = getLanguageName(targetLanguage);
        return String.format(
            "You are a professional translator specializing in technical documentation and risk assessment content. " +
            "Translate the following text from English to %s. " +
            "Maintain the original meaning, technical accuracy, and formal tone. " +
            "Keep technical terms consistent and preserve any formatting. " +
            "Only respond with the translated text, no explanations or additional content.",
            languageName
        );
    }

    private String getLanguageName(String languageCode) {
        switch (languageCode.toLowerCase()) {
            case "de":
            case "german":
                return "German";
            case "en":
            case "english":
                return "English";
            default:
                return languageCode;
        }
    }

    private String parseTranslationResponse(WSResponse response) {
        try {
            String responseBody = response.getBody();
            
            if (response.getStatus() != 200) {
                // Provide detailed error information for common HTTP status codes
                String errorDetail = "";
                switch (response.getStatus()) {
                    case 401:
                        errorDetail = " - Authentication failed. Check API key validity.";
                        break;
                    case 403:
                        errorDetail = " - Forbidden. API key may lack necessary permissions.";
                        break;
                    case 429:
                        errorDetail = " - Rate limit exceeded. Try again later.";
                        break;
                    case 500:
                        errorDetail = " - OpenRouter server error.";
                        break;
                    default:
                        errorDetail = " - Unknown error.";
                        break;
                }
                
                logger.error("Translation API error: HTTP {} {} - Response: {}", 
                           response.getStatus(), errorDetail, responseBody);
                
                // Try to parse error details from response body
                try {
                    JsonNode errorJson = objectMapper.readTree(responseBody);
                    if (errorJson.has("error")) {
                        JsonNode error = errorJson.get("error");
                        String errorMessage = error.has("message") ? error.get("message").asText() : "Unknown error";
                        String errorType = error.has("type") ? error.get("type").asText() : "unknown_error";
                        String errorCode = error.has("code") ? error.get("code").asText() : "unknown";
                        
                        logger.error("OpenRouter API error details - Type: {}, Code: {}, Message: {}", 
                                   errorType, errorCode, errorMessage);
                    }
                } catch (Exception parseEx) {
                    logger.warn("Could not parse error response as JSON: {}", parseEx.getMessage());
                }
                
                return ""; // Return empty string on API error
            }

            JsonNode responseJson = response.asJson();
            logger.info("Translation API response received successfully");
            
            if (responseJson.has("choices") && responseJson.get("choices").isArray() && 
                responseJson.get("choices").size() > 0) {
                
                JsonNode firstChoice = responseJson.get("choices").get(0);
                if (firstChoice.has("message") && firstChoice.get("message").has("content")) {
                    String translatedText = firstChoice.get("message").get("content").asText();
                    logger.info("Translation completed successfully. Text length: {} characters", 
                               translatedText.length());
                    return translatedText.trim();
                }
            }

            logger.error("Unexpected response format from translation API: {}", responseBody);
            return ""; // Return empty string if response format is unexpected

        } catch (Exception e) {
            logger.error("Error parsing translation response", e);
            return ""; // Return empty string on parsing error
        }
    }

    public CompletionStage<Boolean> testConfiguration(TranslationConfig config, String testText) {
        String originalText = testText != null && !testText.trim().isEmpty() ? 
                             testText : "This is a test message for translation configuration.";
        
        return performTranslation(originalText, "german", config)
            .thenApply(translatedText -> {
                boolean success = translatedText != null && !translatedText.trim().isEmpty() && 
                                 !translatedText.equals(originalText);
                
                if (success) {
                    logger.info("Translation test successful. Original: '{}', Translated: '{}'", 
                               originalText, translatedText);
                } else {
                    logger.warn("Translation test failed. Original: '{}', Result: '{}'", 
                               originalText, translatedText);
                }
                
                return success;
            })
            .exceptionally(throwable -> {
                logger.error("Translation test failed with exception", throwable);
                return false;
            });
    }
}