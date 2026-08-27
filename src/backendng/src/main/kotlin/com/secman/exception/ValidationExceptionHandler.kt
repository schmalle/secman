package com.secman.exception

import io.micronaut.context.annotation.Primary
import io.micronaut.context.annotation.Requires
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpResponse
import io.micronaut.http.annotation.Produces
import io.micronaut.http.server.exceptions.ExceptionHandler
import jakarta.inject.Singleton
import jakarta.validation.ConstraintViolationException
import org.slf4j.LoggerFactory

/**
 * Global exception handler for validation errors
 *
 * Intercepts ConstraintViolationException thrown by @Valid annotation
 * and logs detailed validation failure information before returning HTTP 400.
 *
 * Purpose: Diagnose 400 Bad Request errors during CrowdStrike import
 * Feature: 032-servers-query-import (debugging support)
 *
 * @Primary annotation resolves bean conflict with Micronaut's built-in ConstraintExceptionHandler
 */
@Produces
@Singleton
@Primary
@Requires(classes = [ConstraintViolationException::class])
class ValidationExceptionHandler : ExceptionHandler<ConstraintViolationException, HttpResponse<*>> {

    private val log = LoggerFactory.getLogger(ValidationExceptionHandler::class.java)

    companion object {
        private const val MAX_RENDERED_VALUE_CHARS = 100

        /**
         * Render a rejected value for the server-side log, bounded in size.
         *
         * A `@Size` violation on a collection carries the **entire collection** as the invalid
         * value, so rendering it verbatim writes the whole rejected request body to the log:
         * six oversized CrowdStrike hosts produced 27MB of backend log on 2026-08-25. For a
         * collection the element count is the only diagnostic that matters, and every other
         * value is truncated so no single violation can dominate the log.
         *
         * Internal only for testing — nothing outside this handler should format violations.
         */
        internal fun renderInvalidValue(value: Any?): String = when {
            value == null -> "null"
            value is Collection<*> -> "<collection of ${value.size} element(s)>"
            value is Map<*, *> -> "<map of ${value.size} entry(s)>"
            value is String && value.length > MAX_RENDERED_VALUE_CHARS ->
                "${value.take(MAX_RENDERED_VALUE_CHARS)}... (${value.length} chars)"
            value is String && value.isBlank() -> "\"\" (blank string)"
            else -> value.toString().let {
                if (it.length > MAX_RENDERED_VALUE_CHARS) {
                    "${it.take(MAX_RENDERED_VALUE_CHARS)}... (${it.length} chars)"
                } else {
                    it
                }
            }
        }
    }

    override fun handle(request: HttpRequest<*>, exception: ConstraintViolationException): HttpResponse<*> {
        // Extract all constraint violations with detailed information for logging
        val detailedViolations = exception.constraintViolations.map { violation ->
            val path = violation.propertyPath.toString()
            val message = violation.message
            "$path: $message (value: ${renderInvalidValue(violation.invalidValue)})"
        }

        // Log detailed validation failure (server-side only, not exposed to client)
        log.error("Validation failed for {} {}: {} violation(s) found",
            request.method,
            request.uri,
            detailedViolations.size
        )

        detailedViolations.forEach { violation ->
            log.error("  - {}", violation)
        }

        // Return safe error response without internal field names or submitted values
        val safeViolations = exception.constraintViolations.map { violation ->
            val fieldName = violation.propertyPath.toString().substringAfterLast(".")
            "$fieldName: ${violation.message}"
        }

        return HttpResponse.badRequest(mapOf(
            "error" to "Validation failed",
            "message" to "Request contains ${safeViolations.size} validation error(s)",
            "violations" to safeViolations
        ))
    }
}
