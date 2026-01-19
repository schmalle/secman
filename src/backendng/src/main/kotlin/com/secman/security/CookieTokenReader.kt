package com.secman.security

import com.secman.service.AuthCookieService
import io.micronaut.core.util.StringUtils
import io.micronaut.http.HttpRequest
import io.micronaut.security.token.reader.TokenReader
import jakarta.inject.Singleton
import java.util.Optional

/**
 * Custom TokenReader that supports JWT authentication via HttpOnly cookie.
 *
 * **Security Benefits**:
 * - HttpOnly cookies are not accessible via JavaScript, preventing XSS token theft
 * - Secure flag ensures cookie is only sent over HTTPS
 * - SameSite=Lax provides CSRF protection while allowing navigation
 *
 * **Use Case**:
 * - Primary authentication mechanism for browser-based clients
 * - Works alongside Authorization header for backward compatibility
 *
 * **Priority**:
 * - Micronaut tries all TokenReaders, so both cookie and Authorization header work
 * - Order is determined by @Order annotation if needed
 *
 * Feature: Security Hardening - JWT in HttpOnly Cookies
 */
@Singleton
class CookieTokenReader : TokenReader<HttpRequest<*>> {

    /**
     * Read JWT token from HttpOnly cookie.
     *
     * This reader checks for the authentication cookie set during login.
     * If not found, returns empty to allow other TokenReaders (Authorization header,
     * query parameter for SSE) to be tried.
     *
     * @param request The HTTP request
     * @return Optional containing token string from cookie, or empty if not found
     */
    override fun findToken(request: HttpRequest<*>): Optional<String> {
        val cookies = request.cookies
        val authCookie = cookies.get(AuthCookieService.AUTH_COOKIE_NAME)

        if (authCookie != null && StringUtils.isNotEmpty(authCookie.value)) {
            return Optional.of(authCookie.value)
        }

        return Optional.empty()
    }
}
