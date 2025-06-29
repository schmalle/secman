package controllers;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.Test;
import play.Application;
import play.inject.guice.GuiceApplicationBuilder;
import play.libs.Json;
import play.mvc.Http;
import play.mvc.Result;
import play.test.WithApplication;

import static org.junit.Assert.*;
import static play.mvc.Http.Status.*;
import static play.test.Helpers.*;

/**
 * Integration tests for MCP Controller
 */
public class MCPControllerTest extends WithApplication {
    
    @Override
    protected Application provideApplication() {
        return new GuiceApplicationBuilder().build();
    }
    
    @Test
    public void testHealthEndpoint() {
        Http.RequestBuilder request = new Http.RequestBuilder()
            .method(GET)
            .uri("/mcp/health");
        
        Result result = route(app, request);
        assertEquals(OK, result.status());
        
        JsonNode json = Json.parse(contentAsString(result));
        assertEquals("healthy", json.get("status").asText());
        assertEquals("secman-mcp-server", json.get("server").asText());
        assertTrue(json.has("activeSessions"));
        assertTrue(json.has("timestamp"));
    }
    
    @Test
    public void testCapabilitiesEndpoint() {
        Http.RequestBuilder request = new Http.RequestBuilder()
            .method(GET)
            .uri("/mcp/capabilities");
        
        Result result = route(app, request);
        assertEquals(OK, result.status());
        
        JsonNode json = Json.parse(contentAsString(result));
        assertEquals("secman-mcp-server", json.get("serverName").asText());
        assertTrue(json.has("serverVersion"));
        assertTrue(json.has("protocolVersion"));
        
        JsonNode capabilities = json.get("capabilities");
        assertTrue(capabilities.get("resources").asBoolean());
        assertTrue(capabilities.get("tools").asBoolean());
        assertTrue(capabilities.get("prompts").asBoolean());
        
        JsonNode transports = json.get("transports");
        assertTrue(transports.isArray());
        assertTrue(transports.size() >= 2); // stdio and http
    }
    
    @Test
    public void testMcpEndpointUnauthenticated() {
        JsonNode mcpRequest = Json.newObject()
            .put("jsonrpc", "2.0")
            .put("id", "1")
            .put("method", "initialize");
        
        Http.RequestBuilder request = new Http.RequestBuilder()
            .method(POST)
            .uri("/mcp")
            .bodyJson(mcpRequest)
            .header("Content-Type", "application/json");
        
        Result result = route(app, request);
        assertEquals(UNAUTHORIZED, result.status());
        
        JsonNode json = Json.parse(contentAsString(result));
        assertEquals("Authentication required", json.get("error").asText());
    }
    
    @Test
    public void testMcpEndpointInvalidContentType() {
        Http.RequestBuilder request = new Http.RequestBuilder()
            .method(POST)
            .uri("/mcp")
            .bodyText("not json")
            .header("Content-Type", "text/plain");
        
        Result result = route(app, request);
        assertEquals(BAD_REQUEST, result.status());
        
        JsonNode json = Json.parse(contentAsString(result));
        assertEquals("Content-Type must be application/json", json.get("error").asText());
    }
    
    @Test
    public void testMcpEndpointInvalidJson() {
        Http.RequestBuilder request = new Http.RequestBuilder()
            .method(POST)
            .uri("/mcp")
            .bodyText("invalid json")
            .header("Content-Type", "application/json");
        
        Result result = route(app, request);
        assertEquals(BAD_REQUEST, result.status());
        
        JsonNode json = Json.parse(contentAsString(result));
        assertEquals("Invalid JSON", json.get("error").asText());
    }
    
    @Test
    public void testCorsPreflightRequest() {
        Http.RequestBuilder request = new Http.RequestBuilder()
            .method("OPTIONS")
            .uri("/mcp")
            .header("Origin", "http://localhost:3000")
            .header("Access-Control-Request-Method", "POST")
            .header("Access-Control-Request-Headers", "Content-Type, Authorization");
        
        Result result = route(app, request);
        assertEquals(OK, result.status());
        
        // Check CORS headers
        assertEquals("*", result.headers().get("Access-Control-Allow-Origin"));
        assertTrue(result.headers().get("Access-Control-Allow-Methods").contains("POST"));
        assertTrue(result.headers().get("Access-Control-Allow-Headers").contains("Content-Type"));
    }
    
    @Test
    public void testGetApiKeyUnauthenticated() {
        Http.RequestBuilder request = new Http.RequestBuilder()
            .method(GET)
            .uri("/mcp/api-key");
        
        Result result = route(app, request);
        assertEquals(UNAUTHORIZED, result.status());
        
        JsonNode json = Json.parse(contentAsString(result));
        assertEquals("Authentication required", json.get("error").asText());
    }
    
    @Test
    public void testMcpEndpointMethodNotAllowed() {
        Http.RequestBuilder request = new Http.RequestBuilder()
            .method("PUT")
            .uri("/mcp");
        
        Result result = route(app, request);
        assertEquals(BAD_REQUEST, result.status());
        
        JsonNode json = Json.parse(contentAsString(result));
        assertTrue(json.get("error").asText().contains("Method not allowed"));
    }
}