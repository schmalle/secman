package com.secman.controller

import com.secman.service.*
import com.secman.service.mcp.McpAccessControlService
import com.secman.mcp.McpToolRegistry
import com.secman.mcp.tools.McpToolResult as ToolResult
import com.secman.domain.*
import com.secman.dto.mcp.*
import io.micronaut.http.HttpResponse
import io.micronaut.http.HttpStatus
import io.micronaut.http.annotation.*
import io.micronaut.security.annotation.Secured
import io.micronaut.security.rules.SecurityRule
import jakarta.inject.Inject
import jakarta.validation.Valid
import org.slf4j.LoggerFactory

/**
 * Delegation context passed through the request when X-MCP-User-Email header is present.
 */
data class DelegationContext(
    val delegatedUserEmail: String,
    val delegatedUserId: Long,
    val effectivePermissions: Set<McpPermission>
)

/**
 * Main MCP protocol controller handling core MCP operations.
 *
 * Implements the Model Context Protocol for AI assistant integration.
 */
@Controller("/api/mcp")
open class McpController(
    @Inject private val authService: McpAuthenticationService,
    @Inject private val sessionService: McpSessionService,
    @Inject private val auditService: McpAuditService,
    @Inject private val toolPermissionService: McpToolPermissionService,
    @Inject private val toolRegistry: McpToolRegistry,
    @Inject private val delegationService: McpDelegationService,
    @Inject private val accessControlService: McpAccessControlService
) {
    private val logger = LoggerFactory.getLogger(McpController::class.java)

    companion object {
        /** Header name for user delegation email. Identifies the delegated user; never a credential. */
        const val DELEGATION_HEADER = "X-MCP-User-Email"

        const val SERVER_NAME = "Secman MCP Server"
        const val SERVER_VERSION = "1.0.0"
    }

    /**
     * Outcome of a precondition check: the resolved value, or the status + error
     * body the endpoint should render into its own response envelope.
     */
    private sealed interface Checked<out T> {
        data class Denied(val status: HttpStatus, val error: McpErrorResponse) : Checked<Nothing>
        data class Ok<T>(val value: T) : Checked<T>
    }

    /** Resolve the API key behind `X-MCP-API-Key`. */
    private fun authenticateKey(apiKey: String?): Checked<McpApiKey> {
        if (apiKey == null) {
            return Checked.Denied(HttpStatus.UNAUTHORIZED, McpErrorResponse("AUTH_REQUIRED", "API key required"))
        }

        val authResult = authService.authenticateApiKey(apiKey)
        if (!authResult.success) {
            return Checked.Denied(
                HttpStatus.UNAUTHORIZED,
                McpErrorResponse("AUTH_FAILED", authResult.errorMessage ?: "Authentication failed")
            )
        }

        return Checked.Ok(authResult.apiKey!!)
    }

    /**
     * Resolve the delegated user behind `X-MCP-User-Email`.
     *
     * SECURITY: User delegation is mandatory on every data-accessing endpoint.
     */
    private fun resolveDelegation(mcpApiKey: McpApiKey, delegatedUserEmail: String?): Checked<DelegationContext> {
        if (delegatedUserEmail.isNullOrBlank()) {
            return Checked.Denied(HttpStatus.BAD_REQUEST, McpErrorResponse(
                DelegationErrorCodes.DELEGATION_HEADER_REQUIRED,
                "X-MCP-User-Email header is required for all data-accessing endpoints"
            ))
        }

        if (!mcpApiKey.delegationEnabled) {
            return Checked.Denied(HttpStatus.FORBIDDEN, McpErrorResponse(
                DelegationErrorCodes.DELEGATION_NOT_ENABLED,
                "User delegation is not enabled for this API key. Enable delegation in API key settings."
            ))
        }

        val validation = delegationService.validateDelegation(mcpApiKey, delegatedUserEmail)
        if (!validation.success) {
            logger.warn(
                "Delegation failed for email={}, key={}, error={}",
                delegatedUserEmail, mcpApiKey.keyId, validation.errorCode
            )
            return Checked.Denied(HttpStatus.FORBIDDEN, McpErrorResponse(
                validation.errorCode ?: DelegationErrorCodes.DELEGATION_FAILED,
                validation.errorMessage ?: "Delegation validation failed"
            ))
        }

        return Checked.Ok(DelegationContext(
            delegatedUserEmail = delegatedUserEmail,
            delegatedUserId = validation.user!!.id!!,
            effectivePermissions = validation.effectivePermissions
        ))
    }

    /**
     * Get MCP server capabilities.
     * Returns available tools based on effective permissions (user roles ∩ API key permissions).
     *
     * SECURITY: User delegation via X-MCP-User-Email header is mandatory.
     * Requests without this header are rejected with DELEGATION_HEADER_REQUIRED.
     */
    @Get("/capabilities")
    @Secured(SecurityRule.IS_ANONYMOUS)
    suspend fun getCapabilities(
        @Header("X-MCP-API-Key") apiKey: String?,
        @Header(DELEGATION_HEADER) delegatedUserEmail: String?
    ): HttpResponse<McpCapabilitiesResponse> {
        fun deny(denied: Checked.Denied): HttpResponse<McpCapabilitiesResponse> =
            HttpResponse.status<McpCapabilitiesResponse>(denied.status)
                .body(McpCapabilitiesResponse(error = denied.error))

        return try {
            val mcpApiKey = when (val checked = authenticateKey(apiKey)) {
                is Checked.Denied -> return deny(checked)
                is Checked.Ok -> checked.value
            }

            val delegation = when (val checked = resolveDelegation(mcpApiKey, delegatedUserEmail)) {
                is Checked.Denied -> return deny(checked)
                is Checked.Ok -> checked.value
            }

            val toolCapabilities = toolRegistry.getToolCapabilities(delegation.effectivePermissions)

            HttpResponse.ok(McpCapabilitiesResponse(
                capabilities = mapOf(
                    "tools" to (toolCapabilities["tools"] as? List<*> ?: emptyList<Any>()),
                    "resources" to emptyMap<String, Any>(),
                    "prompts" to emptyMap<String, Any>()
                ),
                serverInfo = mutableMapOf<String, Any>(
                    "name" to SERVER_NAME,
                    "version" to SERVER_VERSION,
                    "protocol" to "mcp/1.0",
                    "delegationActive" to true,
                    "delegatedUser" to delegation.delegatedUserEmail
                )
            ))

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
    open suspend fun createSession(
        @Header("X-MCP-API-Key") apiKey: String?,
        @Valid @Body request: McpSessionCreateRequest
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
     *
     * SECURITY: User delegation via X-MCP-User-Email header is mandatory.
     * Computes effective permissions as intersection of user roles and API key permissions.
     */
    @Post("/tools/call")
    @Secured(SecurityRule.IS_ANONYMOUS)
    open suspend fun callTool(
        @Header("X-MCP-API-Key") apiKey: String?,
        @Header(DELEGATION_HEADER) delegatedUserEmail: String?,
        @Valid @Body request: McpToolCallRequest
    ): HttpResponse<McpToolCallResponse> {
        val startTime = System.currentTimeMillis()

        fun deny(denied: Checked.Denied): HttpResponse<McpToolCallResponse> =
            HttpResponse.status<McpToolCallResponse>(denied.status)
                .body(McpToolCallResponse(jsonrpc = request.jsonrpc, id = request.id, error = denied.error))

        return try {
            val mcpApiKey = when (val checked = authenticateKey(apiKey)) {
                is Checked.Denied -> return deny(checked)
                is Checked.Ok -> checked.value
            }

            // Security: Check rate limits before processing (HIGH-005 fix)
            val rateLimitInfo = toolPermissionService.checkRateLimitForApiKey(mcpApiKey.id, request.id)
            if (rateLimitInfo.exceeded) {
                auditService.logAuthenticationEvent(
                    McpEventType.RATE_LIMITED,
                    mcpApiKey.id,
                    mcpApiKey.userId,
                    success = false,
                    errorCode = "RATE_LIMITED",
                    errorMessage = "Rate limit exceeded: ${rateLimitInfo.remainingCalls} calls remaining, resets at ${rateLimitInfo.resetTime}",
                    requestId = request.id
                )

                return HttpResponse.status<McpToolCallResponse>(HttpStatus.TOO_MANY_REQUESTS)
                    .body(McpToolCallResponse(
                        jsonrpc = request.jsonrpc,
                        id = request.id,
                        error = McpErrorResponse("RATE_LIMITED", "Rate limit exceeded. Resets at ${rateLimitInfo.resetTime}")
                    ))
            }

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

            val delegation = when (val checked = resolveDelegation(mcpApiKey, delegatedUserEmail)) {
                is Checked.Denied -> return deny(checked)
                is Checked.Ok -> checked.value
            }

            val effectivePermissions = delegation.effectivePermissions

            val toolName = request.params.name
            val arguments = request.params.arguments

            // Check tool permission using effective permissions
            val permissionCheck = toolPermissionService.hasPermissionWithSet(
                toolName,
                effectivePermissions,
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

            // Build execution context with access control data
            val executionContext = accessControlService.buildExecutionContext(mcpApiKey, delegation)

            // Execute tool with context
            val toolResult = tool.execute(arguments, executionContext)
            val duration = System.currentTimeMillis() - startTime

            // Log tool execution with delegation info
            val toolError = toolResult as? ToolResult.Error
            auditService.logToolCall(
                apiKeyId = mcpApiKey.id,
                userId = mcpApiKey.userId,
                sessionId = "http-${request.id}", // HTTP requests don't have sessions
                toolName = toolName,
                operation = tool.operation,
                arguments = arguments,
                success = !toolResult.isError,
                durationMs = duration,
                errorCode = toolError?.code,
                errorMessage = toolError?.message,
                requestId = request.id,
                delegatedUserEmail = delegation.delegatedUserEmail,
                delegatedUserId = delegation.delegatedUserId
            )

            val response = when (toolResult) {
                is ToolResult.Error -> McpToolCallResponse(
                    jsonrpc = request.jsonrpc,
                    id = request.id,
                    error = McpErrorResponse(toolResult.code, toolResult.message)
                )
                is ToolResult.Success -> McpToolCallResponse(
                    jsonrpc = request.jsonrpc,
                    id = request.id,
                    result = McpToolResult(
                        content = toolResult.content,
                        isError = false,
                        metadata = toolResult.metadata
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
                        val apiKeyVal = authResult.apiKey!!
                        auditService.logToolCall(
                            apiKeyId = apiKeyVal.id,
                            userId = apiKeyVal.userId,
                            sessionId = "http-${request.id}",
                            toolName = request.params.name,
                            operation = McpOperation.READ, // Default
                            arguments = request.params.arguments,
                            success = false,
                            durationMs = duration,
                            errorCode = "SYSTEM_ERROR",
                            errorMessage = "An internal error occurred",
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