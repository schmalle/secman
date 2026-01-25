package com.secman.filter

import io.micronaut.http.HttpRequest
import io.micronaut.http.MutableHttpResponse
import io.micronaut.http.annotation.Filter
import io.micronaut.http.filter.HttpServerFilter
import io.micronaut.http.filter.ServerFilterChain
import io.micronaut.http.filter.ServerFilterPhase
import org.reactivestreams.Publisher
import reactor.core.publisher.Flux
import org.slf4j.LoggerFactory

/**
 * Security headers filter to add comprehensive security headers to all responses.
 *
 * SECURITY DOCUMENTATION:
 *
 * Content Security Policy (CSP) Configuration:
 * - 'unsafe-inline' is REQUIRED for Astro's hydration scripts and React islands
 * - Full nonce-based CSP migration requires frontend changes:
 *   1. Backend: Generate per-request nonce and pass to templates
 *   2. Frontend: Add nonce="..." to all <script> and <style> tags
 *   3. Update Astro config to support nonce injection
 * - Current mitigation: strict-dynamic is NOT used because it requires nonces
 *
 * To migrate to nonce-based CSP (recommended for higher security):
 * 1. Create NonceProvider singleton that generates cryptographic nonces
 * 2. Add nonce to response attributes in this filter
 * 3. Update Astro/React to read nonce from response context
 * 4. Replace 'unsafe-inline' with 'nonce-{value}'
 */
@Filter("/**")
class SecurityHeadersFilter : HttpServerFilter {

    private val logger = LoggerFactory.getLogger(SecurityHeadersFilter::class.java)

    companion object {
        // Content Security Policy - Hardened policy to prevent XSS
        // SECURITY: 'unsafe-inline' is required for Astro framework compatibility
        // See class documentation for nonce-based CSP migration plan
        private const val CSP_POLICY = "default-src 'self'; " +
            "script-src 'self' 'unsafe-inline' https://cdn.jsdelivr.net; " +  // Removed unsafe-eval
            "style-src 'self' 'unsafe-inline' https://cdn.jsdelivr.net; " +
            "img-src 'self' data: https:; " +
            "font-src 'self' data: https://cdn.jsdelivr.net; " +
            "connect-src 'self'; " +
            "frame-ancestors 'none'; " +
            "form-action 'self'; " +
            "base-uri 'self'; " +
            "object-src 'none'; " +
            "upgrade-insecure-requests"

        // Permissions Policy (formerly Feature Policy) - disable all risky APIs
        private const val PERMISSIONS_POLICY = "geolocation=(), " +
            "microphone=(), " +
            "camera=(), " +
            "payment=(), " +
            "usb=(), " +
            "magnetometer=(), " +
            "gyroscope=(), " +
            "accelerometer=()"

        // Cross-Origin policies for additional isolation
        private const val CROSS_ORIGIN_EMBEDDER_POLICY = "require-corp"
        private const val CROSS_ORIGIN_OPENER_POLICY = "same-origin"
        private const val CROSS_ORIGIN_RESOURCE_POLICY = "same-origin"
    }
    
    override fun doFilter(request: HttpRequest<*>, chain: ServerFilterChain): Publisher<MutableHttpResponse<*>> {
        return Flux.from(chain.proceed(request))
            .map { response ->
                addSecurityHeaders(response, request)
                response
            }
    }
    
    private fun addSecurityHeaders(response: MutableHttpResponse<*>, request: HttpRequest<*>) {
        // Prevent clickjacking attacks
        response.header("X-Frame-Options", "DENY")
        
        // Prevent MIME type sniffing
        response.header("X-Content-Type-Options", "nosniff")
        
        // Enable XSS protection in older browsers
        response.header("X-XSS-Protection", "1; mode=block")
        
        // Control referrer information
        response.header("Referrer-Policy", "strict-origin-when-cross-origin")
        
        // Content Security Policy
        response.header("Content-Security-Policy", CSP_POLICY)

        // Permissions Policy
        response.header("Permissions-Policy", PERMISSIONS_POLICY)

        // Cross-Origin Isolation headers for defense-in-depth
        // These headers prevent various cross-origin attacks
        response.header("Cross-Origin-Opener-Policy", CROSS_ORIGIN_OPENER_POLICY)
        response.header("Cross-Origin-Resource-Policy", CROSS_ORIGIN_RESOURCE_POLICY)
        
        // Strict Transport Security (HSTS) - only for HTTPS
        if (request.uri.scheme == "https") {
            response.header("Strict-Transport-Security", "max-age=31536000; includeSubDomains; preload")
        }
        
        // Remove server header to avoid information disclosure
        response.headers.remove("Server")
        response.headers.remove("X-Powered-By")
        
        // Add custom security header
        response.header("X-Security-Policy", "enabled")
        
        // Cache control for sensitive data (API endpoints)
        if (request.uri.path.startsWith("/api/")) {
            response.header("Cache-Control", "no-store, no-cache, must-revalidate, private")
            response.header("Pragma", "no-cache")
            response.header("Expires", "0")
        }

        // AGGRESSIVE cache control for OAuth endpoints to prevent "state" errors
        // in corporate AAD environments where cached responses cause state mismatches
        if (request.uri.path.startsWith("/oauth/")) {
            response.header("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0")
            response.header("Pragma", "no-cache")
            response.header("Expires", "0")
        }
    }
    
    override fun getOrder(): Int {
        // Run early in the filter chain
        return ServerFilterPhase.SECURITY.order()
    }
}