package com.secman.security

import io.micronaut.core.util.StringUtils
import io.micronaut.http.HttpRequest
import io.micronaut.security.token.reader.TokenReader
import jakarta.inject.Singleton
import java.util.Optional

/**
 * Retains query-parameter JWT support for legacy clients.
 *
 * Browser EventSource callers use the HttpOnly `secman_auth` cookie, and external
 * clients can use the Authorization header. New callers should not put JWTs in
 * URLs: access logs, browser history and Referer headers may expose them.
 * Micronaut Security tries this reader alongside the cookie and header readers.
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
