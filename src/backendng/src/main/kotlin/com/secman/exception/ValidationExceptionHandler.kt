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

    override fun handle(request: HttpRequest<*>, exception: ConstraintViolationException): HttpResponse<*> {
        // Extract all constraint violations with detailed information
        val violations = exception.constraintViolations.map { violation ->
            val path = violation.propertyPath.toString()
            val message = violation.message
            val invalidValue = violation.invalidValue?.let { value ->
                when {
                    value is String && value.length > 100 -> "${value.take(100)}... (${value.length} chars)"
                    value is String && value.isBlank() -> "\"\" (blank string)"
                    else -> value.toString()
                }
            } ?: "null"

            "$path: $message (value: $invalidValue)"
        }

        // Log detailed validation failure
        log.error("Validation failed for {} {}: {} violation(s) found",
            request.method,
            request.uri,
            violations.size
        )

        violations.forEach { violation ->
            log.error("  - {}", violation)
        }

        // Return user-friendly error response
        return HttpResponse.badRequest(mapOf(
            "error" to "Validation failed",
            "message" to "Request contains ${violations.size} validation error(s)",
            "violations" to violations
        ))
    }
}
