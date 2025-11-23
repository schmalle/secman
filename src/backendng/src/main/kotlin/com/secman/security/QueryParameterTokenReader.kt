package com.secman.security

import io.micronaut.context.annotation.Replaces
import io.micronaut.core.util.StringUtils
import io.micronaut.http.HttpRequest
import io.micronaut.security.token.reader.HttpHeaderTokenReader
import io.micronaut.security.token.reader.TokenReader
import jakarta.inject.Singleton
import java.util.Optional

/**
 * Custom TokenReader that supports JWT authentication via:
 * 1. Authorization header (Bearer token) - standard for REST API calls
 * 2. Query parameter (?token=...) - for SSE/EventSource (cannot send custom headers)
 *
 * **Use Cases**:
 * - Regular API: `Authorization: Bearer <token>`
 * - SSE Endpoint: `/api/exception-badge-updates?token=<token>`
 *
 * **Security Notes**:
 * - Query parameter tokens are only used when Authorization header is absent
 * - Tokens in query parameters may be logged in access logs (use HTTPS)
 * - SSE EventSource API doesn't support custom headers, requiring this fallback
 *
 * Feature: SSE Authentication Fix (031-vuln-exception-approval)
 */
@Singleton
@Replaces(HttpHeaderTokenReader::class)
class QueryParameterTokenReader : TokenReader<HttpRequest<*>> {

    companion object {
        private const val QUERY_PARAM_TOKEN = "token"
    }

    /**
     * Read JWT token from request.
     *
     * **Priority**:
     * 1. Authorization header (preferred)
     * 2. Query parameter 'token' (fallback for SSE)
     *
     * @param request The HTTP request
     * @return Optional containing token string, or empty if no token found
     */
    override fun findToken(request: HttpRequest<*>): Optional<String> {
        // First, try to read from Authorization header (standard approach)
        val authHeader = request.headers.get("Authorization")
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            val token = authHeader.substring(7) // Remove "Bearer " prefix
            if (StringUtils.isNotEmpty(token)) {
                return Optional.of(token)
            }
        }

        // Fallback: Read from query parameter (for SSE/EventSource)
        val tokenParam = request.parameters.get(QUERY_PARAM_TOKEN)
        if (tokenParam != null && StringUtils.isNotEmpty(tokenParam)) {
            return Optional.of(tokenParam)
        }

        return Optional.empty()
    }
}
