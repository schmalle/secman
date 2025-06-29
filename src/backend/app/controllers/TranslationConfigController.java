package controllers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import models.TranslationConfig;
import play.db.jpa.JPAApi;
import play.libs.Json;
import play.mvc.Controller;
import play.mvc.Http;
import play.mvc.Result;
import services.TranslationService;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.persistence.TypedQuery;
import java.util.List;
import java.util.Optional;

@Singleton
public class TranslationConfigController extends Controller {

    private final JPAApi jpaApi;
    private final TranslationService translationService;
    private final ObjectMapper objectMapper;

    @Inject
    public TranslationConfigController(JPAApi jpaApi, TranslationService translationService) {
        this.jpaApi = jpaApi;
        this.translationService = translationService;
        this.objectMapper = new ObjectMapper();
    }

    public Result list(Http.Request request) {
        // Check if user is authenticated
        Optional<String> usernameOpt = request.session().get("username");
        if (usernameOpt.isEmpty()) {
            return unauthorized(Json.toJson("Authentication required"));
        }

        return jpaApi.withTransaction(em -> {
            TypedQuery<TranslationConfig> query = em.createQuery(
                "SELECT tc FROM TranslationConfig tc ORDER BY tc.updatedAt DESC",
                TranslationConfig.class
            );
            List<TranslationConfig> configs = query.getResultList();
            
            // Hide sensitive information like API keys in the list
            for (TranslationConfig config : configs) {
                if (config.getApiKey() != null && !config.getApiKey().isEmpty()) {
                    config.setApiKey("***HIDDEN***");
                }
            }
            
            return ok(Json.toJson(configs));
        });
    }

    public Result get(Http.Request request, Long id) {
        // Check if user is authenticated
        Optional<String> usernameOpt = request.session().get("username");
        if (usernameOpt.isEmpty()) {
            return unauthorized(Json.toJson("Authentication required"));
        }

        return jpaApi.withTransaction(em -> {
            TranslationConfig config = em.find(TranslationConfig.class, id);
            if (config == null) {
                return notFound(Json.toJson("Translation configuration not found"));
            }
            
            // Hide API key in single item view too
            if (config.getApiKey() != null && !config.getApiKey().isEmpty()) {
                config.setApiKey("***HIDDEN***");
            }
            
            return ok(Json.toJson(config));
        });
    }

    public Result create(Http.Request request) {
        JsonNode json = request.body().asJson();
        if (json == null) {
            return badRequest(Json.toJson("Invalid JSON"));
        }

        // Check if user is authenticated
        Optional<String> usernameOpt = request.session().get("username");
        if (usernameOpt.isEmpty()) {
            return unauthorized(Json.toJson("Authentication required"));
        }

        try {
            return jpaApi.withTransaction(em -> {
                TranslationConfig config = new TranslationConfig();
                updateConfigFromJson(config, json);
                
                // If this is marked as active, deactivate others
                if (Boolean.TRUE.equals(config.getIsActive())) {
                    em.createQuery("UPDATE TranslationConfig tc SET tc.isActive = false").executeUpdate();
                }
                
                em.persist(config);
                return created(Json.toJson(config));
            });
        } catch (Exception e) {
            return badRequest(Json.toJson("Invalid translation configuration data: " + e.getMessage()));
        }
    }

    public Result update(Http.Request request, Long id) {
        JsonNode json = request.body().asJson();
        if (json == null) {
            return badRequest(Json.toJson("Invalid JSON"));
        }

        // Check if user is authenticated
        Optional<String> usernameOpt = request.session().get("username");
        if (usernameOpt.isEmpty()) {
            return unauthorized(Json.toJson("Authentication required"));
        }

        return jpaApi.withTransaction(em -> {
            TranslationConfig config = em.find(TranslationConfig.class, id);
            if (config == null) {
                return notFound(Json.toJson("Translation configuration not found"));
            }

            try {
                updateConfigFromJson(config, json);
                
                // If this is marked as active, deactivate others
                if (Boolean.TRUE.equals(config.getIsActive())) {
                    em.createQuery("UPDATE TranslationConfig tc SET tc.isActive = false WHERE tc.id != :id")
                      .setParameter("id", id)
                      .executeUpdate();
                }
                
                em.merge(config);
                return ok(Json.toJson(config));
            } catch (Exception e) {
                return badRequest(Json.toJson("Invalid translation configuration data: " + e.getMessage()));
            }
        });
    }

    public Result delete(Http.Request request, Long id) {
        // Check if user is authenticated
        Optional<String> usernameOpt = request.session().get("username");
        if (usernameOpt.isEmpty()) {
            return unauthorized(Json.toJson("Authentication required"));
        }

        return jpaApi.withTransaction(em -> {
            TranslationConfig config = em.find(TranslationConfig.class, id);
            if (config == null) {
                return notFound(Json.toJson("Translation configuration not found"));
            }

            em.remove(config);
            return ok(Json.toJson("Translation configuration deleted successfully"));
        });
    }

    public Result testConfiguration(Http.Request request, Long id) {
        JsonNode json = request.body().asJson();
        final String testText;
        
        if (json != null && json.has("testText")) {
            testText = json.get("testText").asText();
        } else {
            testText = "This is a test message for translation configuration verification.";
        }

        // Check if user is authenticated
        Optional<String> usernameOpt = request.session().get("username");
        if (usernameOpt.isEmpty()) {
            return unauthorized(Json.toJson("Authentication required"));
        }

        return jpaApi.withTransaction(em -> {
            TranslationConfig config = em.find(TranslationConfig.class, id);
            if (config == null) {
                return notFound(Json.toJson("Translation configuration not found"));
            }

            // Temporarily activate this configuration for testing
            Boolean originalActive = config.getIsActive();
            config.setIsActive(true);
            em.merge(config);

            try {
                boolean success = translationService.testConfiguration(config, testText)
                    .toCompletableFuture().join();

                if (success) {
                    return ok(Json.toJson("Translation test successful"));
                } else {
                    return internalServerError(Json.toJson("Translation test failed"));
                }
            } finally {
                // Restore original active status
                config.setIsActive(originalActive);
                em.merge(config);
            }
        });
    }

    public Result getActiveConfiguration(Http.Request request) {
        // Check if user is authenticated
        Optional<String> usernameOpt = request.session().get("username");
        if (usernameOpt.isEmpty()) {
            return unauthorized(Json.toJson("Authentication required"));
        }

        return jpaApi.withTransaction(em -> {
            TypedQuery<TranslationConfig> query = em.createQuery(
                "SELECT tc FROM TranslationConfig tc WHERE tc.isActive = true ORDER BY tc.updatedAt DESC",
                TranslationConfig.class
            );
            query.setMaxResults(1);
            
            try {
                TranslationConfig config = query.getSingleResult();
                // Hide API key
                if (config.getApiKey() != null && !config.getApiKey().isEmpty()) {
                    config.setApiKey("***HIDDEN***");
                }
                return ok(Json.toJson(config));
            } catch (Exception e) {
                return notFound(Json.toJson("No active translation configuration found"));
            }
        });
    }

    private void updateConfigFromJson(TranslationConfig config, JsonNode json) {
        if (json.has("baseUrl")) config.setBaseUrl(json.get("baseUrl").asText());
        if (json.has("modelName")) config.setModelName(json.get("modelName").asText());
        if (json.has("maxTokens")) config.setMaxTokens(json.get("maxTokens").asInt());
        if (json.has("temperature")) config.setTemperature(json.get("temperature").asDouble());
        
        // Only update API key if it's not the hidden placeholder
        if (json.has("apiKey")) {
            String apiKey = json.get("apiKey").asText();
            if (!"***HIDDEN***".equals(apiKey)) {
                config.setApiKey(apiKey);
            }
        }
        
        if (json.has("isActive")) config.setIsActive(json.get("isActive").asBoolean());
    }
}