package com.secman.exception

import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpResponse
import io.micronaut.http.annotation.Produces
import io.micronaut.http.server.exceptions.ExceptionHandler
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory

/**
 * Catch-all exception handler that prevents internal implementation details
 * (database schema, class names, stack traces) from leaking to API clients.
 *
 * Logs the full exception server-side and returns a generic error message.
 */
@Produces
@Singleton
class GenericExceptionHandler : ExceptionHandler<Exception, HttpResponse<*>> {

    private val log = LoggerFactory.getLogger(GenericExceptionHandler::class.java)

    override fun handle(request: HttpRequest<*>, exception: Exception): HttpResponse<*> {
        log.error("Unhandled exception for {} {}: {}", request.method, request.uri, exception.message, exception)

        return HttpResponse.serverError(mapOf(
            "error" to "Internal Server Error",
            "message" to "An unexpected error occurred. Please try again or contact support."
        ))
    }
}
