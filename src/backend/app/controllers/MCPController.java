package controllers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import mcp.AuthenticationHandler;
import mcp.MCPServer;
import mcp.MCPSession;
import models.User;
import play.libs.Json;
import play.mvc.*;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Controller for HTTP-based MCP server endpoints
 */
@Singleton
public class MCPController extends Controller {
    
    private final MCPServer mcpServer;
    private final AuthenticationHandler authHandler;
    
    @Inject
    public MCPController(MCPServer mcpServer, AuthenticationHandler authHandler) {
        this.mcpServer = mcpServer;
        this.authHandler = authHandler;
    }
    
    /**
     * Main MCP endpoint for HTTP transport
     * Supports both GET (SSE) and POST (single request) methods
     */
    public CompletionStage<Result> mcp(Http.Request request) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                // Handle CORS preflight
                if ("OPTIONS".equals(request.method())) {
                    return handleCorsPreFlight();
                }
                
                // Handle GET request (SSE endpoint)
                if ("GET".equals(request.method())) {
                    return handleSseEndpoint(request);
                }
                
                // Handle POST request (JSON-RPC message)
                if ("POST".equals(request.method())) {
                    return handleJsonRpcRequest(request);
                }
                
                return Results.badRequest(Json.newObject()
                    .put("error", "Method not allowed. Use GET for SSE or POST for JSON-RPC"));
                    
            } catch (Exception e) {
                play.Logger.error("Error handling MCP request", e);
                return Results.internalServerError(Json.newObject()
                    .put("error", "Internal server error"));
            }
        });
    }
    
    /**
     * Handle CORS preflight request
     */
    private Result handleCorsPreFlight() {
        return Results.ok()
            .withHeader("Access-Control-Allow-Origin", "*")
            .withHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
            .withHeader("Access-Control-Allow-Headers", "Content-Type, Authorization, Accept")
            .withHeader("Access-Control-Max-Age", "3600");
    }
    
    /**
     * Handle SSE endpoint for real-time communication
     */
    private Result handleSseEndpoint(Http.Request request) {
        // Authenticate the request
        Optional<User> userOpt = authenticateRequest(request);
        if (userOpt.isEmpty()) {
            return Results.unauthorized(Json.newObject()
                .put("error", "Authentication required"))
                .withHeader("Access-Control-Allow-Origin", "*");
        }
        
        User user = userOpt.get();
        String sessionId = generateSessionId();
        
        // Create authenticated session
        MCPSession session = new MCPSession(sessionId, Json.newObject()
            .put("transport", "sse")
            .put("clientIp", request.remoteAddress()));
        session.setAuthenticatedUser(user);
        
        // Return SSE response
        String sseData = "data: {\\\"event\\\": \\\"connected\\\", \\\"sessionId\\\": \\\"" + sessionId + "\\\"}\\n\\n";
        return Results.ok(sseData)
            .withHeader("Content-Type", "text/event-stream")
            .withHeader("Cache-Control", "no-cache")
            .withHeader("Connection", "keep-alive")
            .withHeader("Access-Control-Allow-Origin", "*")
            .withHeader("Access-Control-Allow-Headers", "Content-Type, Authorization");
    }
    
    /**
     * Handle JSON-RPC POST request
     */
    private Result handleJsonRpcRequest(Http.Request request) {
        // Check Content-Type
        Optional<String> contentType = request.contentType();
        if (contentType.isEmpty() || !contentType.get().contains("application/json")) {
            return Results.badRequest(Json.newObject()
                .put("error", "Content-Type must be application/json"))
                .withHeader("Access-Control-Allow-Origin", "*");
        }
        
        // Authenticate the request
        Optional<User> userOpt = authenticateRequest(request);
        if (userOpt.isEmpty()) {
            return Results.unauthorized(Json.newObject()
                .put("error", "Authentication required"))
                .withHeader("Access-Control-Allow-Origin", "*");
        }
        
        User user = userOpt.get();
        String sessionId = generateSessionId();
        
        // Parse JSON-RPC message
        JsonNode requestBody = request.body().asJson();
        if (requestBody == null) {
            return Results.badRequest(Json.newObject()
                .put("error", "Invalid JSON"))
                .withHeader("Access-Control-Allow-Origin", "*");
        }
        
        try {
            // Create authenticated session
            MCPSession session = new MCPSession(sessionId, Json.newObject()
                .put("transport", "http")
                .put("clientIp", request.remoteAddress()));
            session.setAuthenticatedUser(user);
            session.setInitialized(true); // HTTP sessions are immediately initialized
            
            // Process the MCP message
            JsonNode response = mcpServer.processMessage(requestBody, sessionId);
            
            if (response != null) {
                return Results.ok(response)
                    .withHeader("Content-Type", "application/json")
                    .withHeader("Access-Control-Allow-Origin", "*");
            } else {
                // No response expected (notification)
                return Results.status(202) // Accepted
                    .withHeader("Access-Control-Allow-Origin", "*");
            }
            
        } catch (Exception e) {
            play.Logger.error("Error processing MCP JSON-RPC request", e);
            
            ObjectNode errorResponse = Json.newObject();
            errorResponse.put("jsonrpc", "2.0");
            if (requestBody.has("id")) {
                errorResponse.set("id", requestBody.get("id"));
            } else {
                errorResponse.putNull("id");
            }
            
            ObjectNode error = Json.newObject();
            error.put("code", -32603);
            error.put("message", "Internal error");
            error.put("data", e.getMessage());
            errorResponse.set("error", error);
                    
            return Results.internalServerError(errorResponse)
                .withHeader("Content-Type", "application/json")
                .withHeader("Access-Control-Allow-Origin", "*");
        }
    }
    
    /**
     * Authenticate incoming request
     */
    private Optional<User> authenticateRequest(Http.Request request) {
        // Try session-based authentication first
        Optional<String> sessionUsername = request.session().get("username");
        if (sessionUsername.isPresent()) {
            Optional<User> user = authHandler.authenticateFromSession(sessionUsername.get());
            if (user.isPresent()) {
                return user;
            }
        }
        
        // Try Authorization header (Bearer token / API key)
        Optional<String> authHeader = request.getHeaders().get("Authorization");
        if (authHeader.isPresent()) {
            String auth = authHeader.get();
            if (auth.startsWith("Bearer ")) {
                String apiKey = auth.substring(7); // Remove "Bearer "
                return authHandler.authenticateByApiKey(apiKey);
            }
        }
        
        // Try API key from query parameter (less secure, for development)
        String apiKeyParam = request.getQueryString("api_key");
        if (apiKeyParam != null) {
            return authHandler.authenticateByApiKey(apiKeyParam);
        }
        
        return Optional.empty();
    }
    
    /**
     * Generate unique session ID
     */
    private String generateSessionId() {
        return "mcp-session-" + UUID.randomUUID().toString();
    }
    
    /**
     * Health check endpoint for MCP server
     */
    public Result health() {
        return Results.ok(Json.newObject()
            .put("status", "healthy")
            .put("server", "secman-mcp-server")
            .put("version", "1.0.0")
            .put("activeSessions", mcpServer.getActiveSessionsCount())
            .put("timestamp", System.currentTimeMillis()))
            .withHeader("Access-Control-Allow-Origin", "*");
    }
    
    /**
     * Get MCP server capabilities
     */
    public Result capabilities() {
        ObjectNode capabilities = Json.newObject();
        capabilities.put("resources", true);
        capabilities.put("tools", true);
        capabilities.put("prompts", true);
        capabilities.put("logging", true);
        
        ArrayNode transports = Json.newArray();
        transports.add("stdio");
        transports.add("http");
        
        ObjectNode response = Json.newObject();
        response.put("serverName", "secman-mcp-server");
        response.put("serverVersion", "1.0.0");
        response.put("protocolVersion", "2024-11-05");
        response.set("capabilities", capabilities);
        response.set("transports", transports);
        
        return Results.ok(response)
            .withHeader("Access-Control-Allow-Origin", "*");
    }
    
    /**
     * Get API key for authenticated user
     */
    public Result getApiKey(Http.Request request) {
        Optional<String> sessionUsername = request.session().get("username");
        if (sessionUsername.isEmpty()) {
            return Results.unauthorized(Json.newObject()
                .put("error", "Authentication required"));
        }
        
        Optional<User> userOpt = authHandler.authenticateFromSession(sessionUsername.get());
        if (userOpt.isEmpty()) {
            return Results.unauthorized(Json.newObject()
                .put("error", "Invalid session"));
        }
        
        User user = userOpt.get();
        String instructions = authHandler.getApiKeyInstructions(user);
        
        return Results.ok(Json.newObject()
            .put("username", user.getUsername())
            .put("instructions", instructions));
    }
}