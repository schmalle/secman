package controllers;

import actions.AdminOnly;
import actions.Secured;
import com.fasterxml.jackson.databind.JsonNode;
import models.IdentityProvider;
import play.db.jpa.JPAApi;
import play.libs.Json;
import play.mvc.*;
import play.mvc.Results;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.persistence.TypedQuery;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

@Singleton
public class IdentityProviderController extends Controller {

    private final JPAApi jpaApi;

    @Inject
    public IdentityProviderController(JPAApi jpaApi) {
        this.jpaApi = jpaApi;
    }

    /**
     * Get all identity providers (admin only)
     */
    @With(AdminOnly.class)
    public Result list() {
        return jpaApi.withTransaction(em -> {
            try {
                TypedQuery<IdentityProvider> query = em.createQuery(
                    "SELECT idp FROM IdentityProvider idp ORDER BY idp.name", IdentityProvider.class);
                List<IdentityProvider> providers = query.getResultList();
                return Results.ok(Json.toJson(providers));
            } catch (Exception e) {
                play.Logger.error("Error listing identity providers", e);
                return Results.internalServerError(Json.newObject().put("error", "Could not retrieve identity providers"));
            }
        });
    }

    /**
     * Get enabled identity providers for login page (public)
     */
    public Result listEnabled() {
        return jpaApi.withTransaction(em -> {
            try {
                TypedQuery<IdentityProvider> query = em.createQuery(
                    "SELECT idp FROM IdentityProvider idp WHERE idp.enabled = true ORDER BY idp.name", 
                    IdentityProvider.class);
                List<IdentityProvider> providers = query.getResultList();
                
                // Return only public information for login page
                List<Map<String, Object>> publicProviders = providers.stream()
                    .map(idp -> {
                        Map<String, Object> publicIdp = new HashMap<>();
                        publicIdp.put("id", idp.getId());
                        publicIdp.put("name", idp.getName());
                        publicIdp.put("type", idp.getType().toString());
                        publicIdp.put("buttonText", idp.getButtonText());
                        publicIdp.put("buttonColor", idp.getButtonColor());
                        return publicIdp;
                    })
                    .toList();
                
                return Results.ok(Json.toJson(publicProviders));
            } catch (Exception e) {
                play.Logger.error("Error listing enabled identity providers", e);
                return Results.internalServerError(Json.newObject().put("error", "Could not retrieve identity providers"));
            }
        });
    }

    /**
     * Get a specific identity provider by ID
     */
    @With(AdminOnly.class)
    public Result get(Long id) {
        return jpaApi.withTransaction(em -> {
            try {
                IdentityProvider provider = em.find(IdentityProvider.class, id);
                if (provider == null) {
                    return Results.notFound(Json.newObject().put("error", "Identity provider not found"));
                }
                return Results.ok(Json.toJson(provider));
            } catch (Exception e) {
                play.Logger.error("Error getting identity provider with id: " + id, e);
                return Results.internalServerError(Json.newObject().put("error", "Could not retrieve identity provider"));
            }
        });
    }

    /**
     * Create a new identity provider
     */
    @With(AdminOnly.class)
    public Result create(Http.Request request) {
        JsonNode json = request.body().asJson();
        if (json == null) {
            return Results.badRequest(Json.newObject().put("error", "Expecting JSON data"));
        }

        return jpaApi.withTransaction(em -> {
            try {
                IdentityProvider provider = new IdentityProvider();
                updateProviderFromJson(provider, json);

                // Validate required fields
                if (provider.getName() == null || provider.getName().trim().isEmpty()) {
                    return Results.badRequest(Json.newObject().put("error", "Provider name is required"));
                }

                // Check for duplicate names
                TypedQuery<Long> countQuery = em.createQuery(
                    "SELECT COUNT(idp) FROM IdentityProvider idp WHERE idp.name = :name", Long.class);
                countQuery.setParameter("name", provider.getName());
                Long count = countQuery.getSingleResult();
                
                if (count > 0) {
                    return Results.badRequest(Json.newObject().put("error", "Identity provider with this name already exists"));
                }

                em.persist(provider);
                em.flush();

                return Results.created(Json.toJson(provider));
            } catch (Exception e) {
                play.Logger.error("Error creating identity provider", e);
                return Results.internalServerError(Json.newObject().put("error", "Could not create identity provider"));
            }
        });
    }

    /**
     * Update an existing identity provider
     */
    @With(AdminOnly.class)
    public Result update(Long id, Http.Request request) {
        JsonNode json = request.body().asJson();
        if (json == null) {
            return Results.badRequest(Json.newObject().put("error", "Expecting JSON data"));
        }

        return jpaApi.withTransaction(em -> {
            try {
                IdentityProvider provider = em.find(IdentityProvider.class, id);
                if (provider == null) {
                    return Results.notFound(Json.newObject().put("error", "Identity provider not found"));
                }

                updateProviderFromJson(provider, json);

                // Validate required fields
                if (provider.getName() == null || provider.getName().trim().isEmpty()) {
                    return Results.badRequest(Json.newObject().put("error", "Provider name is required"));
                }

                // Check for duplicate names (excluding current provider)
                TypedQuery<Long> countQuery = em.createQuery(
                    "SELECT COUNT(idp) FROM IdentityProvider idp WHERE idp.name = :name AND idp.id != :id", Long.class);
                countQuery.setParameter("name", provider.getName());
                countQuery.setParameter("id", id);
                Long count = countQuery.getSingleResult();
                
                if (count > 0) {
                    return Results.badRequest(Json.newObject().put("error", "Identity provider with this name already exists"));
                }

                provider = em.merge(provider);
                return Results.ok(Json.toJson(provider));
            } catch (Exception e) {
                play.Logger.error("Error updating identity provider with id: " + id, e);
                return Results.internalServerError(Json.newObject().put("error", "Could not update identity provider"));
            }
        });
    }

    /**
     * Delete an identity provider
     */
    @With(AdminOnly.class)
    public Result delete(Long id) {
        return jpaApi.withTransaction(em -> {
            try {
                IdentityProvider provider = em.find(IdentityProvider.class, id);
                if (provider == null) {
                    return Results.notFound(Json.newObject().put("error", "Identity provider not found"));
                }

                // Check if provider is being used by any users
                TypedQuery<Long> userCountQuery = em.createQuery(
                    "SELECT COUNT(uei) FROM UserExternalIdentity uei WHERE uei.provider.id = :providerId", Long.class);
                userCountQuery.setParameter("providerId", id);
                Long userCount = userCountQuery.getSingleResult();
                
                if (userCount > 0) {
                    return Results.badRequest(Json.newObject()
                        .put("error", "Cannot delete identity provider that is in use by " + userCount + " user(s)"));
                }

                em.remove(provider);
                return Results.ok(Json.newObject().put("message", "Identity provider deleted successfully"));
            } catch (Exception e) {
                play.Logger.error("Error deleting identity provider with id: " + id, e);
                return Results.internalServerError(Json.newObject().put("error", "Could not delete identity provider"));
            }
        });
    }

    /**
     * Test identity provider configuration
     */
    @With(AdminOnly.class)
    public Result test(Long id) {
        return jpaApi.withTransaction(em -> {
            try {
                IdentityProvider provider = em.find(IdentityProvider.class, id);
                if (provider == null) {
                    return Results.notFound(Json.newObject().put("error", "Identity provider not found"));
                }

                // Basic validation
                if (provider.isOidc()) {
                    if (provider.getClientId() == null || provider.getClientSecret() == null) {
                        return Results.badRequest(Json.newObject().put("error", "Client ID and Secret are required for OIDC"));
                    }
                    if (provider.getDiscoveryUrl() == null && 
                        (provider.getAuthorizationUrl() == null || provider.getTokenUrl() == null)) {
                        return Results.badRequest(Json.newObject().put("error", "Discovery URL or Authorization/Token URLs are required"));
                    }
                }

                return Results.ok(Json.newObject().put("message", "Identity provider configuration is valid"));
            } catch (Exception e) {
                play.Logger.error("Error testing identity provider with id: " + id, e);
                return Results.internalServerError(Json.newObject().put("error", "Could not test identity provider"));
            }
        });
    }

    /**
     * Helper method to update provider from JSON
     */
    private void updateProviderFromJson(IdentityProvider provider, JsonNode json) {
        if (json.has("name")) {
            provider.setName(json.get("name").asText());
        }
        if (json.has("type")) {
            provider.setType(IdentityProvider.ProviderType.valueOf(json.get("type").asText()));
        }
        if (json.has("clientId")) {
            provider.setClientId(json.get("clientId").asText());
        }
        if (json.has("clientSecret") && !json.get("clientSecret").asText().isEmpty()) {
            provider.setClientSecret(json.get("clientSecret").asText());
        }
        if (json.has("discoveryUrl")) {
            provider.setDiscoveryUrl(json.get("discoveryUrl").asText());
        }
        if (json.has("authorizationUrl")) {
            provider.setAuthorizationUrl(json.get("authorizationUrl").asText());
        }
        if (json.has("tokenUrl")) {
            provider.setTokenUrl(json.get("tokenUrl").asText());
        }
        if (json.has("userInfoUrl")) {
            provider.setUserInfoUrl(json.get("userInfoUrl").asText());
        }
        if (json.has("issuer")) {
            provider.setIssuer(json.get("issuer").asText());
        }
        if (json.has("jwksUri")) {
            provider.setJwksUri(json.get("jwksUri").asText());
        }
        if (json.has("scopes")) {
            provider.setScopes(json.get("scopes").asText());
        }
        if (json.has("enabled")) {
            provider.setEnabled(json.get("enabled").asBoolean());
        }
        if (json.has("autoProvision")) {
            provider.setAutoProvision(json.get("autoProvision").asBoolean());
        }
        if (json.has("buttonText")) {
            provider.setButtonText(json.get("buttonText").asText());
        }
        if (json.has("buttonColor")) {
            provider.setButtonColor(json.get("buttonColor").asText());
        }
        if (json.has("roleMapping")) {
            Map<String, String> roleMapping = new HashMap<>();
            json.get("roleMapping").fields().forEachRemaining(entry -> {
                roleMapping.put(entry.getKey(), entry.getValue().asText());
            });
            provider.setRoleMapping(roleMapping);
        }
        if (json.has("claimMappings")) {
            Map<String, String> claimMappings = new HashMap<>();
            json.get("claimMappings").fields().forEachRemaining(entry -> {
                claimMappings.put(entry.getKey(), entry.getValue().asText());
            });
            provider.setClaimMappings(claimMappings);
        }
    }
}