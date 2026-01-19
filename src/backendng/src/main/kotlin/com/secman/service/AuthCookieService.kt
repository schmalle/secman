package com.secman.service

import io.micronaut.http.cookie.Cookie
import io.micronaut.http.cookie.SameSite
import jakarta.inject.Singleton
import java.time.Duration

/**
 * Service for creating authentication cookies.
 *
 * Provides consistent cookie creation for both local login (AuthController)
 * and OAuth login (OAuthController) to ensure the same HttpOnly secure cookie
 * is used across all authentication methods.
 */
@Singleton
class AuthCookieService(
    @io.micronaut.context.annotation.Value("\${secman.auth.cookie-secure:true}")
    private val cookieSecure: Boolean
) {
    companion object {
        const val AUTH_COOKIE_NAME = "secman_auth"
        private val AUTH_COOKIE_MAX_AGE = Duration.ofHours(8)
    }

    /**
     * Create HttpOnly secure cookie for JWT token.
     * Security properties:
     * - HttpOnly: Prevents JavaScript access (XSS protection)
     * - Secure: Only sent over HTTPS (configurable for development)
     * - SameSite=Lax: CSRF protection while allowing navigation
     * - Path=/: Available for all API endpoints
     *
     * Note: Set SECMAN_AUTH_COOKIE_SECURE=false for local development over HTTP
     */
    fun createAuthCookie(token: String): Cookie {
        return Cookie.of(AUTH_COOKIE_NAME, token)
            .httpOnly(true)
            .secure(cookieSecure)
            .sameSite(SameSite.Lax)
            .maxAge(AUTH_COOKIE_MAX_AGE)
            .path("/")
    }

    /**
     * Create expired cookie to clear authentication.
     */
    fun createLogoutCookie(): Cookie {
        return Cookie.of(AUTH_COOKIE_NAME, "")
            .httpOnly(true)
            .secure(cookieSecure)
            .sameSite(SameSite.Lax)
            .maxAge(Duration.ZERO)
            .path("/")
    }
}
