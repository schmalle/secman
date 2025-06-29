package controllers;

import models.*;
import services.IdentityProviderService;
import services.UserProvisioningService;
import play.db.jpa.JPAApi;
import play.libs.Json;
import play.mvc.*;
import play.mvc.Results;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.persistence.TypedQuery;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

@Singleton
public class OAuthController extends Controller {

    private final JPAApi jpaApi;
    private final IdentityProviderService identityProviderService;
    private final UserProvisioningService userProvisioningService;
    private final SecureRandom secureRandom = new SecureRandom();

    @Inject
    public OAuthController(JPAApi jpaApi, 
                          IdentityProviderService identityProviderService,
                          UserProvisioningService userProvisioningService) {
        this.jpaApi = jpaApi;
        this.identityProviderService = identityProviderService;
        this.userProvisioningService = userProvisioningService;
    }

    /**
     * Initiate OAuth authorization flow
     */
    public Result authorize(Long providerId, Http.Request request) {
        return jpaApi.withTransaction(em -> {
            try {
                IdentityProvider provider = em.find(IdentityProvider.class, providerId);
                if (provider == null || !provider.getEnabled()) {
                    return Results.notFound(Json.newObject().put("error", "Identity provider not found or disabled"));
                }

                if (!provider.isOidc()) {
                    return Results.badRequest(Json.newObject().put("error", "Only OIDC providers supported for OAuth flow"));
                }

                // Generate state and nonce for security
                String state = generateSecureToken();
                String nonce = generateSecureToken();
                String codeVerifier = null;
                String codeChallenge = null;

                // Generate PKCE parameters for enhanced security
                codeVerifier = generateCodeVerifier();
                codeChallenge = generateCodeChallenge(codeVerifier);

                // Store OAuth state
                OAuthState oauthState = new OAuthState(state, provider);
                oauthState.setNonce(nonce);
                oauthState.setCodeVerifier(codeVerifier);
                oauthState.setRedirectUri(getRedirectUri(request));
                em.persist(oauthState);

                // Build authorization URL
                StringBuilder authUrl = new StringBuilder();
                authUrl.append(provider.getAuthorizationUrl());
                authUrl.append("?response_type=code");
                authUrl.append("&client_id=").append(URLEncoder.encode(provider.getClientId(), StandardCharsets.UTF_8));
                authUrl.append("&redirect_uri=").append(URLEncoder.encode(getRedirectUri(request), StandardCharsets.UTF_8));
                authUrl.append("&scope=").append(URLEncoder.encode(provider.getScopes(), StandardCharsets.UTF_8));
                authUrl.append("&state=").append(URLEncoder.encode(state, StandardCharsets.UTF_8));
                authUrl.append("&nonce=").append(URLEncoder.encode(nonce, StandardCharsets.UTF_8));

                // Add PKCE parameters
                if (codeChallenge != null) {
                    authUrl.append("&code_challenge=").append(URLEncoder.encode(codeChallenge, StandardCharsets.UTF_8));
                    authUrl.append("&code_challenge_method=S256");
                }

                // Redirect to identity provider
                return Results.redirect(authUrl.toString());

            } catch (Exception e) {
                play.Logger.error("Error initiating OAuth authorization", e);
                return Results.internalServerError(Json.newObject().put("error", "Could not initiate authorization"));
            }
        });
    }

    /**
     * Handle OAuth callback
     */
    public CompletionStage<Result> callback(Http.Request request) {
        String code = request.getQueryString("code");
        String state = request.getQueryString("state");
        String error = request.getQueryString("error");
        String errorDescription = request.getQueryString("error_description");

        // Handle OAuth errors
        if (error != null) {
            play.Logger.warn("OAuth error: {} - {}", error, errorDescription);
            return java.util.concurrent.CompletableFuture.completedFuture(
                Results.badRequest(Json.newObject()
                    .put("error", "OAuth authorization failed: " + error)
                    .put("description", errorDescription != null ? errorDescription : "Unknown error"))
            );
        }

        if (code == null || state == null) {
            return java.util.concurrent.CompletableFuture.completedFuture(
                Results.badRequest(Json.newObject().put("error", "Missing authorization code or state parameter"))
            );
        }

        return jpaApi.withTransaction(em -> {
            try {
                // Validate and retrieve OAuth state
                TypedQuery<OAuthState> stateQuery = em.createQuery(
                    "SELECT os FROM OAuthState os WHERE os.stateToken = :state", OAuthState.class);
                stateQuery.setParameter("state", state);
                
                Optional<OAuthState> stateOpt = stateQuery.getResultStream().findFirst();
                if (stateOpt.isEmpty()) {
                    return java.util.concurrent.CompletableFuture.completedFuture(
                        Results.badRequest(Json.newObject().put("error", "Invalid or expired state parameter"))
                    );
                }

                OAuthState oauthState = stateOpt.get();
                if (oauthState.isExpired()) {
                    em.remove(oauthState);
                    return java.util.concurrent.CompletableFuture.completedFuture(
                        Results.badRequest(Json.newObject().put("error", "OAuth state has expired"))
                    );
                }

                IdentityProvider provider = oauthState.getProvider();

                // Exchange authorization code for tokens
                return identityProviderService.exchangeCodeForTokens(provider, code, oauthState.getCodeVerifier(), getRedirectUri(request))
                    .thenCompose(tokenResponse -> {
                        if (tokenResponse == null) {
                            return java.util.concurrent.CompletableFuture.completedFuture(
                                Results.internalServerError(Json.newObject().put("error", "Failed to exchange code for tokens"))
                            );
                        }

                        // Get user info from identity provider
                        return identityProviderService.getUserInfo(provider, tokenResponse.get("access_token").asText())
                            .thenApply(userInfo -> {
                                return jpaApi.withTransaction(em2 -> {
                                    try {
                                        // Clean up OAuth state
                                        OAuthState stateToRemove = em2.find(OAuthState.class, oauthState.getId());
                                        if (stateToRemove != null) {
                                            em2.remove(stateToRemove);
                                        }

                                        // Process user login/provision
                                        String externalUserId = userInfo.get("sub").asText();
                                        String email = userInfo.has("email") ? userInfo.get("email").asText() : null;

                                        // Find or create user
                                        User user = userProvisioningService.findOrCreateUser(provider, userInfo, request);
                                        if (user == null) {
                                            logAuthEvent(AuthAuditLog.EventType.LOGIN_FAILURE, null, provider, 
                                                       externalUserId, "User provisioning failed", request);
                                            return Results.internalServerError(Json.newObject().put("error", "User provisioning failed"));
                                        }

                                        // Update or create external identity link
                                        UserExternalIdentity externalIdentity = findOrCreateExternalIdentity(em2, user, provider, userInfo, tokenResponse);
                                        externalIdentity.updateLastLogin();
                                        em2.merge(externalIdentity);

                                        // Log successful authentication
                                        logAuthEvent(AuthAuditLog.EventType.LOGIN_SUCCESS, user, provider, 
                                                   externalUserId, null, request);

                                        // Create session and redirect to dashboard
                                        Http.Session session = request.session()
                                            .adding("username", user.getUsername())
                                            .adding("roles", String.join(",", user.getRoles().stream()
                                                .map(Enum::name).toList()));

                                        return Results.redirect("/").withSession(session);

                                    } catch (Exception e) {
                                        play.Logger.error("Error processing OAuth callback", e);
                                        return Results.internalServerError(Json.newObject().put("error", "Authentication processing failed"));
                                    }
                                });
                            });
                    });

            } catch (Exception e) {
                play.Logger.error("Error in OAuth callback", e);
                return java.util.concurrent.CompletableFuture.completedFuture(
                    Results.internalServerError(Json.newObject().put("error", "Authentication failed"))
                );
            }
        }).toCompletableFuture();
    }

    /**
     * Logout from external provider
     */
    public Result logout(Http.Request request) {
        String username = request.session().get("username").orElse(null);
        if (username != null) {
            // Log logout event
            // TODO: Get user and provider info for audit log
        }

        return Results.redirect("/login").withNewSession();
    }

    // Helper methods

    private String generateSecureToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String generateCodeVerifier() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String generateCodeChallenge(String codeVerifier) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(codeVerifier.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (Exception e) {
            throw new RuntimeException("Error generating code challenge", e);
        }
    }

    private String getRedirectUri(Http.Request request) {
        String scheme = request.secure() ? "https" : "http";
        return scheme + "://" + request.host() + "/oauth/callback";
    }

    private UserExternalIdentity findOrCreateExternalIdentity(jakarta.persistence.EntityManager em, 
                                                            User user, 
                                                            IdentityProvider provider, 
                                                            com.fasterxml.jackson.databind.JsonNode userInfo,
                                                            com.fasterxml.jackson.databind.JsonNode tokenResponse) {
        String externalUserId = userInfo.get("sub").asText();
        
        TypedQuery<UserExternalIdentity> query = em.createQuery(
            "SELECT uei FROM UserExternalIdentity uei WHERE uei.provider.id = :providerId AND uei.externalUserId = :externalUserId",
            UserExternalIdentity.class);
        query.setParameter("providerId", provider.getId());
        query.setParameter("externalUserId", externalUserId);
        
        Optional<UserExternalIdentity> existingOpt = query.getResultStream().findFirst();
        
        UserExternalIdentity externalIdentity;
        if (existingOpt.isPresent()) {
            externalIdentity = existingOpt.get();
        } else {
            externalIdentity = new UserExternalIdentity(user, provider, externalUserId);
            em.persist(externalIdentity);
        }
        
        // Update user info from provider
        if (userInfo.has("email")) {
            externalIdentity.setEmail(userInfo.get("email").asText());
        }
        if (userInfo.has("name")) {
            externalIdentity.setDisplayName(userInfo.get("name").asText());
        }
        if (userInfo.has("given_name")) {
            externalIdentity.setFirstName(userInfo.get("given_name").asText());
        }
        if (userInfo.has("family_name")) {
            externalIdentity.setLastName(userInfo.get("family_name").asText());
        }
        
        // Update tokens
        if (tokenResponse.has("access_token")) {
            externalIdentity.setAccessToken(tokenResponse.get("access_token").asText());
        }
        if (tokenResponse.has("refresh_token")) {
            externalIdentity.setRefreshToken(tokenResponse.get("refresh_token").asText());
        }
        if (tokenResponse.has("expires_in")) {
            long expiresIn = tokenResponse.get("expires_in").asLong();
            externalIdentity.setTokenExpiresAt(java.time.Instant.now().plusSeconds(expiresIn));
        }
        
        return externalIdentity;
    }

    private void logAuthEvent(AuthAuditLog.EventType eventType, User user, IdentityProvider provider, 
                             String externalUserId, String errorMessage, Http.Request request) {
        try {
            jpaApi.withTransaction(em -> {
                AuthAuditLog log = new AuthAuditLog(user, provider, eventType);
                log.setExternalUserId(externalUserId);
                log.setErrorMessage(errorMessage);
                log.setIpAddress(request.remoteAddress());
                log.setUserAgent(request.getHeaders().get("User-Agent").orElse(null));
                em.persist(log);
                return null;
            });
        } catch (Exception e) {
            play.Logger.error("Error logging auth event", e);
        }
    }
}