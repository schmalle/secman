package services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import models.Norm;
import models.Requirement;
import models.TranslationConfig;
import play.db.jpa.JPAApi;
import play.libs.ws.WSClient;
import play.libs.ws.WSRequest;
import play.libs.ws.WSResponse;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.persistence.TypedQuery;
import java.util.*;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CompletableFuture;
import java.time.Duration;
import java.util.stream.Collectors;

@Singleton
public class NormMappingService {

    private final JPAApi jpaApi;
    private final WSClient wsClient;
    private final ObjectMapper objectMapper;
    private static final play.Logger.ALogger logger = play.Logger.of(NormMappingService.class);

    // Target security standards for mapping
    private static final Map<String, String> TARGET_STANDARDS = Map.of(
        "NIST SP 800-53", "A comprehensive catalog of security and privacy controls for information systems",
        "ISO/IEC 27001", "International standard for information security management systems",
        "IEC 62443", "Industrial communication networks - Network and system security"
    );

    @Inject
    public NormMappingService(JPAApi jpaApi, WSClient wsClient) {
        this.jpaApi = jpaApi;
        this.wsClient = wsClient;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Analyzes a requirement and suggests appropriate norm mappings using AI
     */
    public CompletionStage<List<NormSuggestion>> suggestNormMappings(Requirement requirement) {
        return getActiveTranslationConfig().thenCompose(configOpt -> {
            if (configOpt.isEmpty()) {
                logger.error("No active translation configuration found for AI norm mapping");
                return CompletableFuture.completedFuture(Collections.emptyList());
            }

            TranslationConfig config = configOpt.get();
            return performAIMappingAnalysis(requirement, config);
        });
    }

    /**
     * Processes multiple requirements to find those missing norm mappings and suggests mappings
     */
    public CompletionStage<Map<Long, List<NormSuggestion>>> suggestMissingMappings(List<Requirement> requirements) {
        // Filter requirements that have no or insufficient norm mappings
        List<Requirement> requirementsMissingMappings = requirements.stream()
            .filter(this::needsNormMapping)
            .collect(Collectors.toList());

        if (requirementsMissingMappings.isEmpty()) {
            return CompletableFuture.completedFuture(Collections.emptyMap());
        }

        // Process in batches to avoid overwhelming the AI service
        List<CompletionStage<Map<Long, List<NormSuggestion>>>> batchFutures = new ArrayList<>();
        int batchSize = 5; // Process 5 requirements at a time

        for (int i = 0; i < requirementsMissingMappings.size(); i += batchSize) {
            int endIndex = Math.min(i + batchSize, requirementsMissingMappings.size());
            List<Requirement> batch = requirementsMissingMappings.subList(i, endIndex);
            batchFutures.add(processBatch(batch));
        }

        // Combine all batch results
        return CompletableFuture.allOf(batchFutures.toArray(new CompletableFuture[0]))
            .thenApply(v -> {
                Map<Long, List<NormSuggestion>> combinedResults = new HashMap<>();
                for (CompletionStage<Map<Long, List<NormSuggestion>>> future : batchFutures) {
                    try {
                        combinedResults.putAll(future.toCompletableFuture().join());
                    } catch (Exception e) {
                        logger.error("Error processing batch results", e);
                    }
                }
                return combinedResults;
            });
    }

    /**
     * Applies suggested norm mappings to requirements
     */
    public CompletionStage<Integer> applyNormMappings(Map<Long, List<Long>> requirementToNormIds) {
        return CompletableFuture.supplyAsync(() -> {
            return jpaApi.withTransaction(em -> {
                int updatedCount = 0;
                
                for (Map.Entry<Long, List<Long>> entry : requirementToNormIds.entrySet()) {
                    Long requirementId = entry.getKey();
                    List<Long> normIds = entry.getValue();
                    
                    try {
                        Requirement requirement = em.find(Requirement.class, requirementId);
                        if (requirement == null) {
                            logger.warn("Requirement not found: " + requirementId);
                            continue;
                        }

                        // Get the norms to add
                        Set<Norm> normsToAdd = new HashSet<>();
                        for (Long normId : normIds) {
                            Norm norm = em.find(Norm.class, normId);
                            if (norm != null) {
                                normsToAdd.add(norm);
                            }
                        }

                        // Add to existing norms (don't replace)
                        Set<Norm> existingNorms = requirement.getNorms();
                        if (existingNorms == null) {
                            existingNorms = new HashSet<>();
                        }
                        existingNorms.addAll(normsToAdd);
                        requirement.setNorms(existingNorms);

                        em.merge(requirement);
                        updatedCount++;
                        
                    } catch (Exception e) {
                        logger.error("Error applying norm mappings for requirement " + requirementId, e);
                    }
                }
                
                return updatedCount;
            });
        });
    }

    private CompletionStage<Map<Long, List<NormSuggestion>>> processBatch(List<Requirement> batch) {
        return getActiveTranslationConfig().thenCompose(configOpt -> {
            if (configOpt.isEmpty()) {
                return CompletableFuture.completedFuture(Collections.emptyMap());
            }

            TranslationConfig config = configOpt.get();
            return performBatchAIMappingAnalysis(batch, config);
        });
    }

    private CompletionStage<List<NormSuggestion>> performAIMappingAnalysis(Requirement requirement, TranslationConfig config) {
        try {
            String prompt = buildMappingPrompt(requirement);
            
            // Create the request payload
            ObjectNode requestBody = objectMapper.createObjectNode();
            requestBody.put("model", config.getModelName());
            requestBody.put("max_tokens", 1000); // Increase for structured output
            requestBody.put("temperature", 0.3); // Lower temperature for more consistent analysis

            // Create messages array
            ArrayNode messages = objectMapper.createArrayNode();
            
            // System message
            ObjectNode systemMessage = objectMapper.createObjectNode();
            systemMessage.put("role", "system");
            systemMessage.put("content", buildSystemPromptForMapping());
            messages.add(systemMessage);

            // User message
            ObjectNode userMessage = objectMapper.createObjectNode();
            userMessage.put("role", "user");
            userMessage.put("content", prompt);
            messages.add(userMessage);

            requestBody.set("messages", messages);

            // Make the API call
            WSRequest request = wsClient.url(config.getBaseUrl() + "/chat/completions")
                .addHeader("Authorization", "Bearer " + config.getApiKey())
                .addHeader("Content-Type", "application/json")
                .addHeader("HTTP-Referer", "https://secman.local")
                .addHeader("X-Title", "SecMan Norm Mapping Service")
                .setRequestTimeout(Duration.ofSeconds(45));

            return request.post(requestBody).thenApply(response -> parseMappingResponse(response));

        } catch (Exception e) {
            logger.error("Error creating norm mapping request", e);
            return CompletableFuture.completedFuture(Collections.emptyList());
        }
    }

    private CompletionStage<Map<Long, List<NormSuggestion>>> performBatchAIMappingAnalysis(List<Requirement> batch, TranslationConfig config) {
        try {
            String prompt = buildBatchMappingPrompt(batch);
            
            // Create the request payload
            ObjectNode requestBody = objectMapper.createObjectNode();
            requestBody.put("model", config.getModelName());
            requestBody.put("max_tokens", 2000); // Increase for batch processing
            requestBody.put("temperature", 0.3);

            // Create messages array
            ArrayNode messages = objectMapper.createArrayNode();
            
            // System message
            ObjectNode systemMessage = objectMapper.createObjectNode();
            systemMessage.put("role", "system");
            systemMessage.put("content", buildSystemPromptForBatchMapping());
            messages.add(systemMessage);

            // User message
            ObjectNode userMessage = objectMapper.createObjectNode();
            userMessage.put("role", "user");
            userMessage.put("content", prompt);
            messages.add(userMessage);

            requestBody.set("messages", messages);

            // Make the API call
            WSRequest request = wsClient.url(config.getBaseUrl() + "/chat/completions")
                .addHeader("Authorization", "Bearer " + config.getApiKey())
                .addHeader("Content-Type", "application/json")
                .addHeader("HTTP-Referer", "https://secman.local")
                .addHeader("X-Title", "SecMan Norm Mapping Service")
                .setRequestTimeout(Duration.ofSeconds(60));

            return request.post(requestBody).thenApply(response -> parseBatchMappingResponse(response, batch));

        } catch (Exception e) {
            logger.error("Error creating batch norm mapping request", e);
            return CompletableFuture.completedFuture(Collections.emptyMap());
        }
    }

    private boolean needsNormMapping(Requirement requirement) {
        Set<Norm> norms = requirement.getNorms();
        if (norms == null || norms.isEmpty()) {
            return true;
        }
        
        // Check if it has any of the target standards
        Set<String> existingNormNames = norms.stream()
            .map(Norm::getName)
            .collect(Collectors.toSet());
            
        return TARGET_STANDARDS.keySet().stream()
            .noneMatch(target -> existingNormNames.stream()
                .anyMatch(existing -> existing.toLowerCase().contains(target.toLowerCase().split(" ")[0])));
    }

    private String buildMappingPrompt(Requirement requirement) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("Analyze this security requirement and suggest appropriate mappings:\n\n");
        prompt.append("Requirement: ").append(requirement.getShortreq()).append("\n");
        if (requirement.getDetails() != null && !requirement.getDetails().trim().isEmpty()) {
            prompt.append("Details: ").append(requirement.getDetails()).append("\n");
        }
        if (requirement.getMotivation() != null && !requirement.getMotivation().trim().isEmpty()) {
            prompt.append("Motivation: ").append(requirement.getMotivation()).append("\n");
        }
        
        prompt.append("\nCurrent norms: ");
        Set<Norm> existingNorms = requirement.getNorms();
        if (existingNorms != null && !existingNorms.isEmpty()) {
            prompt.append(existingNorms.stream()
                .map(norm -> norm.getName() + (norm.getVersion() != null ? " " + norm.getVersion() : ""))
                .collect(Collectors.joining(", ")));
        } else {
            prompt.append("None");
        }

        return prompt.toString();
    }

    private String buildBatchMappingPrompt(List<Requirement> requirements) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("Analyze these security requirements and suggest appropriate norm mappings:\n\n");
        
        for (int i = 0; i < requirements.size(); i++) {
            Requirement req = requirements.get(i);
            prompt.append("Requirement ").append(i + 1).append(" (ID: ").append(req.getId()).append("):\n");
            prompt.append("Title: ").append(req.getShortreq()).append("\n");
            if (req.getDetails() != null && !req.getDetails().trim().isEmpty()) {
                prompt.append("Details: ").append(req.getDetails()).append("\n");
            }
            prompt.append("\n");
        }

        return prompt.toString();
    }

    private String buildSystemPromptForMapping() {
        return "You are a cybersecurity expert specializing in security frameworks and standards mapping. " +
               "Your task is to analyze security requirements and suggest appropriate mappings to these standards:\n\n" +
               "1. NIST SP 800-53 - Comprehensive security controls catalog\n" +
               "2. ISO/IEC 27001 - Information security management systems\n" +
               "3. IEC 62443 - Industrial network and system security\n\n" +
               "For each requirement, suggest which standards are most relevant and provide a confidence score (1-5). " +
               "Respond in JSON format with this structure:\n" +
               "{\n" +
               "  \"suggestions\": [\n" +
               "    {\n" +
               "      \"standard\": \"NIST SP 800-53\",\n" +
               "      \"confidence\": 4,\n" +
               "      \"reasoning\": \"Brief explanation\"\n" +
               "    }\n" +
               "  ]\n" +
               "}";
    }

    private String buildSystemPromptForBatchMapping() {
        return "You are a cybersecurity expert specializing in security frameworks mapping. " +
               "Analyze each requirement and suggest mappings to NIST SP 800-53, ISO/IEC 27001, and IEC 62443. " +
               "Respond in JSON format:\n" +
               "{\n" +
               "  \"mappings\": {\n" +
               "    \"requirementId\": [\n" +
               "      {\n" +
               "        \"standard\": \"NIST SP 800-53\",\n" +
               "        \"confidence\": 4,\n" +
               "        \"reasoning\": \"Brief explanation\"\n" +
               "      }\n" +
               "    ]\n" +
               "  }\n" +
               "}";
    }

    private List<NormSuggestion> parseMappingResponse(WSResponse response) {
        try {
            if (response.getStatus() != 200) {
                logger.error("AI API error: HTTP " + response.getStatus() + " - " + response.getBody());
                return Collections.emptyList();
            }

            JsonNode responseJson = response.asJson();
            
            if (responseJson.has("choices") && responseJson.get("choices").isArray() && 
                responseJson.get("choices").size() > 0) {
                
                JsonNode firstChoice = responseJson.get("choices").get(0);
                if (firstChoice.has("message") && firstChoice.get("message").has("content")) {
                    String content = firstChoice.get("message").get("content").asText();
                    return parseNormSuggestions(content);
                }
            }

            logger.error("Unexpected response format from AI API: " + response.getBody());
            return Collections.emptyList();

        } catch (Exception e) {
            logger.error("Error parsing mapping response", e);
            return Collections.emptyList();
        }
    }

    private Map<Long, List<NormSuggestion>> parseBatchMappingResponse(WSResponse response, List<Requirement> batch) {
        try {
            if (response.getStatus() != 200) {
                logger.error("AI API error: HTTP " + response.getStatus() + " - " + response.getBody());
                return Collections.emptyMap();
            }

            JsonNode responseJson = response.asJson();
            
            if (responseJson.has("choices") && responseJson.get("choices").isArray() && 
                responseJson.get("choices").size() > 0) {
                
                JsonNode firstChoice = responseJson.get("choices").get(0);
                if (firstChoice.has("message") && firstChoice.get("message").has("content")) {
                    String content = firstChoice.get("message").get("content").asText();
                    return parseBatchNormSuggestions(content, batch);
                }
            }

            logger.error("Unexpected response format from AI API: " + response.getBody());
            return Collections.emptyMap();

        } catch (Exception e) {
            logger.error("Error parsing batch mapping response", e);
            return Collections.emptyMap();
        }
    }

    private List<NormSuggestion> parseNormSuggestions(String content) {
        try {
            // Try to extract JSON from the content
            String jsonContent = extractJsonFromText(content);
            JsonNode jsonNode = objectMapper.readTree(jsonContent);
            
            List<NormSuggestion> suggestions = new ArrayList<>();
            
            if (jsonNode.has("suggestions") && jsonNode.get("suggestions").isArray()) {
                ArrayNode suggestionsArray = (ArrayNode) jsonNode.get("suggestions");
                
                for (JsonNode suggestion : suggestionsArray) {
                    String standard = suggestion.get("standard").asText();
                    int confidence = suggestion.get("confidence").asInt();
                    String reasoning = suggestion.get("reasoning").asText();
                    
                    suggestions.add(new NormSuggestion(standard, confidence, reasoning));
                }
            }
            
            return suggestions;
            
        } catch (Exception e) {
            logger.error("Error parsing norm suggestions from content: " + content, e);
            return Collections.emptyList();
        }
    }

    private Map<Long, List<NormSuggestion>> parseBatchNormSuggestions(String content, List<Requirement> batch) {
        try {
            String jsonContent = extractJsonFromText(content);
            JsonNode jsonNode = objectMapper.readTree(jsonContent);
            
            Map<Long, List<NormSuggestion>> results = new HashMap<>();
            
            if (jsonNode.has("mappings")) {
                JsonNode mappings = jsonNode.get("mappings");
                
                // Create a map from batch index to requirement ID
                Map<String, Long> indexToId = new HashMap<>();
                for (int i = 0; i < batch.size(); i++) {
                    indexToId.put(String.valueOf(batch.get(i).getId()), batch.get(i).getId());
                    indexToId.put(String.valueOf(i + 1), batch.get(i).getId()); // 1-based index
                }
                
                mappings.fields().forEachRemaining(entry -> {
                    String key = entry.getKey();
                    Long requirementId = indexToId.get(key);
                    
                    if (requirementId != null && entry.getValue().isArray()) {
                        List<NormSuggestion> suggestions = new ArrayList<>();
                        ArrayNode suggestionsArray = (ArrayNode) entry.getValue();
                        
                        for (JsonNode suggestion : suggestionsArray) {
                            String standard = suggestion.get("standard").asText();
                            int confidence = suggestion.get("confidence").asInt();
                            String reasoning = suggestion.get("reasoning").asText();
                            
                            suggestions.add(new NormSuggestion(standard, confidence, reasoning));
                        }
                        
                        results.put(requirementId, suggestions);
                    }
                });
            }
            
            return results;
            
        } catch (Exception e) {
            logger.error("Error parsing batch norm suggestions from content: " + content, e);
            return Collections.emptyMap();
        }
    }

    private String extractJsonFromText(String text) {
        // Find JSON block within the text
        int startIndex = text.indexOf('{');
        int endIndex = text.lastIndexOf('}');
        
        if (startIndex != -1 && endIndex != -1 && endIndex > startIndex) {
            return text.substring(startIndex, endIndex + 1);
        }
        
        return text; // Return as-is if no JSON blocks found
    }

    private CompletionStage<Optional<TranslationConfig>> getActiveTranslationConfig() {
        return CompletableFuture.supplyAsync(() -> {
            return jpaApi.withTransaction(em -> {
                try {
                    TypedQuery<TranslationConfig> query = em.createQuery(
                        "SELECT tc FROM TranslationConfig tc WHERE tc.isActive = true ORDER BY tc.updatedAt DESC",
                        TranslationConfig.class
                    );
                    query.setMaxResults(1);
                    TranslationConfig config = query.getSingleResult();
                    return Optional.of(config);
                } catch (Exception e) {
                    logger.warn("No active translation configuration found: " + e.getMessage());
                    return Optional.<TranslationConfig>empty();
                }
            });
        });
    }

    /**
     * Data class for norm mapping suggestions
     */
    public static class NormSuggestion {
        private final String standard;
        private final int confidence;
        private final String reasoning;

        public NormSuggestion(String standard, int confidence, String reasoning) {
            this.standard = standard;
            this.confidence = confidence;
            this.reasoning = reasoning;
        }

        public String getStandard() { return standard; }
        public int getConfidence() { return confidence; }
        public String getReasoning() { return reasoning; }
    }
}