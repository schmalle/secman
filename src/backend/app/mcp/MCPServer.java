package mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import play.Logger;
import play.libs.Json;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.io.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Main MCP (Model Context Protocol) Server implementation for Secman
 * Implements JSON-RPC 2.0 protocol for communication with MCP clients
 */
@Singleton
public class MCPServer {
    
    private static final Logger.ALogger logger = Logger.of(MCPServer.class);
    private static final String PROTOCOL_VERSION = "2024-11-05";
    private static final String SERVER_NAME = "secman-mcp-server";
    private static final String SERVER_VERSION = "1.0.0";
    
    private final ObjectMapper objectMapper;
    private final ResourceProvider resourceProvider;
    private final ToolProvider toolProvider;
    private final PromptProvider promptProvider;
    private final AuthenticationHandler authHandler;
    
    // Session management
    private final Map<String, MCPSession> sessions = new ConcurrentHashMap<>();
    private final AtomicLong requestIdCounter = new AtomicLong(0);
    
    @Inject
    public MCPServer(ResourceProvider resourceProvider, 
                     ToolProvider toolProvider,
                     PromptProvider promptProvider,
                     AuthenticationHandler authHandler) {
        this.objectMapper = new ObjectMapper();
        this.resourceProvider = resourceProvider;
        this.toolProvider = toolProvider;
        this.promptProvider = promptProvider;
        this.authHandler = authHandler;
    }
    
    /**
     * Start STDIO-based MCP server for Claude Desktop integration
     */
    public void startStdioServer() {
        logger.info("Starting MCP server in STDIO mode");
        
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
             PrintWriter writer = new PrintWriter(System.out, true)) {
            
            String line;
            while ((line = reader.readLine()) != null) {
                try {
                    JsonNode request = objectMapper.readTree(line);
                    JsonNode response = processMessage(request, "stdio");
                    
                    if (response != null) {
                        writer.println(objectMapper.writeValueAsString(response));
                    }
                } catch (Exception e) {
                    logger.error("Error processing MCP message: " + line, e);
                    JsonNode errorResponse = createErrorResponse(null, -32700, "Parse error", e.getMessage());
                    writer.println(objectMapper.writeValueAsString(errorResponse));
                }
            }
        } catch (IOException e) {
            logger.error("STDIO server error", e);
        }
    }
    
    /**
     * Process incoming JSON-RPC message
     */
    public JsonNode processMessage(JsonNode message, String sessionId) {
        try {
            // Handle JSON-RPC batch
            if (message.isArray()) {
                ArrayNode responses = objectMapper.createArrayNode();
                for (JsonNode msg : message) {
                    JsonNode response = processSingleMessage(msg, sessionId);
                    if (response != null) {
                        responses.add(response);
                    }
                }
                return responses.size() > 0 ? responses : null;
            } else {
                return processSingleMessage(message, sessionId);
            }
        } catch (Exception e) {
            logger.error("Error processing MCP message", e);
            return createErrorResponse(null, -32603, "Internal error", e.getMessage());
        }
    }
    
    /**
     * Process a single JSON-RPC message
     */
    private JsonNode processSingleMessage(JsonNode message, String sessionId) {
        if (!message.has("jsonrpc") || !"2.0".equals(message.get("jsonrpc").asText())) {
            return createErrorResponse(null, -32600, "Invalid Request", "Missing or invalid jsonrpc field");
        }
        
        if (!message.has("method")) {
            return createErrorResponse(null, -32600, "Invalid Request", "Missing method field");
        }
        
        String method = message.get("method").asText();
        JsonNode params = message.get("params");
        JsonNode id = message.get("id");
        
        // Handle notifications (no response expected)
        if (id == null) {
            handleNotification(method, params, sessionId);
            return null;
        }
        
        // Handle requests
        try {
            JsonNode result = handleRequest(method, params, sessionId);
            return createSuccessResponse(id, result);
        } catch (MCPException e) {
            return createErrorResponse(id, e.getCode(), e.getMessage(), e.getData());
        } catch (Exception e) {
            logger.error("Unexpected error handling request: " + method, e);
            return createErrorResponse(id, -32603, "Internal error", e.getMessage());
        }
    }
    
    /**
     * Handle MCP requests
     */
    private JsonNode handleRequest(String method, JsonNode params, String sessionId) throws MCPException {
        switch (method) {
            case "initialize":
                return handleInitialize(params, sessionId);
            case "ping":
                return handlePing();
            case "resources/list":
                return resourceProvider.listResources(getSession(sessionId));
            case "resources/read":
                return resourceProvider.readResource(params, getSession(sessionId));
            case "tools/list":
                return toolProvider.listTools(getSession(sessionId));
            case "tools/call":
                return toolProvider.callTool(params, getSession(sessionId));
            case "prompts/list":
                return promptProvider.listPrompts(getSession(sessionId));
            case "prompts/get":
                return promptProvider.getPrompt(params, getSession(sessionId));
            case "completion/complete":
                return handleCompletion(params, sessionId);
            case "logging/setLevel":
                return handleSetLogLevel(params);
            default:
                throw new MCPException(-32601, "Method not found", method);
        }
    }
    
    /**
     * Handle MCP notifications
     */
    private void handleNotification(String method, JsonNode params, String sessionId) {
        switch (method) {
            case "notifications/initialized":
                handleInitialized(sessionId);
                break;
            case "notifications/cancelled":
                handleCancelled(params, sessionId);
                break;
            default:
                logger.warn("Unknown notification method: " + method);
        }
    }
    
    /**
     * Handle initialize request
     */
    private JsonNode handleInitialize(JsonNode params, String sessionId) {
        ObjectNode result = objectMapper.createObjectNode();
        
        // Server information
        ObjectNode serverInfo = objectMapper.createObjectNode();
        serverInfo.put("name", SERVER_NAME);
        serverInfo.put("version", SERVER_VERSION);
        result.set("serverInfo", serverInfo);
        
        // Protocol version
        result.put("protocolVersion", PROTOCOL_VERSION);
        
        // Capabilities
        ObjectNode capabilities = objectMapper.createObjectNode();
        capabilities.put("resources", true);
        capabilities.put("tools", true);
        capabilities.put("prompts", true);
        capabilities.put("logging", true);
        result.set("capabilities", capabilities);
        
        // Create session
        MCPSession session = new MCPSession(sessionId, params);
        sessions.put(sessionId, session);
        
        return result;
    }
    
    /**
     * Handle ping request
     */
    private JsonNode handlePing() {
        return objectMapper.createObjectNode();
    }
    
    /**
     * Handle completion request
     */
    private JsonNode handleCompletion(JsonNode params, String sessionId) {
        ObjectNode result = objectMapper.createObjectNode();
        ArrayNode completions = objectMapper.createArrayNode();
        result.set("completions", completions);
        return result;
    }
    
    /**
     * Handle set log level request
     */
    private JsonNode handleSetLogLevel(JsonNode params) {
        if (params.has("level")) {
            String level = params.get("level").asText();
            logger.info("Setting log level to: " + level);
        }
        return objectMapper.createObjectNode();
    }
    
    /**
     * Handle initialized notification
     */
    private void handleInitialized(String sessionId) {
        MCPSession session = sessions.get(sessionId);
        if (session != null) {
            session.setInitialized(true);
            logger.info("MCP session initialized: " + sessionId);
        }
    }
    
    /**
     * Handle cancelled notification
     */
    private void handleCancelled(JsonNode params, String sessionId) {
        if (params.has("requestId")) {
            String requestId = params.get("requestId").asText();
            logger.info("Request cancelled: " + requestId + " for session: " + sessionId);
        }
    }
    
    /**
     * Get or create session
     */
    private MCPSession getSession(String sessionId) {
        return sessions.computeIfAbsent(sessionId, id -> new MCPSession(id, null));
    }
    
    /**
     * Create success response
     */
    private JsonNode createSuccessResponse(JsonNode id, JsonNode result) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.set("id", id);
        response.set("result", result);
        return response;
    }
    
    /**
     * Create error response
     */
    private JsonNode createErrorResponse(JsonNode id, int code, String message, Object data) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("jsonrpc", "2.0");
        if (id != null) {
            response.set("id", id);
        }
        
        ObjectNode error = objectMapper.createObjectNode();
        error.put("code", code);
        error.put("message", message);
        if (data != null) {
            error.set("data", objectMapper.valueToTree(data));
        }
        response.set("error", error);
        
        return response;
    }
    
    /**
     * Generate unique request ID
     */
    public String generateRequestId() {
        return String.valueOf(requestIdCounter.incrementAndGet());
    }
    
    /**
     * Get active sessions count
     */
    public int getActiveSessionsCount() {
        return sessions.size();
    }
    
    /**
     * Clean up expired sessions
     */
    public void cleanupSessions() {
        long now = System.currentTimeMillis();
        sessions.entrySet().removeIf(entry -> {
            MCPSession session = entry.getValue();
            return now - session.getCreatedAt() > 3600000; // 1 hour timeout
        });
    }
}