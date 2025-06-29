package mcp;

import models.User;
import play.db.jpa.JPAApi;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.persistence.TypedQuery;
import java.util.Optional;

/**
 * Handles authentication for MCP sessions
 */
@Singleton
public class AuthenticationHandler {
    
    private final JPAApi jpaApi;
    
    @Inject
    public AuthenticationHandler(JPAApi jpaApi) {
        this.jpaApi = jpaApi;
    }
    
    /**
     * Authenticate user by username for MCP session
     * In a production environment, this should use proper authentication mechanisms
     */
    public Optional<User> authenticateUser(String username, String password) {
        return jpaApi.withTransaction(em -> {
            try {
                TypedQuery<User> query = em.createQuery(
                    "SELECT u FROM User u WHERE u.username = :username", User.class);
                query.setParameter("username", username);
                
                Optional<User> userOpt = query.getResultStream().findFirst();
                if (userOpt.isPresent()) {
                    User user = userOpt.get();
                    // In production, verify password hash here
                    // For now, we'll use a simple check (THIS IS NOT SECURE FOR PRODUCTION)
                    if (verifyPassword(password, user.getPasswordHash())) {
                        return Optional.of(user);
                    }
                }
                return Optional.empty();
            } catch (Exception e) {
                play.Logger.error("Error authenticating user: " + username, e);
                return Optional.empty();
            }
        });
    }
    
    /**
     * Authenticate user by API key (for programmatic access)
     */
    public Optional<User> authenticateByApiKey(String apiKey) {
        // For now, we'll implement a simple API key format: username:hashedkey
        // In production, this should use proper API key management
        if (apiKey == null || !apiKey.contains(":")) {
            return Optional.empty();
        }
        
        String[] parts = apiKey.split(":", 2);
        if (parts.length != 2) {
            return Optional.empty();
        }
        
        String username = parts[0];
        String key = parts[1];
        
        return jpaApi.withTransaction(em -> {
            try {
                TypedQuery<User> query = em.createQuery(
                    "SELECT u FROM User u WHERE u.username = :username", User.class);
                query.setParameter("username", username);
                
                Optional<User> userOpt = query.getResultStream().findFirst();
                if (userOpt.isPresent()) {
                    User user = userOpt.get();
                    // Simple API key verification (enhance for production)
                    String expectedKey = generateApiKeyForUser(user);
                    if (expectedKey.equals(key)) {
                        return Optional.of(user);
                    }
                }
                return Optional.empty();
            } catch (Exception e) {
                play.Logger.error("Error authenticating API key for user: " + username, e);
                return Optional.empty();
            }
        });
    }
    
    /**
     * Authenticate from session (for HTTP transport)
     */
    public Optional<User> authenticateFromSession(String sessionUsername) {
        if (sessionUsername == null || sessionUsername.trim().isEmpty()) {
            return Optional.empty();
        }
        
        return jpaApi.withTransaction(em -> {
            try {
                TypedQuery<User> query = em.createQuery(
                    "SELECT u FROM User u WHERE u.username = :username", User.class);
                query.setParameter("username", sessionUsername);
                
                return query.getResultStream().findFirst();
            } catch (Exception e) {
                play.Logger.error("Error getting user from session: " + sessionUsername, e);
                return Optional.empty();
            }
        });
    }
    
    /**
     * Verify password against stored hash
     * This is a simplified implementation - use proper password hashing in production
     */
    private boolean verifyPassword(String password, String storedHash) {
        if (password == null || storedHash == null) {
            return false;
        }
        
        try {
            // Use BCrypt for password verification
            return org.mindrot.jbcrypt.BCrypt.checkpw(password, storedHash);
        } catch (Exception e) {
            play.Logger.error("Error verifying password", e);
            return false;
        }
    }
    
    /**
     * Generate API key for user (simplified implementation)
     * In production, use proper API key generation and storage
     */
    private String generateApiKeyForUser(User user) {
        // This is a simplified implementation
        // In production, generate and store proper API keys
        String data = user.getUsername() + ":" + user.getId() + ":secman-mcp";
        return Integer.toHexString(data.hashCode());
    }
    
    /**
     * Check if user has admin privileges
     */
    public boolean isAdmin(User user) {
        return user != null && user.isAdmin();
    }
    
    /**
     * Generate API key instructions for user
     */
    public String getApiKeyInstructions(User user) {
        String apiKey = user.getUsername() + ":" + generateApiKeyForUser(user);
        return String.format(
            "To authenticate with the Secman MCP server, use the following API key:\\n" +
            "API Key: %s\\n\\n" +
            "For STDIO transport (Claude Desktop), set environment variable:\\n" +
            "SECMAN_API_KEY=%s\\n\\n" +
            "For HTTP transport, include in Authorization header:\\n" +
            "Authorization: Bearer %s",
            apiKey, apiKey, apiKey
        );
    }
}