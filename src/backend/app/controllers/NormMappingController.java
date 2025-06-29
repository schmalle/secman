package controllers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import models.Norm;
import models.Requirement;
import play.db.jpa.JPAApi;
import play.libs.Json;
import play.mvc.*;
import play.mvc.Results;
import services.NormMappingService;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.stream.Collectors;

@Singleton
public class NormMappingController extends Controller {

    private final JPAApi jpaApi;
    private final NormMappingService normMappingService;
    private static final play.Logger.ALogger logger = play.Logger.of(NormMappingController.class);

    @Inject
    public NormMappingController(JPAApi jpaApi, NormMappingService normMappingService) {
        this.jpaApi = jpaApi;
        this.normMappingService = normMappingService;
    }

    /**
     * Analyzes all requirements and suggests norm mappings for those missing mappings
     */
    public CompletionStage<Result> suggestMissingMappings(Http.Request request) {
        logger.info("=== SUGGEST MISSING MAPPINGS REQUEST ===");
        
        try {
            List<Requirement> requirements = jpaApi.withTransaction(em -> {
                TypedQuery<Requirement> query = em.createQuery("SELECT DISTINCT r FROM Requirement r LEFT JOIN FETCH r.norms ORDER BY r.id", Requirement.class);
                return query.getResultList();
            });
            
            if (requirements.isEmpty()) {
                ObjectNode emptyResponse = Json.newObject()
                    .put("message", "No requirements found")
                    .<ObjectNode>set("suggestions", Json.newArray());
                return CompletableFuture.completedFuture(Results.ok(emptyResponse));
            }
            
            return normMappingService.suggestMissingMappings(requirements)
                .thenApply(suggestions -> {
                    try {
                        return buildSuggestionsResponse(suggestions, requirements);
                    } catch (Exception e) {
                        logger.error("Error building suggestions response", e);
                        return Results.internalServerError(Json.newObject()
                            .put("error", "Error processing suggestions: " + e.getMessage()));
                    }
                });
                
        } catch (Exception e) {
            logger.error("Error fetching requirements for mapping analysis", e);
            return CompletableFuture.completedFuture(Results.internalServerError(Json.newObject()
                .put("error", "Error fetching requirements: " + e.getMessage())));
        }
    }

    /**
     * Applies suggested norm mappings to requirements
     */
    public CompletionStage<Result> applyMappings(Http.Request request) {
        JsonNode json = request.body().asJson();
        if (json == null) {
            return CompletableFuture.completedFuture(Results.badRequest(Json.newObject()
                .put("error", "Expecting JSON data")));
        }

        try {
            Map<Long, List<Long>> mappings = parseMappingRequest(json);
            
            if (mappings.isEmpty()) {
                return CompletableFuture.completedFuture(Results.badRequest(Json.newObject()
                    .put("error", "No valid mappings provided")));
            }

            return normMappingService.applyNormMappings(mappings)
                .thenApply(updatedCount -> {
                    ObjectNode response = Json.newObject();
                    response.put("message", "Norm mappings applied successfully");
                    response.put("updatedRequirements", updatedCount);
                    return Results.ok(response);
                })
                .exceptionally(throwable -> {
                    logger.error("Error applying norm mappings", throwable);
                    return Results.internalServerError(Json.newObject()
                        .put("error", "Error applying mappings: " + throwable.getMessage()));
                });

        } catch (Exception e) {
            logger.error("Error parsing mapping request", e);
            return CompletableFuture.completedFuture(Results.badRequest(Json.newObject()
                .put("error", "Invalid request format: " + e.getMessage())));
        }
    }

    /**
     * Ensures required norms exist in the database
     */
    public Result ensureRequiredNorms(Http.Request request) {
        return jpaApi.withTransaction(em -> {
            try {
                int createdCount = 0;
                
                // Required norms for mapping
                Map<String, String> requiredNorms = Map.of(
                    "NIST SP 800-53", "Rev 5",
                    "ISO/IEC 27001", "2022",
                    "IEC 62443", "2018"
                );
                
                for (Map.Entry<String, String> entry : requiredNorms.entrySet()) {
                    String normName = entry.getKey();
                    String version = entry.getValue();
                    
                    // Check if norm already exists
                    TypedQuery<Long> countQuery = em.createQuery(
                        "SELECT COUNT(n) FROM Norm n WHERE n.name = :name", Long.class);
                    countQuery.setParameter("name", normName);
                    Long count = countQuery.getSingleResult();
                    
                    if (count == 0) {
                        // Create the norm
                        Norm norm = new Norm();
                        norm.setName(normName);
                        norm.setVersion(version);
                        
                        // Set year based on version/standard
                        if (normName.contains("27001")) {
                            norm.setYear(2022);
                        } else if (normName.contains("62443")) {
                            norm.setYear(2018);
                        } else if (normName.contains("800-53")) {
                            norm.setYear(2020); // Rev 5 was published in 2020
                        }
                        
                        em.persist(norm);
                        createdCount++;
                        logger.info("Created norm: " + normName + " " + version);
                    }
                }
                
                ObjectNode response = Json.newObject();
                response.put("message", "Required norms ensured");
                response.put("createdNorms", createdCount);
                return Results.ok(response);
                
            } catch (Exception e) {
                logger.error("Error ensuring required norms", e);
                return Results.internalServerError(Json.newObject()
                    .put("error", "Error creating required norms: " + e.getMessage()));
            }
        });
    }

    /**
     * Gets available norms for mapping
     */
    public Result getAvailableNorms(Http.Request request) {
        return jpaApi.withTransaction(em -> {
            try {
                TypedQuery<Norm> query = em.createQuery(
                    "SELECT n FROM Norm n WHERE n.name LIKE '%NIST%' OR n.name LIKE '%ISO%' OR n.name LIKE '%IEC%' ORDER BY n.name", 
                    Norm.class);
                List<Norm> norms = query.getResultList();
                
                return Results.ok(Json.toJson(norms));
                
            } catch (Exception e) {
                logger.error("Error fetching available norms", e);
                return Results.internalServerError(Json.newObject()
                    .put("error", "Error fetching norms: " + e.getMessage()));
            }
        });
    }

    private Result buildSuggestionsResponse(Map<Long, List<NormMappingService.NormSuggestion>> suggestions, 
                                          List<Requirement> requirements) {
        
        ObjectNode response = Json.newObject();
        ArrayNode suggestionsArray = Json.newArray();
        
        // Get existing norms from database for ID mapping
        Map<String, Long> normNameToId = jpaApi.withTransaction(em -> {
            TypedQuery<Norm> query = em.createQuery("SELECT n FROM Norm n", Norm.class);
            return query.getResultList().stream()
                .collect(Collectors.toMap(Norm::getName, Norm::getId));
        });
        
        for (Map.Entry<Long, List<NormMappingService.NormSuggestion>> entry : suggestions.entrySet()) {
            Long requirementId = entry.getKey();
            List<NormMappingService.NormSuggestion> normSuggestions = entry.getValue();
            
            // Find the requirement details
            Optional<Requirement> requirement = requirements.stream()
                .filter(r -> r.getId().equals(requirementId))
                .findFirst();
                
            if (requirement.isPresent()) {
                ObjectNode suggestionObj = Json.newObject();
                suggestionObj.put("requirementId", requirementId);
                suggestionObj.put("requirementTitle", requirement.get().getShortreq());
                
                ArrayNode normSuggestionsArray = Json.newArray();
                for (NormMappingService.NormSuggestion normSuggestion : normSuggestions) {
                    ObjectNode normObj = Json.newObject();
                    normObj.put("standard", normSuggestion.getStandard());
                    normObj.put("confidence", normSuggestion.getConfidence());
                    normObj.put("reasoning", normSuggestion.getReasoning());
                    
                    // Try to find the norm ID
                    Long normId = findBestMatchingNormId(normSuggestion.getStandard(), normNameToId);
                    if (normId != null) {
                        normObj.put("normId", normId);
                    }
                    
                    normSuggestionsArray.add(normObj);
                }
                
                suggestionObj.set("suggestions", normSuggestionsArray);
                suggestionsArray.add(suggestionObj);
            }
        }
        
        response.put("message", "Norm mapping suggestions generated");
        response.put("totalRequirements", requirements.size());
        response.put("requirementsWithSuggestions", suggestions.size());
        response.set("suggestions", suggestionsArray);
        
        return Results.ok(response);
    }

    private Long findBestMatchingNormId(String standard, Map<String, Long> normNameToId) {
        // Try exact match first
        if (normNameToId.containsKey(standard)) {
            return normNameToId.get(standard);
        }
        
        // Try partial matches
        for (Map.Entry<String, Long> entry : normNameToId.entrySet()) {
            String normName = entry.getKey().toLowerCase();
            String standardLower = standard.toLowerCase();
            
            if ((standardLower.contains("nist") && normName.contains("nist")) ||
                (standardLower.contains("iso") && normName.contains("iso")) ||
                (standardLower.contains("iec") && normName.contains("iec"))) {
                return entry.getValue();
            }
        }
        
        return null;
    }

    private Map<Long, List<Long>> parseMappingRequest(JsonNode json) {
        Map<Long, List<Long>> mappings = new HashMap<>();
        
        if (json.has("mappings") && json.get("mappings").isObject()) {
            JsonNode mappingsNode = json.get("mappings");
            
            mappingsNode.fields().forEachRemaining(entry -> {
                try {
                    Long requirementId = Long.parseLong(entry.getKey());
                    JsonNode normIdsNode = entry.getValue();
                    
                    if (normIdsNode.isArray()) {
                        List<Long> normIds = new ArrayList<>();
                        for (JsonNode normIdNode : normIdsNode) {
                            if (normIdNode.isLong()) {
                                normIds.add(normIdNode.asLong());
                            }
                        }
                        if (!normIds.isEmpty()) {
                            mappings.put(requirementId, normIds);
                        }
                    }
                } catch (NumberFormatException e) {
                    logger.warn("Invalid requirement ID in mapping request: " + entry.getKey());
                }
            });
        }
        
        return mappings;
    }

    private static CompletableFuture<Object> completedFuture(Result result) {
        return CompletableFuture.completedFuture(result);
    }
}