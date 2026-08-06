package com.secman.security

import io.micronaut.core.util.StringUtils
import io.micronaut.http.HttpRequest
import io.micronaut.security.token.reader.TokenReader
import jakarta.inject.Singleton
import java.util.Optional

/**
 * Custom TokenReader that supports JWT authentication via query parameter.
 *
 * **Use Case**:
 * - SSE Endpoint: `/api/exception-badge-updates?token=<token>`
 *
 * **Note**: This TokenReader works alongside the default HttpHeaderTokenReader.
 * Micronaut Security will try all registered TokenReaders, so requests can use
 * either the Authorization header OR the query parameter.
 *
 * **Security Notes**:
 * - Query parameter tokens may be logged in access logs (use HTTPS)
 * - SSE EventSource API doesn't support custom headers, requiring this fallback
 * - Only use query parameters for SSE endpoints; prefer Authorization header for REST APIs
 *
 * Feature: SSE Authentication Fix (031-vuln-exception-approval)
 */
@Singleton
class QueryParameterTokenReader : TokenReader<HttpRequest<*>> {

    companion object {
        private const val QUERY_PARAM_TOKEN = "token"
    }

    /**
     * Read JWT token from query parameter.
     *
     * This reader only checks the query parameter, allowing Micronaut to try
     * other TokenReaders (like HttpHeaderTokenReader) if this one returns empty.
     *
     * @param request The HTTP request
     * @return Optional containing token string from query param, or empty if not found
     */
    override fun findToken(request: HttpRequest<*>): Optional<String> {
        val tokenParam = request.parameters.get(QUERY_PARAM_TOKEN)
        if (tokenParam != null && StringUtils.isNotEmpty(tokenParam)) {
            return Optional.of(tokenParam)
        }

        return Optional.empty()
    }
}
