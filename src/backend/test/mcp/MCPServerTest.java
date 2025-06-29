package mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import models.User;
import org.junit.Before;
import org.junit.Test;
import play.Application;
import play.inject.guice.GuiceApplicationBuilder;
import play.libs.Json;
import play.test.WithApplication;

import static org.junit.Assert.*;

/**
 * Test cases for MCP Server functionality
 */
public class MCPServerTest extends WithApplication {
    
    private MCPServer mcpServer;
    private ObjectMapper objectMapper;
    
    @Override
    protected Application provideApplication() {
        return new GuiceApplicationBuilder().build();
    }
    
    @Before
    public void setUp() {
        mcpServer = app.injector().instanceOf(MCPServer.class);
        objectMapper = new ObjectMapper();
    }
    
    @Test
    public void testInitializeRequest() {
        // Create initialize request
        JsonNode initRequest = Json.newObject()
            .put("jsonrpc", "2.0")
            .put("id", "1")
            .put("method", "initialize")
            .set("params", Json.newObject()
                .put("protocolVersion", "2024-11-05")
                .set("clientInfo", Json.newObject()
                    .put("name", "test-client")
                    .put("version", "1.0.0")));
        
        // Process the request
        JsonNode response = mcpServer.processMessage(initRequest, "test-session");
        
        // Verify response
        assertNotNull(response);
        assertEquals("2.0", response.get("jsonrpc").asText());
        assertEquals("1", response.get("id").asText());
        assertTrue(response.has("result"));
        
        JsonNode result = response.get("result");
        assertTrue(result.has("serverInfo"));
        assertTrue(result.has("protocolVersion"));
        assertTrue(result.has("capabilities"));
        
        // Verify capabilities
        JsonNode capabilities = result.get("capabilities");
        assertTrue(capabilities.get("resources").asBoolean());
        assertTrue(capabilities.get("tools").asBoolean());
        assertTrue(capabilities.get("prompts").asBoolean());
    }
    
    @Test
    public void testPingRequest() {
        JsonNode pingRequest = Json.newObject()
            .put("jsonrpc", "2.0")
            .put("id", "2")
            .put("method", "ping");
        
        JsonNode response = mcpServer.processMessage(pingRequest, "test-session");
        
        assertNotNull(response);
        assertEquals("2.0", response.get("jsonrpc").asText());
        assertEquals("2", response.get("id").asText());
        assertTrue(response.has("result"));
    }
    
    @Test
    public void testInvalidMethod() {
        JsonNode invalidRequest = Json.newObject()
            .put("jsonrpc", "2.0")
            .put("id", "3")
            .put("method", "nonexistent_method");
        
        JsonNode response = mcpServer.processMessage(invalidRequest, "test-session");
        
        assertNotNull(response);
        assertEquals("2.0", response.get("jsonrpc").asText());
        assertEquals("3", response.get("id").asText());
        assertTrue(response.has("error"));
        
        JsonNode error = response.get("error");
        assertEquals(-32601, error.get("code").asInt());
        assertEquals("Method not found", error.get("message").asText());
    }
    
    @Test
    public void testInvalidJsonRpc() {
        JsonNode invalidRequest = Json.newObject()
            .put("jsonrpc", "1.0") // Wrong version
            .put("id", "4")
            .put("method", "ping");
        
        JsonNode response = mcpServer.processMessage(invalidRequest, "test-session");
        
        assertNotNull(response);
        assertTrue(response.has("error"));
        
        JsonNode error = response.get("error");
        assertEquals(-32600, error.get("code").asInt());
    }
    
    @Test
    public void testNotificationHandling() {
        JsonNode notification = Json.newObject()
            .put("jsonrpc", "2.0")
            .put("method", "notifications/initialized");
        
        // Notifications should not return a response
        JsonNode response = mcpServer.processMessage(notification, "test-session");
        assertNull(response);
    }
    
    @Test
    public void testBatchRequest() {
        JsonNode batchRequest = Json.newArray()
            .add(Json.newObject()
                .put("jsonrpc", "2.0")
                .put("id", "5")
                .put("method", "ping"))
            .add(Json.newObject()
                .put("jsonrpc", "2.0")
                .put("id", "6")
                .put("method", "ping"));
        
        JsonNode response = mcpServer.processMessage(batchRequest, "test-session");
        
        assertNotNull(response);
        assertTrue(response.isArray());
        assertEquals(2, response.size());
        
        // Check both responses
        for (JsonNode resp : response) {
            assertEquals("2.0", resp.get("jsonrpc").asText());
            assertTrue(resp.has("result"));
        }
    }
    
    @Test
    public void testSessionManagement() {
        String sessionId = "test-session-mgmt";
        
        // Initialize session
        JsonNode initRequest = Json.newObject()
            .put("jsonrpc", "2.0")
            .put("id", "1")
            .put("method", "initialize")
            .set("params", Json.newObject());
        
        mcpServer.processMessage(initRequest, sessionId);
        
        // Verify session was created
        assertEquals(1, mcpServer.getActiveSessionsCount());
        
        // Clean up sessions
        mcpServer.cleanupSessions();
    }
    
    @Test
    public void testGenerateRequestId() {
        String id1 = mcpServer.generateRequestId();
        String id2 = mcpServer.generateRequestId();
        
        assertNotNull(id1);
        assertNotNull(id2);
        assertNotEquals(id1, id2);
    }
}