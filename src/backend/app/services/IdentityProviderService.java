package services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import models.IdentityProvider;
import play.libs.Json;
import play.libs.ws.WSClient;
import play.libs.ws.WSRequest;
import play.libs.ws.WSResponse;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

@Singleton
public class IdentityProviderService {

    private final WSClient wsClient;
    private final ObjectMapper objectMapper;

    @Inject
    public IdentityProviderService(WSClient wsClient) {
        this.wsClient = wsClient;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Discover OIDC configuration from discovery URL
     */
    public CompletionStage<JsonNode> discoverOidcConfiguration(IdentityProvider provider) {
        if (provider.getDiscoveryUrl() == null) {
            return CompletableFuture.completedFuture(null);
        }

        WSRequest request = wsClient.url(provider.getDiscoveryUrl())
            .setRequestTimeout(Duration.ofSeconds(30))
            .addHeader("Accept", "application/json");

        return request.get()
            .thenApply(response -> {
                if (response.getStatus() == 200) {
                    try {
                        return Json.parse(response.getBody());
                    } catch (Exception e) {
                        play.Logger.error("Error parsing OIDC discovery response", e);
                        return null;
                    }
                } else {
                    play.Logger.error("OIDC discovery failed with status: {}", response.getStatus());
                    return null;
                }
            })
            .exceptionally(throwable -> {
                play.Logger.error("Error during OIDC discovery", throwable);
                return null;
            });
    }

    /**
     * Exchange authorization code for tokens
     */
    public CompletionStage<JsonNode> exchangeCodeForTokens(IdentityProvider provider, String code, 
                                                          String codeVerifier, String redirectUri) {
        String tokenUrl = provider.getTokenUrl();
        if (tokenUrl == null) {
            return CompletableFuture.completedFuture(null);
        }

        try {
            StringBuilder formData = new StringBuilder();
            formData.append("grant_type=authorization_code");
            formData.append("&code=").append(URLEncoder.encode(code, StandardCharsets.UTF_8));
            formData.append("&redirect_uri=").append(URLEncoder.encode(redirectUri, StandardCharsets.UTF_8));
            formData.append("&client_id=").append(URLEncoder.encode(provider.getClientId(), StandardCharsets.UTF_8));
            
            if (provider.getClientSecret() != null) {
                formData.append("&client_secret=").append(URLEncoder.encode(provider.getClientSecret(), StandardCharsets.UTF_8));
            }
            
            if (codeVerifier != null) {
                formData.append("&code_verifier=").append(URLEncoder.encode(codeVerifier, StandardCharsets.UTF_8));
            }

            WSRequest request = wsClient.url(tokenUrl)
                .setRequestTimeout(Duration.ofSeconds(30))
                .addHeader("Content-Type", "application/x-www-form-urlencoded")
                .addHeader("Accept", "application/json");

            return request.post(formData.toString())
                .thenApply(response -> {
                    if (response.getStatus() == 200) {
                        try {
                            return Json.parse(response.getBody());
                        } catch (Exception e) {
                            play.Logger.error("Error parsing token response", e);
                            return null;
                        }
                    } else {
                        play.Logger.error("Token exchange failed with status: {} - {}", 
                                        response.getStatus(), response.getBody());
                        return null;
                    }
                })
                .exceptionally(throwable -> {
                    play.Logger.error("Error during token exchange", throwable);
                    return null;
                });

        } catch (Exception e) {
            play.Logger.error("Error preparing token exchange request", e);
            return CompletableFuture.completedFuture(null);
        }
    }

    /**
     * Get user information from identity provider
     */
    public CompletionStage<JsonNode> getUserInfo(IdentityProvider provider, String accessToken) {
        String userInfoUrl = provider.getUserInfoUrl();
        if (userInfoUrl == null) {
            return CompletableFuture.completedFuture(null);
        }

        WSRequest request = wsClient.url(userInfoUrl)
            .setRequestTimeout(Duration.ofSeconds(30))
            .addHeader("Authorization", "Bearer " + accessToken)
            .addHeader("Accept", "application/json");

        return request.get()
            .thenApply(response -> {
                if (response.getStatus() == 200) {
                    try {
                        return Json.parse(response.getBody());
                    } catch (Exception e) {
                        play.Logger.error("Error parsing user info response", e);
                        return null;
                    }
                } else {
                    play.Logger.error("User info request failed with status: {} - {}", 
                                    response.getStatus(), response.getBody());
                    return null;
                }
            })
            .exceptionally(throwable -> {
                play.Logger.error("Error getting user info", throwable);
                return null;
            });
    }

    /**
     * Verify ID token signature and extract claims
     */
    public CompletionStage<JWTClaimsSet> verifyIdToken(IdentityProvider provider, String idToken) {
        try {
            SignedJWT signedJWT = SignedJWT.parse(idToken);
            
            // Get the key ID from the token header
            String keyId = signedJWT.getHeader().getKeyID();
            
            // Fetch JWKS and verify signature
            return fetchJwks(provider)
                .thenApply(jwks -> {
                    try {
                        if (jwks == null) {
                            play.Logger.error("Could not fetch JWKS for provider: {}", provider.getName());
                            return null;
                        }
                        
                        // Find the key used to sign this token
                        JWK jwk = jwks.getKeyByKeyId(keyId);
                        if (jwk == null) {
                            play.Logger.error("Key not found in JWKS for key ID: {}", keyId);
                            return null;
                        }
                        
                        // Verify signature
                        RSAKey rsaKey = (RSAKey) jwk;
                        JWSVerifier verifier = new RSASSAVerifier(rsaKey);
                        
                        if (!signedJWT.verify(verifier)) {
                            play.Logger.error("ID token signature verification failed");
                            return null;
                        }
                        
                        // Extract claims
                        JWTClaimsSet claims = signedJWT.getJWTClaimsSet();
                        
                        // Verify issuer
                        if (provider.getIssuer() != null && !provider.getIssuer().equals(claims.getIssuer())) {
                            play.Logger.error("ID token issuer mismatch. Expected: {}, Got: {}", 
                                            provider.getIssuer(), claims.getIssuer());
                            return null;
                        }
                        
                        // Verify audience
                        if (!claims.getAudience().contains(provider.getClientId())) {
                            play.Logger.error("ID token audience mismatch. Expected: {}, Got: {}", 
                                            provider.getClientId(), claims.getAudience());
                            return null;
                        }
                        
                        // Verify expiration
                        if (claims.getExpirationTime().before(new java.util.Date())) {
                            play.Logger.error("ID token has expired");
                            return null;
                        }
                        
                        return claims;
                        
                    } catch (Exception e) {
                        play.Logger.error("Error verifying ID token", e);
                        return null;
                    }
                });
                
        } catch (Exception e) {
            play.Logger.error("Error parsing ID token", e);
            return CompletableFuture.completedFuture(null);
        }
    }

    /**
     * Fetch JWKS from provider
     */
    public CompletionStage<JWKSet> fetchJwks(IdentityProvider provider) {
        String jwksUri = provider.getJwksUri();
        if (jwksUri == null) {
            return CompletableFuture.completedFuture(null);
        }

        WSRequest request = wsClient.url(jwksUri)
            .setRequestTimeout(Duration.ofSeconds(30))
            .addHeader("Accept", "application/json");

        return request.get()
            .thenApply(response -> {
                if (response.getStatus() == 200) {
                    try {
                        return JWKSet.parse(response.getBody());
                    } catch (Exception e) {
                        play.Logger.error("Error parsing JWKS response", e);
                        return null;
                    }
                } else {
                    play.Logger.error("JWKS request failed with status: {} - {}", 
                                    response.getStatus(), response.getBody());
                    return null;
                }
            })
            .exceptionally(throwable -> {
                play.Logger.error("Error fetching JWKS", throwable);
                return null;
            });
    }

    /**
     * Refresh access token using refresh token
     */
    public CompletionStage<JsonNode> refreshAccessToken(IdentityProvider provider, String refreshToken) {
        String tokenUrl = provider.getTokenUrl();
        if (tokenUrl == null || refreshToken == null) {
            return CompletableFuture.completedFuture(null);
        }

        try {
            StringBuilder formData = new StringBuilder();
            formData.append("grant_type=refresh_token");
            formData.append("&refresh_token=").append(URLEncoder.encode(refreshToken, StandardCharsets.UTF_8));
            formData.append("&client_id=").append(URLEncoder.encode(provider.getClientId(), StandardCharsets.UTF_8));
            
            if (provider.getClientSecret() != null) {
                formData.append("&client_secret=").append(URLEncoder.encode(provider.getClientSecret(), StandardCharsets.UTF_8));
            }

            WSRequest request = wsClient.url(tokenUrl)
                .setRequestTimeout(Duration.ofSeconds(30))
                .addHeader("Content-Type", "application/x-www-form-urlencoded")
                .addHeader("Accept", "application/json");

            return request.post(formData.toString())
                .thenApply(response -> {
                    if (response.getStatus() == 200) {
                        try {
                            return Json.parse(response.getBody());
                        } catch (Exception e) {
                            play.Logger.error("Error parsing refresh token response", e);
                            return null;
                        }
                    } else {
                        play.Logger.error("Token refresh failed with status: {} - {}", 
                                        response.getStatus(), response.getBody());
                        return null;
                    }
                })
                .exceptionally(throwable -> {
                    play.Logger.error("Error during token refresh", throwable);
                    return null;
                });

        } catch (Exception e) {
            play.Logger.error("Error preparing token refresh request", e);
            return CompletableFuture.completedFuture(null);
        }
    }

    /**
     * Test provider configuration by attempting OIDC discovery
     */
    public CompletionStage<Boolean> testProviderConfiguration(IdentityProvider provider) {
        if (provider.getDiscoveryUrl() != null) {
            return discoverOidcConfiguration(provider)
                .thenApply(config -> config != null);
        } else {
            // If no discovery URL, check if required endpoints are configured
            return CompletableFuture.completedFuture(
                provider.getAuthorizationUrl() != null && 
                provider.getTokenUrl() != null &&
                provider.getClientId() != null
            );
        }
    }

    /**
     * Auto-configure provider from OIDC discovery
     */
    public CompletionStage<IdentityProvider> autoConfigureFromDiscovery(IdentityProvider provider) {
        return discoverOidcConfiguration(provider)
            .thenApply(config -> {
                if (config == null) {
                    return provider;
                }

                try {
                    if (config.has("authorization_endpoint")) {
                        provider.setAuthorizationUrl(config.get("authorization_endpoint").asText());
                    }
                    if (config.has("token_endpoint")) {
                        provider.setTokenUrl(config.get("token_endpoint").asText());
                    }
                    if (config.has("userinfo_endpoint")) {
                        provider.setUserInfoUrl(config.get("userinfo_endpoint").asText());
                    }
                    if (config.has("issuer")) {
                        provider.setIssuer(config.get("issuer").asText());
                    }
                    if (config.has("jwks_uri")) {
                        provider.setJwksUri(config.get("jwks_uri").asText());
                    }
                    if (config.has("scopes_supported")) {
                        // Use default scopes that are commonly supported
                        provider.setScopes("openid email profile");
                    }

                    return provider;
                } catch (Exception e) {
                    play.Logger.error("Error auto-configuring provider from discovery", e);
                    return provider;
                }
            });
    }
}