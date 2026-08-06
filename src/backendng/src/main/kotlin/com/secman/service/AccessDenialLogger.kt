package com.secman.service

import io.micronaut.security.authentication.Authentication
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import java.time.Instant

/**
 * Service for logging access denials for security audit purposes
 * Feature: 025-role-based-access-control
 *
 * Logs are written to a dedicated logger (ACCESS_DENIAL_AUDIT) with structured MDC context.
 * This enables easy aggregation in log management systems (Splunk, ELK, Datadog).
 *
 * Constitutional Compliance:
 * - Principle I (Security-First): FR-014 - Log all access denials with full context
 * - Per clarification: Log denials only (not grants) with user ID, roles, resource, timestamp
 */
@Singleton
class AccessDenialLogger {

    companion object {
        // Dedicated logger for access denial events
        // Allows separate log routing/filtering in log aggregation systems
        private val log = LoggerFactory.getLogger("ACCESS_DENIAL_AUDIT")
    }

    /**
     * Log an access denial event with full context
     *
     * @param authentication Authenticated user attempting access
     * @param resource Resource path attempted (e.g., "/api/risk-assessments")
     * @param requiredRoles Roles required to access the resource
     * @param ipAddress Optional IP address of the request
     */
    fun logAccessDenial(
        authentication: Authentication,
        resource: String,
        requiredRoles: List<String>,
        ipAddress: String? = null
    ) {
        try {
            // Add structured context to MDC for log aggregation
            MDC.put("event_type", "access_denied")
            MDC.put("user_id", authentication.name)
            MDC.put("user_roles", authentication.roles.joinToString(","))
            MDC.put("resource", resource)
            MDC.put("required_roles", requiredRoles.joinToString(","))
            MDC.put("timestamp", Instant.now().toString())
            ipAddress?.let { MDC.put("ip_address", it) }

            // Log at WARN level (not ERROR - this is expected behavior for unauthorized access)
            log.warn(
                "Access denied: user='{}', roles=[{}], resource='{}', required=[{}], ip='{}'",
                authentication.name,
                authentication.roles.joinToString(","),
                resource,
                requiredRoles.joinToString(","),
                ipAddress ?: "unknown"
            )
        } finally {
            // CRITICAL: Always clear MDC to prevent context leakage across threads
            MDC.clear()
        }
    }

    /**
     * Log an access denial with HTTP method context
     *
     * @param authentication Authenticated user
     * @param httpMethod HTTP method (GET, POST, PUT, DELETE)
     * @param resource Resource path
     * @param requiredRoles Required roles
     * @param ipAddress Optional IP address
     */
    fun logAccessDenialWithMethod(
        authentication: Authentication,
        httpMethod: String,
        resource: String,
        requiredRoles: List<String>,
        ipAddress: String? = null
    ) {
        try {
            MDC.put("event_type", "access_denied")
            MDC.put("user_id", authentication.name)
            MDC.put("user_roles", authentication.roles.joinToString(","))
            MDC.put("http_method", httpMethod)
            MDC.put("resource", resource)
            MDC.put("required_roles", requiredRoles.joinToString(","))
            MDC.put("timestamp", Instant.now().toString())
            ipAddress?.let { MDC.put("ip_address", it) }

            log.warn(
                "Access denied: user='{}', method={}, resource='{}', roles=[{}], required=[{}]",
                authentication.name,
                httpMethod,
                resource,
                authentication.roles.joinToString(","),
                requiredRoles.joinToString(",")
            )
        } finally {
            MDC.clear()
        }
    }
}
