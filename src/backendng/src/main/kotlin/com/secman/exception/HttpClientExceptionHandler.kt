package com.secman.exception

import io.micronaut.context.annotation.Requires
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpResponse
import io.micronaut.http.HttpStatus
import io.micronaut.http.annotation.Produces
import io.micronaut.http.client.exceptions.HttpClientException
import io.micronaut.http.client.exceptions.ReadTimeoutException
import io.micronaut.http.server.exceptions.ExceptionHandler
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory

/**
 * Global exception handler for HTTP client exceptions
 *
 * Ensures that HTTP client errors (timeouts, connection failures) from external API calls
 * (like CrowdStrike Spotlight API) return JSON responses instead of HTML error pages.
 *
 * This handler catches:
 * - ReadTimeoutException: When external API takes too long to respond
 * - HttpClientException: General HTTP client errors (connection refused, channel closed, etc.)
 */
@Produces
@Singleton
@Requires(classes = [HttpClientException::class])
class HttpClientExceptionHandler : ExceptionHandler<HttpClientException, HttpResponse<*>> {

    private val log = LoggerFactory.getLogger(HttpClientExceptionHandler::class.java)

    override fun handle(request: HttpRequest<*>, exception: HttpClientException): HttpResponse<*> {
        val errorMessage = when (exception) {
            is ReadTimeoutException -> {
                log.error("Read timeout during {} {}: {}", request.method, request.uri, exception.message)
                "CrowdStrike API error: Read Timeout - The request took too long. Please try again."
            }
            else -> {
                val message = exception.message ?: "Unknown HTTP client error"
                log.error("HTTP client error during {} {}: {}", request.method, request.uri, message)

                when {
                    message.contains("timeout", ignoreCase = true) ->
                        "CrowdStrike API error: Request timed out. Please try again."
                    message.contains("Channel closed", ignoreCase = true) ->
                        "CrowdStrike API error: Connection was closed. Please try again."
                    message.contains("Connection refused", ignoreCase = true) ->
                        "CrowdStrike API error: Unable to connect to external service."
                    else ->
                        "CrowdStrike API error: $message"
                }
            }
        }

        return HttpResponse.status<Map<String, String>>(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(mapOf("error" to errorMessage))
    }
}
