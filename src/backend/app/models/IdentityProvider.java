package models;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import play.libs.Json;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@Entity
@Table(name = "identity_providers")
public class IdentityProvider {

    public enum ProviderType {
        OIDC, SAML
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ProviderType type = ProviderType.OIDC;

    @Column(name = "client_id")
    private String clientId;

    @JsonIgnore
    @Column(name = "client_secret", length = 1024)
    private String clientSecret;

    @Column(name = "discovery_url", length = 500)
    private String discoveryUrl;

    @Column(name = "authorization_url", length = 500)
    private String authorizationUrl;

    @Column(name = "token_url", length = 500)
    private String tokenUrl;

    @Column(name = "user_info_url", length = 500)
    private String userInfoUrl;

    @Column(length = 500)
    private String issuer;

    @Column(name = "jwks_uri", length = 500)
    private String jwksUri;

    @Column(length = 255)
    private String scopes = "openid email profile";

    @Column(nullable = false)
    private Boolean enabled = true;

    @Column(name = "auto_provision", nullable = false)
    private Boolean autoProvision = false;

    @Column(name = "role_mapping", columnDefinition = "JSON")
    private String roleMappingJson;

    @Column(name = "claim_mappings", columnDefinition = "JSON")
    private String claimMappingsJson;

    @Column(name = "button_text", length = 100)
    private String buttonText = "Sign in with Provider";

    @Column(name = "button_color", length = 7)
    private String buttonColor = "#007bff";

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    // Constructors
    public IdentityProvider() {}

    public IdentityProvider(String name, ProviderType type) {
        this.name = name;
        this.type = type;
    }

    // Lifecycle methods
    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
        updatedAt = Instant.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }

    // Getters and Setters
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public ProviderType getType() {
        return type;
    }

    public void setType(ProviderType type) {
        this.type = type;
    }

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    public String getClientSecret() {
        return clientSecret;
    }

    public void setClientSecret(String clientSecret) {
        this.clientSecret = clientSecret;
    }

    public String getDiscoveryUrl() {
        return discoveryUrl;
    }

    public void setDiscoveryUrl(String discoveryUrl) {
        this.discoveryUrl = discoveryUrl;
    }

    public String getAuthorizationUrl() {
        return authorizationUrl;
    }

    public void setAuthorizationUrl(String authorizationUrl) {
        this.authorizationUrl = authorizationUrl;
    }

    public String getTokenUrl() {
        return tokenUrl;
    }

    public void setTokenUrl(String tokenUrl) {
        this.tokenUrl = tokenUrl;
    }

    public String getUserInfoUrl() {
        return userInfoUrl;
    }

    public void setUserInfoUrl(String userInfoUrl) {
        this.userInfoUrl = userInfoUrl;
    }

    public String getIssuer() {
        return issuer;
    }

    public void setIssuer(String issuer) {
        this.issuer = issuer;
    }

    public String getJwksUri() {
        return jwksUri;
    }

    public void setJwksUri(String jwksUri) {
        this.jwksUri = jwksUri;
    }

    public String getScopes() {
        return scopes;
    }

    public void setScopes(String scopes) {
        this.scopes = scopes;
    }

    public Boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    public Boolean getAutoProvision() {
        return autoProvision;
    }

    public void setAutoProvision(Boolean autoProvision) {
        this.autoProvision = autoProvision;
    }

    public Map<String, String> getRoleMapping() {
        if (roleMappingJson == null || roleMappingJson.isEmpty()) {
            return new HashMap<>();
        }
        try {
            JsonNode jsonNode = Json.parse(roleMappingJson);
            Map<String, String> mapping = new HashMap<>();
            jsonNode.fields().forEachRemaining(entry -> {
                mapping.put(entry.getKey(), entry.getValue().asText());
            });
            return mapping;
        } catch (Exception e) {
            return new HashMap<>();
        }
    }

    public void setRoleMapping(Map<String, String> roleMapping) {
        if (roleMapping == null || roleMapping.isEmpty()) {
            this.roleMappingJson = null;
        } else {
            this.roleMappingJson = Json.toJson(roleMapping).toString();
        }
    }

    public Map<String, String> getClaimMappings() {
        if (claimMappingsJson == null || claimMappingsJson.isEmpty()) {
            return new HashMap<>();
        }
        try {
            JsonNode jsonNode = Json.parse(claimMappingsJson);
            Map<String, String> mapping = new HashMap<>();
            jsonNode.fields().forEachRemaining(entry -> {
                mapping.put(entry.getKey(), entry.getValue().asText());
            });
            return mapping;
        } catch (Exception e) {
            return new HashMap<>();
        }
    }

    public void setClaimMappings(Map<String, String> claimMappings) {
        if (claimMappings == null || claimMappings.isEmpty()) {
            this.claimMappingsJson = null;
        } else {
            this.claimMappingsJson = Json.toJson(claimMappings).toString();
        }
    }

    public String getButtonText() {
        return buttonText;
    }

    public void setButtonText(String buttonText) {
        this.buttonText = buttonText;
    }

    public String getButtonColor() {
        return buttonColor;
    }

    public void setButtonColor(String buttonColor) {
        this.buttonColor = buttonColor;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    // Helper methods
    public boolean isOidc() {
        return ProviderType.OIDC.equals(type);
    }

    public boolean isSaml() {
        return ProviderType.SAML.equals(type);
    }
}