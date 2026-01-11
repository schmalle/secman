package com.secman.controller

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.secman.domain.*
import com.secman.dto.mcp.*
import com.secman.mcp.McpToolRegistry
import com.secman.mcp.tools.McpToolResult as ToolResult
import com.secman.service.*
import com.secman.service.mcp.McpAccessControlService
import io.micronaut.http.HttpResponse
import io.micronaut.http.HttpStatus
import io.micronaut.http.MediaType
import io.micronaut.http.annotation.*
import io.micronaut.security.annotation.Secured
import io.micronaut.security.rules.SecurityRule
import jakarta.inject.Inject
import org.slf4j.LoggerFactory

/**
 * MCP Streamable HTTP Transport Controller.
 *
 * Implements the MCP Streamable HTTP transport specification, allowing Claude Desktop
 * and other MCP clients to connect directly to secman without requiring Node.js middleware.
 *
 * Endpoint: POST /mcp
 *
 * Authentication: X-MCP-API-Key header (required)
 * User Delegation: X-MCP-User-Email header (optional, requires delegation-enabled API key)
 *
 * @see https://modelcontextprotocol.io/specification/2024-11-05/basic/transports
 */
@Controller("/mcp")
@Secured(SecurityRule.IS_ANONYMOUS)
class McpStreamableHttpController(
    @Inject private val authService: McpAuthenticationService,
    @Inject private val toolRegistry: McpToolRegistry,
    @Inject private val delegationService: McpDelegationService,
    @Inject private val accessControlService: McpAccessControlService,
    @Inject private val auditService: McpAuditService,
    @Inject private val toolPermissionService: McpToolPermissionService,
    @Inject private val objectMapper: ObjectMapper
) {
    private val logger = LoggerFactory.getLogger(McpStreamableHttpController::class.java)

    companion object {
        const val API_KEY_HEADER = "X-MCP-API-Key"
        const val DELEGATION_HEADER = "X-MCP-User-Email"
        const val SESSION_HEADER = "Mcp-Session-Id"

        const val SERVER_NAME = "Secman MCP Server"
        const val SERVER_VERSION = "1.0.0"
    }

    /**
     * Handle MCP JSON-RPC requests.
     *
     * Supports both single requests and batch requests (JSON-RPC 2.0).
     */
    @Post(produces = [MediaType.APPLICATION_JSON], consumes = [MediaType.APPLICATION_JSON])
    suspend fun handlePost(
        @Header(API_KEY_HEADER) apiKey: String?,
        @Header(DELEGATION_HEADER) delegatedUserEmail: String?,
        @Header(SESSION_HEADER) sessionId: String?,
        @Body body: String
    ): HttpResponse<Any> {
        logger.debug("MCP request received, body length: {}", body.length)

        // Parse JSON body
        val parsed = try {
            parseRequestBody(body)
        } catch (e: JsonProcessingException) {
            logger.warn("Failed to parse MCP request body: {}", e.message)
            return HttpResponse.ok(JsonRpcResponse.parseError())
        }

        // Handle batch requests
        if (parsed is List<*>) {
            @Suppress("UNCHECKED_CAST")
            val requests = parsed as List<JsonRpcRequest>
            if (requests.isEmpty()) {
                return HttpResponse.ok(JsonRpcResponse.invalidRequest(null, "Empty batch request"))
            }

            val responses = requests.mapNotNull { request ->
                processRequest(request, apiKey, delegatedUserEmail)
            }

            // If all requests were notifications, return 204 No Content
            return if (responses.isEmpty()) {
                HttpResponse.noContent()
            } else {
                HttpResponse.ok(responses)
            }
        }

        // Handle single request
        val request = parsed as JsonRpcRequest
        val response = processRequest(request, apiKey, delegatedUserEmail)

        // Notifications don't get a response
        return if (response == null) {
            HttpResponse.noContent()
        } else {
            HttpResponse.ok(response)
        }
    }

    /**
     * Parse the request body, detecting single vs batch requests.
     */
    private fun parseRequestBody(body: String): Any {
        val trimmed = body.trim()
        return if (trimmed.startsWith("[")) {
            objectMapper.readValue<List<JsonRpcRequest>>(body)
        } else {
            objectMapper.readValue<JsonRpcRequest>(body)
        }
    }

    /**
     * Process a single JSON-RPC request.
     * Returns null for notifications (requests without id).
     */
    private suspend fun processRequest(
        request: JsonRpcRequest,
        apiKey: String?,
        delegatedUserEmail: String?
    ): JsonRpcResponse? {
        val isNotification = request.id == null

        // Validate JSON-RPC version
        if (request.jsonrpc != "2.0") {
            return if (isNotification) null else JsonRpcResponse.invalidRequest(
                request.id,
                "Invalid JSON-RPC version: ${request.jsonrpc}"
            )
        }

        // Handle notifications that don't require auth
        if (isNotification && request.method == McpMethods.INITIALIZED) {
            logger.debug("Received initialized notification")
            return null
        }
        if (isNotification && request.method == McpMethods.CANCELLED) {
            logger.debug("Received cancelled notification")
            return null
        }

        // Route to appropriate handler
        return try {
            when (request.method) {
                McpMethods.INITIALIZE -> handleInitialize(request, apiKey)
                McpMethods.PING -> handlePing(request)
                McpMethods.TOOLS_LIST -> handleToolsList(request, apiKey, delegatedUserEmail)
                McpMethods.TOOLS_CALL -> handleToolsCall(request, apiKey, delegatedUserEmail)
                else -> JsonRpcResponse.methodNotFound(request.id, request.method)
            }
        } catch (e: Exception) {
            logger.error("Error processing MCP request: method={}", request.method, e)
            JsonRpcResponse.internalError(request.id, "Internal server error: ${e.message}")
        }
    }

    /**
     * Handle the initialize method.
     * This is the first method called during MCP handshake.
     */
    private suspend fun handleInitialize(
        request: JsonRpcRequest,
        apiKey: String?
    ): JsonRpcResponse {
        logger.info("Processing MCP initialize request")

        // Authentication required for initialize
        if (apiKey == null) {
            return JsonRpcResponse.authRequired(request.id)
        }

        val authResult = authService.authenticateApiKey(apiKey)
        if (!authResult.success) {
            return JsonRpcResponse.authFailed(request.id, authResult.errorMessage ?: "Authentication failed")
        }

        // Parse initialize params
        val params = try {
            if (request.params != null) {
                objectMapper.convertValue(request.params, McpInitializeParams::class.java)
            } else {
                null
            }
        } catch (e: Exception) {
            logger.warn("Failed to parse initialize params: {}", e.message)
            null
        }

        // Validate protocol version
        val clientVersion = params?.protocolVersion ?: McpProtocolVersions.LATEST
        val negotiatedVersion = if (McpProtocolVersions.isSupported(clientVersion)) {
            clientVersion
        } else {
            McpProtocolVersions.LATEST
        }

        // Build server capabilities
        val serverCapabilities = McpServerCapabilities(
            tools = McpToolsCapability(listChanged = false),
            resources = null, // Not implemented yet
            prompts = null,   // Not implemented yet
            logging = null
        )

        val serverInfo = McpServerImplementation(
            name = SERVER_NAME,
            version = SERVER_VERSION
        )

        val result = McpInitializeResult(
            protocolVersion = negotiatedVersion,
            capabilities = serverCapabilities,
            serverInfo = serverInfo,
            instructions = "Secman MCP Server - Security requirement and risk assessment management. " +
                    "Use tools/list to see available tools based on your API key permissions."
        )

        logger.info("MCP initialize successful, negotiated protocol version: {}", negotiatedVersion)
        return JsonRpcResponse.success(request.id, result)
    }

    /**
     * Handle the ping method.
     * Simple health check that returns an empty object.
     */
    private fun handlePing(request: JsonRpcRequest): JsonRpcResponse {
        logger.debug("Processing MCP ping request")
        return JsonRpcResponse.success(request.id, emptyMap<String, Any>())
    }

    /**
     * Handle the tools/list method.
     * Returns available tools based on API key permissions.
     */
    private suspend fun handleToolsList(
        request: JsonRpcRequest,
        apiKey: String?,
        delegatedUserEmail: String?
    ): JsonRpcResponse {
        logger.debug("Processing MCP tools/list request")

        // Authentication required
        if (apiKey == null) {
            return JsonRpcResponse.authRequired(request.id)
        }

        val authResult = authService.authenticateApiKey(apiKey)
        if (!authResult.success) {
            return JsonRpcResponse.authFailed(request.id, authResult.errorMessage ?: "Authentication failed")
        }

        val mcpApiKey = authResult.apiKey!!

        // Handle user delegation
        val effectivePermissions = if (!delegatedUserEmail.isNullOrBlank() && mcpApiKey.delegationEnabled) {
            val validationResult = delegationService.validateDelegation(mcpApiKey, delegatedUserEmail)
            if (validationResult.success) {
                validationResult.effectivePermissions
            } else {
                return JsonRpcResponse.permissionDenied(
                    request.id,
                    validationResult.errorMessage ?: "Delegation validation failed"
                )
            }
        } else {
            mcpApiKey.getPermissionSet()
        }

        // Get authorized tools
        val authorizedTools = toolRegistry.getAuthorizedTools(effectivePermissions)

        val tools = authorizedTools.map { (name, tool) ->
            McpToolDefinitionDto(
                name = name,
                description = tool.description,
                inputSchema = tool.inputSchema
            )
        }

        val result = McpToolsListResult(tools = tools)

        logger.debug("Returning {} tools for API key {}", tools.size, mcpApiKey.keyId)
        return JsonRpcResponse.success(request.id, result)
    }

    /**
     * Handle the tools/call method.
     * Executes a tool and returns the result.
     */
    private suspend fun handleToolsCall(
        request: JsonRpcRequest,
        apiKey: String?,
        delegatedUserEmail: String?
    ): JsonRpcResponse {
        val startTime = System.currentTimeMillis()

        // Parse tool call params
        val params = try {
            if (request.params != null) {
                objectMapper.convertValue(request.params, McpToolsCallParams::class.java)
            } else {
                return JsonRpcResponse.invalidParams(request.id, "Tool call requires name parameter")
            }
        } catch (e: Exception) {
            logger.warn("Failed to parse tools/call params: {}", e.message)
            return JsonRpcResponse.invalidParams(request.id, "Invalid tool call parameters: ${e.message}")
        }

        val toolName = params.name
        val arguments = params.arguments ?: emptyMap()

        logger.debug("Processing MCP tools/call: tool={}", toolName)

        // Authentication required
        if (apiKey == null) {
            return JsonRpcResponse.authRequired(request.id)
        }

        val authResult = authService.authenticateApiKey(apiKey)
        if (!authResult.success) {
            return JsonRpcResponse.authFailed(request.id, authResult.errorMessage ?: "Authentication failed")
        }

        val mcpApiKey = authResult.apiKey!!

        // Handle user delegation
        val delegation = if (!delegatedUserEmail.isNullOrBlank() && mcpApiKey.delegationEnabled) {
            val validationResult = delegationService.validateDelegation(mcpApiKey, delegatedUserEmail)
            if (validationResult.success) {
                DelegationContext(
                    delegatedUserEmail = delegatedUserEmail,
                    delegatedUserId = validationResult.user!!.id!!,
                    effectivePermissions = validationResult.effectivePermissions
                )
            } else {
                return JsonRpcResponse.permissionDenied(
                    request.id,
                    validationResult.errorMessage ?: "Delegation validation failed"
                )
            }
        } else {
            null
        }

        // Determine effective permissions
        val effectivePermissions = delegation?.effectivePermissions ?: mcpApiKey.getPermissionSet()

        // Check tool permission
        val permissionCheck = toolPermissionService.hasPermissionWithSet(
            toolName,
            effectivePermissions,
            request.id?.toString() ?: "unknown"
        )

        if (!permissionCheck.granted) {
            auditService.logAuthenticationEvent(
                McpEventType.PERMISSION_DENIED,
                mcpApiKey.id,
                mcpApiKey.userId,
                success = false,
                errorCode = "PERMISSION_DENIED",
                errorMessage = permissionCheck.reason,
                requestId = request.id?.toString()
            )

            return JsonRpcResponse.permissionDenied(request.id, permissionCheck.reason)
        }

        // Get and validate tool
        val tool = toolRegistry.getTool(toolName)
        if (tool == null) {
            return JsonRpcResponse.error(
                request.id,
                JsonRpcErrorCodes.TOOL_NOT_FOUND,
                "Tool not found: $toolName"
            )
        }

        // Validate arguments
        val validationResult = toolRegistry.validateArguments(toolName, arguments)
        if (!validationResult.isValid) {
            return JsonRpcResponse.invalidParams(request.id, validationResult.errorMessage!!)
        }

        // Build execution context
        val executionContext = accessControlService.buildExecutionContext(mcpApiKey, delegation)

        // Execute tool
        val toolResult = try {
            tool.execute(arguments, executionContext)
        } catch (e: Exception) {
            logger.error("Tool execution failed: tool={}", toolName, e)
            ToolResult.Error("EXECUTION_ERROR", "Tool execution failed: ${e.message}")
        }

        val duration = System.currentTimeMillis() - startTime

        // Log tool execution
        auditService.logToolCall(
            apiKeyId = mcpApiKey.id,
            userId = mcpApiKey.userId,
            sessionId = "streamable-http-${request.id}",
            toolName = toolName,
            operation = tool.operation,
            arguments = arguments,
            success = !toolResult.isError,
            durationMs = duration,
            errorCode = if (toolResult.isError) (toolResult as ToolResult.Error).code else null,
            errorMessage = if (toolResult.isError) (toolResult as ToolResult.Error).message else null,
            requestId = request.id?.toString(),
            delegatedUserEmail = delegation?.delegatedUserEmail,
            delegatedUserId = delegation?.delegatedUserId
        )

        // Build response
        return if (toolResult.isError) {
            val error = toolResult as ToolResult.Error
            JsonRpcResponse.error(request.id, JsonRpcErrorCodes.INTERNAL_ERROR, error.message)
        } else {
            val success = toolResult as ToolResult.Success
            val result = McpToolsCallResult(
                content = listOf(
                    McpContentItem(
                        type = "text",
                        text = objectMapper.writeValueAsString(success.content)
                    )
                ),
                isError = false
            )
            JsonRpcResponse.success(request.id, result)
        }
    }
}
