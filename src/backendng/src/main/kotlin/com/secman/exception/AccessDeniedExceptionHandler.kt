package com.secman.exception

import com.secman.service.AccessDenialLogger
import io.micronaut.context.annotation.Requires
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpResponse
import io.micronaut.http.HttpStatus
import io.micronaut.http.annotation.Error
import io.micronaut.http.exceptions.HttpStatusException
import io.micronaut.security.authentication.Authentication
import io.micronaut.security.rules.SecurityRuleResult
import io.micronaut.security.utils.SecurityService
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory

/**
 * Global Exception Handler for Access Denied (403 Forbidden) responses
 * Feature: 025-role-based-access-control (Task T032)
 *
 * Intercepts all 403 Forbidden responses and logs them to ACCESS_DENIAL_AUDIT logger
 * with full context (user, roles, resource, timestamp, IP address)
 *
 * Constitutional Compliance:
 * - Principle I (Security-First): FR-014 - Log all access denials
 * - Principle V (RBAC): Enforce role-based access at API layer
 *
 * Per spec clarification:
 * - Log denials only (not grants)
 * - Include user ID, roles, resource, timestamp, IP address
 * - Use generic error messages (no role disclosure to user)
 */
@Singleton
@Requires(classes = [HttpStatusException::class])
class AccessDeniedExceptionHandler(
    private val accessDenialLogger: AccessDenialLogger,
    private val securityService: SecurityService
) {

    private val log = LoggerFactory.getLogger(AccessDeniedExceptionHandler::class.java)

    /**
     * Handle 403 Forbidden responses
     *
     * This handler is triggered when:
     * 1. User lacks required role for an endpoint (@Secured annotation)
     * 2. Manual HttpStatusException(FORBIDDEN) is thrown
     *
     * Logs the denial and returns generic error message to prevent role disclosure
     */
    @Error(status = HttpStatus.FORBIDDEN, global = true)
    fun handleForbidden(
        request: HttpRequest<*>,
        exception: HttpStatusException
    ): HttpResponse<Map<String, Any>> {
        // Get authenticated user (if any)
        val authentication = securityService.authentication.orElse(null)

        if (authentication != null) {
            // Log access denial with full context
            val resource = request.path
            val httpMethod = request.method.name
            val ipAddress = request.remoteAddress.address.hostAddress

            // Determine required roles from the endpoint (if available from exception message)
            // For now, we'll log with empty required roles - could be enhanced with annotation inspection
            val requiredRoles = extractRequiredRoles(exception)

            // Log the denial
            accessDenialLogger.logAccessDenialWithMethod(
                authentication = authentication,
                httpMethod = httpMethod,
                resource = resource,
                requiredRoles = requiredRoles,
                ipAddress = ipAddress
            )

            log.debug("Access denied for user '{}' to resource '{}' ({})",
                authentication.name, resource, httpMethod)
        } else {
            // Unauthenticated access attempt (shouldn't happen for 403, but log anyway)
            log.warn("Unauthenticated 403 response for resource '{}' - this may indicate misconfiguration",
                request.path)
        }

        // Return generic error message (no role disclosure per spec)
        return HttpResponse.status<Map<String, Any>>(HttpStatus.FORBIDDEN)
            .body(mapOf(
                "message" to "Access denied. You do not have permission to access this resource.",
                "error" to "Forbidden",
                "status" to 403,
                "path" to request.path,
                "suggestion" to "If you believe this is an error, please contact your system administrator."
            ))
    }

    /**
     * Extract required roles from exception message if available
     *
     * Micronaut Security may include role information in exception messages
     * This is for logging purposes only - NOT returned to the user
     */
    private fun extractRequiredRoles(exception: HttpStatusException): List<String> {
        val message = exception.message ?: return emptyList()

        // Micronaut security exception messages may contain role requirements
        // Example: "Rejected: ADMIN, RISK"
        // This is a best-effort extraction for better audit logs
        return try {
            when {
                message.contains("Required roles:") -> {
                    message.substringAfter("Required roles:")
                        .split(",")
                        .map { it.trim() }
                        .filter { it.isNotBlank() }
                }
                message.contains("Rejected:") -> {
                    message.substringAfter("Rejected:")
                        .split(",")
                        .map { it.trim() }
                        .filter { it.isNotBlank() }
                }
                else -> emptyList()
            }
        } catch (e: Exception) {
            log.debug("Could not extract required roles from exception message", e)
            emptyList()
        }
    }
}
