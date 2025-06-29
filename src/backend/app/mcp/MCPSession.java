package mcp;

import com.fasterxml.jackson.databind.JsonNode;
import models.User;

/**
 * Represents an MCP client session
 */
public class MCPSession {
    private final String sessionId;
    private final long createdAt;
    private final JsonNode clientInfo;
    private boolean initialized;
    private User authenticatedUser;
    
    public MCPSession(String sessionId, JsonNode clientInfo) {
        this.sessionId = sessionId;
        this.clientInfo = clientInfo;
        this.createdAt = System.currentTimeMillis();
        this.initialized = false;
    }
    
    public String getSessionId() {
        return sessionId;
    }
    
    public long getCreatedAt() {
        return createdAt;
    }
    
    public JsonNode getClientInfo() {
        return clientInfo;
    }
    
    public boolean isInitialized() {
        return initialized;
    }
    
    public void setInitialized(boolean initialized) {
        this.initialized = initialized;
    }
    
    public User getAuthenticatedUser() {
        return authenticatedUser;
    }
    
    public void setAuthenticatedUser(User user) {
        this.authenticatedUser = user;
    }
    
    public boolean isAuthenticated() {
        return authenticatedUser != null;
    }
    
    public boolean isAdmin() {
        return authenticatedUser != null && authenticatedUser.isAdmin();
    }
}