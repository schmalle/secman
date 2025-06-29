package mcp;

import com.fasterxml.jackson.databind.JsonNode;
import models.User;
import org.junit.Before;
import org.junit.Test;
import play.Application;
import play.inject.guice.GuiceApplicationBuilder;
import play.libs.Json;
import play.test.WithApplication;

import static org.junit.Assert.*;

/**
 * Test cases for MCP ResourceProvider
 */
public class ResourceProviderTest extends WithApplication {
    
    private ResourceProvider resourceProvider;
    
    @Override
    protected Application provideApplication() {
        return new GuiceApplicationBuilder().build();
    }
    
    @Before
    public void setUp() {
        resourceProvider = app.injector().instanceOf(ResourceProvider.class);
    }
    
    @Test
    public void testListResourcesUnauthenticated() {
        MCPSession session = new MCPSession("test", null);
        
        try {
            resourceProvider.listResources(session);
            fail("Should throw MCPException for unauthenticated session");
        } catch (MCPException e) {
            assertEquals(MCPException.UNAUTHORIZED, e.getCode());
        }
    }
    
    @Test
    public void testListResourcesAuthenticated() throws MCPException {
        MCPSession session = new MCPSession("test", null);
        
        // Create mock user
        User user = new User();
        user.setUsername("testuser");
        user.setEmail("test@example.com");
        session.setAuthenticatedUser(user);
        
        JsonNode result = resourceProvider.listResources(session);
        
        assertNotNull(result);
        assertTrue(result.has("resources"));
        
        JsonNode resources = result.get("resources");
        assertTrue(resources.isArray());
        assertTrue(resources.size() > 0);
        
        // Check for expected resources
        boolean hasRequirements = false;
        boolean hasAssets = false;
        boolean hasRisks = false;
        
        for (JsonNode resource : resources) {
            String uri = resource.get("uri").asText();
            if ("secman://requirements".equals(uri)) hasRequirements = true;
            if ("secman://assets".equals(uri)) hasAssets = true;
            if ("secman://risks".equals(uri)) hasRisks = true;
        }
        
        assertTrue("Should have requirements resource", hasRequirements);
        assertTrue("Should have assets resource", hasAssets);
        assertTrue("Should have risks resource", hasRisks);
    }
    
    @Test
    public void testListResourcesAdmin() throws MCPException {
        MCPSession session = new MCPSession("test", null);
        
        // Create mock admin user
        User adminUser = new User();
        adminUser.setUsername("admin");
        adminUser.setEmail("admin@example.com");
        adminUser.getRoles().add(User.Role.ADMIN);
        session.setAuthenticatedUser(adminUser);
        
        JsonNode result = resourceProvider.listResources(session);
        
        assertNotNull(result);
        JsonNode resources = result.get("resources");
        
        // Admin should see users resource
        boolean hasUsers = false;
        for (JsonNode resource : resources) {
            String uri = resource.get("uri").asText();
            if ("secman://users".equals(uri)) hasUsers = true;
        }
        
        assertTrue("Admin should have access to users resource", hasUsers);
    }
    
    @Test
    public void testReadResourceUnauthenticated() {
        MCPSession session = new MCPSession("test", null);
        JsonNode params = Json.newObject().put("uri", "secman://requirements");
        
        try {
            resourceProvider.readResource(params, session);
            fail("Should throw MCPException for unauthenticated session");
        } catch (MCPException e) {
            assertEquals(MCPException.UNAUTHORIZED, e.getCode());
        }
    }
    
    @Test
    public void testReadResourceMissingUri() {
        MCPSession session = new MCPSession("test", null);
        User user = new User();
        user.setUsername("testuser");
        session.setAuthenticatedUser(user);
        
        JsonNode params = Json.newObject(); // Missing uri
        
        try {
            resourceProvider.readResource(params, session);
            fail("Should throw MCPException for missing uri");
        } catch (MCPException e) {
            assertEquals(MCPException.INVALID_PARAMS, e.getCode());
        }
    }
    
    @Test
    public void testReadResourceInvalidUri() {
        MCPSession session = new MCPSession("test", null);
        User user = new User();
        user.setUsername("testuser");
        session.setAuthenticatedUser(user);
        
        JsonNode params = Json.newObject().put("uri", "invalid://uri");
        
        try {
            JsonNode result = resourceProvider.readResource(params, session);
            // Should handle gracefully or throw appropriate exception
            // The exact behavior depends on implementation
        } catch (MCPException e) {
            // Expected for invalid URIs
            assertTrue(e.getCode() == MCPException.RESOURCE_NOT_FOUND || 
                      e.getCode() == MCPException.INTERNAL_ERROR);
        }
    }
}