package mcp;

import play.Application;
import play.Environment;
import play.Mode;
import play.inject.guice.GuiceApplicationBuilder;
import play.Logger;

/**
 * Standalone launcher for MCP server in STDIO mode
 * Used for Claude Desktop integration
 */
public class MCPServerLauncher {
    
    private static final Logger.ALogger logger = Logger.of(MCPServerLauncher.class);
    
    public static void main(String[] args) {
        try {
            logger.info("Starting Secman MCP Server in STDIO mode...");
            
            // Check for API key authentication
            String apiKey = System.getenv("SECMAN_API_KEY");
            if (apiKey == null || apiKey.trim().isEmpty()) {
                logger.warn("No SECMAN_API_KEY environment variable found. Authentication may fail.");
            }
            
            // Create Play application in test mode to avoid starting HTTP server
            Application app = new GuiceApplicationBuilder()
                .in(Environment.simple())
                .in(Mode.TEST)
                .build();
            
            // Application is already started in TEST mode
            
            try {
                // Get MCP server instance from DI
                MCPServer mcpServer = app.injector().instanceOf(MCPServer.class);
                
                logger.info("MCP Server initialized successfully");
                logger.info("Ready for STDIO communication...");
                
                // Start STDIO server (this blocks until terminated)
                mcpServer.startStdioServer();
                
            } finally {
                // Clean shutdown - Play application will clean up automatically
            }
            
        } catch (Exception e) {
            logger.error("Failed to start MCP server", e);
            System.exit(1);
        }
    }
}