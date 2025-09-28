package com.secman.controller

import com.secman.service.*
import com.secman.mcp.McpToolRegistry
import com.secman.domain.*
import com.secman.dto.mcp.*
import io.micronaut.http.HttpResponse
import io.micronaut.http.HttpStatus
import io.micronaut.http.annotation.*
import io.micronaut.security.annotation.Secured
import io.micronaut.security.rules.SecurityRule
import jakarta.inject.Inject
import org.slf4j.LoggerFactory
import java.time.LocalDateTime

/**
 * Main MCP protocol controller handling core MCP operations.
 *
 * Implements the Model Context Protocol for AI assistant integration.
 */
@Controller("/api/mcp")
class McpController(
    @Inject private val authService: McpAuthenticationService,
    @Inject private val sessionService: McpSessionService,
    @Inject private val auditService: McpAuditService,
    @Inject private val toolPermissionService: McpToolPermissionService,
    @Inject private val toolRegistry: McpToolRegistry
) {
    private val logger = LoggerFactory.getLogger(McpController::class.java)

    /**
     * Get MCP server capabilities.
     * Returns available tools based on API key permissions.
     */
    @Get("/capabilities")
    @Secured(SecurityRule.IS_ANONYMOUS)
    suspend fun getCapabilities(@Header("X-MCP-API-Key") apiKey: String?): HttpResponse<McpCapabilitiesResponse> {
        return try {
            if (apiKey == null) {
                return HttpResponse.status<McpCapabilitiesResponse>(HttpStatus.UNAUTHORIZED)
                    .body(McpCapabilitiesResponse(error = McpErrorResponse("AUTH_REQUIRED", "API key required")))
            }

            val authResult = authService.authenticateApiKey(apiKey)
            if (!authResult.success) {
                return HttpResponse.status<McpCapabilitiesResponse>(HttpStatus.UNAUTHORIZED)
                    .body(McpCapabilitiesResponse(error = McpErrorResponse("AUTH_FAILED", authResult.errorMessage ?: "Authentication failed")))
            }

            val mcpApiKey = authResult.apiKey!!
            val permissions = mcpApiKey.getPermissionSet()
            val toolCapabilities = toolRegistry.getToolCapabilities(permissions)

            val response = McpCapabilitiesResponse(
                capabilities = mapOf(
                    "tools" to (toolCapabilities["tools"] as? List<*> ?: emptyList<Any>()),
                    "resources" to emptyMap<String, Any>(),
                    "prompts" to emptyMap<String, Any>()
                ),
                serverInfo = mapOf(
                    "name" to "Secman MCP Server",
                    "version" to "1.0.0",
                    "protocol" to "mcp/1.0"
                )
            )

            HttpResponse.ok(response)

        } catch (e: Exception) {
            logger.error("Capabilities request failed", e)
            HttpResponse.status<McpCapabilitiesResponse>(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(McpCapabilitiesResponse(error = McpErrorResponse("SYSTEM_ERROR", "Internal server error")))
        }
    }

    /**
     * Create a new MCP session.
     */
    @Post("/session")
    @Secured(SecurityRule.IS_ANONYMOUS)
    suspend fun createSession(
        @Header("X-MCP-API-Key") apiKey: String?,
        @Body request: McpSessionCreateRequest
    ): HttpResponse<McpSessionResponse> {
        return try {
            if (apiKey == null) {
                return HttpResponse.status<McpSessionResponse>(HttpStatus.UNAUTHORIZED)
                    .body(McpSessionResponse(error = McpErrorResponse("AUTH_REQUIRED", "API key required")))
            }

            val authResult = authService.authenticateApiKey(apiKey)
            if (!authResult.success) {
                return HttpResponse.status<McpSessionResponse>(HttpStatus.UNAUTHORIZED)
                    .body(McpSessionResponse(error = McpErrorResponse("AUTH_FAILED", authResult.errorMessage ?: "Authentication failed")))
            }

            val mcpApiKey = authResult.apiKey!!

            // Validate request
            val validationError = validateSessionRequest(request)
            if (validationError != null) {
                return HttpResponse.badRequest(
                    McpSessionResponse(error = McpErrorResponse("INVALID_REQUEST", validationError))
                )
            }

            val sessionResult = sessionService.createSession(
                apiKeyId = mcpApiKey.id,
                userId = mcpApiKey.userId,
                clientInfo = request.clientInfo.toJsonString(),
                capabilities = request.capabilities.toJsonString(),
                connectionType = McpConnectionType.HTTP
            )

            if (!sessionResult.success) {
                return HttpResponse.badRequest(
                    McpSessionResponse(error = McpErrorResponse(sessionResult.errorCode!!, sessionResult.errorMessage!!))
                )
            }

            val response = McpSessionResponse(
                sessionId = sessionResult.sessionId!!,
                capabilities = request.capabilities,
                serverInfo = mapOf(
                    "name" to "Secman MCP Server",
                    "version" to "1.0.0"
                )
            )

            HttpResponse.status<McpSessionResponse>(HttpStatus.CREATED).body(response)

        } catch (e: Exception) {
            logger.error("Session creation failed", e)
            HttpResponse.status<McpSessionResponse>(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(McpSessionResponse(error = McpErrorResponse("SYSTEM_ERROR", "Session creation failed")))
        }
    }

    /**
     * Close an MCP session.
     */
    @Delete("/session/{sessionId}")
    @Secured(SecurityRule.IS_ANONYMOUS)
    suspend fun closeSession(
        @Header("X-MCP-API-Key") apiKey: String?,
        sessionId: String
    ): HttpResponse<Void> {
        return try {
            if (apiKey == null) {
                return HttpResponse.status(HttpStatus.UNAUTHORIZED)
            }

            val authResult = authService.authenticateApiKey(apiKey)
            if (!authResult.success) {
                return HttpResponse.status(HttpStatus.UNAUTHORIZED)
            }

            // Validate session belongs to this API key
            val sessionValidation = sessionService.validateSession(sessionId, false)
            if (!sessionValidation.valid) {
                return HttpResponse.status(HttpStatus.NOT_FOUND)
            }

            val session = sessionValidation.session!!
            if (session.apiKeyId != authResult.apiKey!!.id) {
                return HttpResponse.status(HttpStatus.FORBIDDEN)
            }

            val closeResult = sessionService.closeSession(sessionId, "Client requested")
            if (!closeResult.success) {
                return HttpResponse.status(HttpStatus.INTERNAL_SERVER_ERROR)
            }

            HttpResponse.noContent()

        } catch (e: Exception) {
            logger.error("Session close failed for sessionId: $sessionId", e)
            HttpResponse.status(HttpStatus.INTERNAL_SERVER_ERROR)
        }
    }

    /**
     * Execute an MCP tool.
     */
    @Post("/tools/call")
    @Secured(SecurityRule.IS_ANONYMOUS)
    suspend fun callTool(
        @Header("X-MCP-API-Key") apiKey: String?,
        @Body request: McpToolCallRequest
    ): HttpResponse<McpToolCallResponse> {
        val startTime = System.currentTimeMillis()

        return try {
            if (apiKey == null) {
                return HttpResponse.status<McpToolCallResponse>(HttpStatus.UNAUTHORIZED)
                    .body(McpToolCallResponse(
                        jsonrpc = request.jsonrpc,
                        id = request.id,
                        error = McpErrorResponse("AUTH_REQUIRED", "API key required")
                    ))
            }

            val authResult = authService.authenticateApiKey(apiKey)
            if (!authResult.success) {
                return HttpResponse.status<McpToolCallResponse>(HttpStatus.UNAUTHORIZED)
                    .body(McpToolCallResponse(
                        jsonrpc = request.jsonrpc,
                        id = request.id,
                        error = McpErrorResponse("AUTH_FAILED", authResult.errorMessage ?: "Authentication failed")
                    ))
            }

            val mcpApiKey = authResult.apiKey!!

            // Validate JSON-RPC format
            if (request.jsonrpc != "2.0" || request.method != "tools/call") {
                return HttpResponse.badRequest(
                    McpToolCallResponse(
                        jsonrpc = request.jsonrpc,
                        id = request.id,
                        error = McpErrorResponse("INVALID_REQUEST", "Invalid JSON-RPC request")
                    )
                )
            }

            val toolName = request.params.name
            val arguments = request.params.arguments

            // Check tool permission
            val permissionCheck = toolPermissionService.hasPermission(
                mcpApiKey.id,
                toolName,
                arguments,
                request.id
            )

            if (!permissionCheck.granted) {
                auditService.logAuthenticationEvent(
                    McpEventType.PERMISSION_DENIED,
                    mcpApiKey.id,
                    mcpApiKey.userId,
                    success = false,
                    errorCode = "PERMISSION_DENIED",
                    errorMessage = permissionCheck.reason,
                    requestId = request.id
                )

                return HttpResponse.status<McpToolCallResponse>(HttpStatus.FORBIDDEN)
                    .body(McpToolCallResponse(
                        jsonrpc = request.jsonrpc,
                        id = request.id,
                        error = McpErrorResponse("PERMISSION_DENIED", permissionCheck.reason)
                    ))
            }

            // Get and validate tool
            val tool = toolRegistry.getTool(toolName)
            if (tool == null) {
                return HttpResponse.badRequest(
                    McpToolCallResponse(
                        jsonrpc = request.jsonrpc,
                        id = request.id,
                        error = McpErrorResponse("TOOL_NOT_FOUND", "Tool '$toolName' not found")
                    )
                )
            }

            // Validate arguments
            val validationResult = toolRegistry.validateArguments(toolName, arguments)
            if (!validationResult.isValid) {
                return HttpResponse.badRequest(
                    McpToolCallResponse(
                        jsonrpc = request.jsonrpc,
                        id = request.id,
                        error = McpErrorResponse("INVALID_ARGUMENTS", validationResult.errorMessage!!)
                    )
                )
            }

            // Execute tool
            val toolResult = tool.execute(arguments)
            val duration = System.currentTimeMillis() - startTime

            // Log tool execution
            auditService.logToolCall(
                apiKeyId = mcpApiKey.id,
                userId = mcpApiKey.userId,
                sessionId = "http-${request.id}", // HTTP requests don't have sessions
                toolName = toolName,
                operation = tool.operation,
                arguments = arguments,
                success = !toolResult.isError,
                durationMs = duration,
                errorCode = if (toolResult.isError) (toolResult as com.secman.mcp.tools.McpToolResult.Error).code else null,
                errorMessage = if (toolResult.isError) (toolResult as com.secman.mcp.tools.McpToolResult.Error).message else null,
                requestId = request.id
            )

            val response = if (toolResult.isError) {
                val error = toolResult as com.secman.mcp.tools.McpToolResult.Error
                McpToolCallResponse(
                    jsonrpc = request.jsonrpc,
                    id = request.id,
                    error = McpErrorResponse(error.code, error.message)
                )
            } else {
                val success = toolResult as com.secman.mcp.tools.McpToolResult.Success
                McpToolCallResponse(
                    jsonrpc = request.jsonrpc,
                    id = request.id,
                    result = McpToolResult(
                        content = success.content,
                        isError = false,
                        metadata = success.metadata
                    )
                )
            }

            HttpResponse.ok(response)

        } catch (e: Exception) {
            val duration = System.currentTimeMillis() - startTime
            logger.error("Tool call failed: ${request.params.name}", e)

            // Log the error
            try {
                if (apiKey != null) {
                    val authResult = authService.authenticateApiKey(apiKey)
                    if (authResult.success) {
                        auditService.logToolCall(
                            apiKeyId = authResult.apiKey!!.id,
                            userId = authResult.apiKey!!.userId,
                            sessionId = "http-${request.id}",
                            toolName = request.params.name,
                            operation = McpOperation.READ, // Default
                            arguments = request.params.arguments,
                            success = false,
                            durationMs = duration,
                            errorCode = "SYSTEM_ERROR",
                            errorMessage = e.message,
                            requestId = request.id
                        )
                    }
                }
            } catch (auditException: Exception) {
                logger.error("Failed to log tool execution error", auditException)
            }

            HttpResponse.status<McpToolCallResponse>(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(McpToolCallResponse(
                    jsonrpc = request.jsonrpc,
                    id = request.id,
                    error = McpErrorResponse("SYSTEM_ERROR", "Tool execution failed")
                ))
        }
    }

    private fun validateSessionRequest(request: McpSessionCreateRequest): String? {
        if (request.clientInfo.name.isBlank()) {
            return "Client name is required"
        }

        if (request.clientInfo.version.isBlank()) {
            return "Client version is required"
        }

        return null
    }

    private fun McpClientInfo.toJsonString(): String {
        return com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(
            mapOf(
                "name" to name,
                "version" to version,
                "additionalInfo" to (additionalInfo ?: emptyMap())
            )
        )
    }

    private fun Map<String, Any>.toJsonString(): String {
        return com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(this)
    }
}