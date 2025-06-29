package controllers;

import models.Standard;
import models.UseCase;
import play.db.jpa.JPAApi;
import play.libs.Json;
import play.mvc.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;

import javax.inject.Inject;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Set;
import java.util.HashSet;
import java.util.function.Function;

import actions.Secured;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@With(Secured.class)
public class StandardController extends Controller {

    private final JPAApi jpaApi;
    private static final Logger log = LoggerFactory.getLogger(StandardController.class);

    @Inject
    public StandardController(JPAApi jpaApi) {
        this.jpaApi = jpaApi;
    }

    public Result createStandard(Http.Request request) {
        JsonNode json = request.body().asJson();
        if (json == null || !json.has("name") || json.get("name").asText().trim().isEmpty()) {
            log.warn("Attempted to create standard with missing or empty name");
            return badRequest(Json.newObject().put("error", "Standard name cannot be empty."));
        }
        String name = json.get("name").asText().trim();
        String description = json.has("description") ? json.get("description").asText().trim() : "";

        List<Standard> existingStandards = jpaApi.withTransaction("default", true, (Function<EntityManager, List<Standard>>) entityManager ->
            entityManager.createQuery("SELECT s FROM Standard s WHERE LOWER(s.name) = LOWER(:name)", Standard.class)
                         .setParameter("name", name.toLowerCase())
                         .getResultList()
        );

        if (!existingStandards.isEmpty()) {
            log.warn("Attempted to create a standard that already exists: {}", name);
            return status(CONFLICT, Json.newObject().put("error", "Standard with name '" + name + "' already exists."));
        }

        Standard standard = new Standard();
        standard.setName(name);
        standard.setDescription(description);

        // Handle use cases if provided
        if (json.has("useCaseIds")) {
            ArrayNode useCaseIds = (ArrayNode) json.get("useCaseIds");
            Set<UseCase> useCases = new HashSet<>();
            
            try {
                for (JsonNode idNode : useCaseIds) {
                    Long useCaseId = idNode.asLong();
                    UseCase useCase = jpaApi.withTransaction("default", true, (Function<EntityManager, UseCase>) entityManager ->
                        entityManager.find(UseCase.class, useCaseId)
                    );
                    if (useCase != null) {
                        useCases.add(useCase);
                    }
                }
                standard.setUseCases(useCases);
            } catch (Exception e) {
                log.warn("Error processing use case IDs: {}", e.getMessage());
            }
        }

        try {
            jpaApi.withTransaction(entityManager -> {
                entityManager.persist(standard);
            });
            log.info("Standard created successfully: {}", standard.getName());
            return created(Json.toJson(standard));
        } catch (Exception e) {
            log.error("Error creating standard: {}", e.getMessage(), e);
            return internalServerError(Json.newObject().put("error", "Could not create standard: " + e.getMessage()));
        }
    }

    public Result getStandards(Http.Request request) {
        List<Standard> standards = jpaApi.withTransaction("default", true, (Function<EntityManager, List<Standard>>) entityManager -> {
            List<Standard> result = entityManager.createQuery("SELECT s FROM Standard s ORDER BY s.name ASC", Standard.class).getResultList();
            // Force load use cases to avoid lazy loading issues
            for (Standard standard : result) {
                standard.getUseCases().size();
            }
            return result;
        });
        log.info("Retrieved {} standards.", standards.size());
        return ok(Json.toJson(standards));
    }

    public Result getStandard(Http.Request request, Long id) {
        Standard standard = jpaApi.withTransaction("default", true, (Function<EntityManager, Standard>) entityManager -> {
            Standard result = entityManager.find(Standard.class, id);
            if (result != null) {
                // Force load use cases to avoid lazy loading issues
                result.getUseCases().size();
            }
            return result;
        });
        
        if (standard == null) {
            return notFound(Json.newObject().put("error", "Standard not found."));
        }
        
        return ok(Json.toJson(standard));
    }

    public Result updateStandard(Http.Request request, Long id) {
        JsonNode json = request.body().asJson();
        if (json == null) {
            return badRequest(Json.newObject().put("error", "Expecting JSON data"));
        }

        if (id == null) {
            log.warn("Attempted to update standard with null ID");
            return badRequest(Json.newObject().put("error", "Standard ID cannot be null."));
        }

        try {
            return jpaApi.withTransaction(entityManager -> {
                Standard existingStandard = entityManager.find(Standard.class, id);
                if (existingStandard == null) {
                    return notFound(Json.newObject().put("error", "Standard not found."));
                }

                // Update name if provided
                if (json.has("name") && !json.get("name").asText().trim().isEmpty()) {
                    String newName = json.get("name").asText().trim();
                    
                    // Check if another standard with this name exists
                    List<Standard> existingByName = entityManager
                        .createQuery("SELECT s FROM Standard s WHERE LOWER(s.name) = LOWER(:name) AND s.id != :id", Standard.class)
                        .setParameter("name", newName.toLowerCase())
                        .setParameter("id", id)
                        .getResultList();
                    
                    if (!existingByName.isEmpty()) {
                        return badRequest(Json.newObject().put("error", "Another standard with this name already exists."));
                    }
                    
                    existingStandard.setName(newName);
                }

                // Update description if provided
                if (json.has("description")) {
                    existingStandard.setDescription(json.get("description").asText().trim());
                }

                // Update use cases if provided
                if (json.has("useCaseIds")) {
                    ArrayNode useCaseIds = (ArrayNode) json.get("useCaseIds");
                    Set<UseCase> useCases = new HashSet<>();
                    
                    for (JsonNode idNode : useCaseIds) {
                        Long useCaseId = idNode.asLong();
                        UseCase useCase = entityManager.find(UseCase.class, useCaseId);
                        if (useCase != null) {
                            useCases.add(useCase);
                        }
                    }
                    existingStandard.setUseCases(useCases);
                }

                entityManager.merge(existingStandard);
                // Force load use cases for response
                existingStandard.getUseCases().size();
                
                log.info("Standard updated successfully: {}", existingStandard.getName());
                return ok(Json.toJson(existingStandard));
            });
        } catch (Exception e) {
            log.error("Error updating standard with ID {}: {}", id, e.getMessage(), e);
            return internalServerError(Json.newObject().put("error", "Could not update standard: " + e.getMessage()));
        }
    }

    public Result deleteStandard(Http.Request request, Long id) {
        if (id == null) {
            log.warn("Attempted to delete standard with null ID");
            return badRequest(Json.newObject().put("error", "Standard ID cannot be null."));
        }
        Standard standard = jpaApi.withTransaction("default", true, (Function<EntityManager, Standard>) entityManager ->
            entityManager.find(Standard.class, id)
        );
        if (standard == null) {
            log.warn("Attempted to delete non-existent standard with ID: {}", id);
            return notFound(Json.newObject().put("error", "Standard not found."));
        }
        
        try {
            jpaApi.withTransaction(entityManager -> {
                entityManager.remove(standard);
            });
            log.info("Standard with ID {} deleted successfully.", id);
            return ok(Json.newObject().put("message", "Standard deleted successfully."));
        } catch (Exception e) {
            log.error("Error deleting standard with ID {}: {}", id, e.getMessage(), e);
            if (e.getCause() instanceof org.hibernate.exception.ConstraintViolationException) {
                 log.warn("Attempted to delete standard ID {} which is still in use (constraint violation).", id);
                 return status(CONFLICT, Json.newObject().put("error", "Standard is in use and cannot be deleted due to database constraints."));
            }
            return internalServerError(Json.newObject().put("error", "Could not delete standard: " + e.getMessage()));
        }
    }
}
