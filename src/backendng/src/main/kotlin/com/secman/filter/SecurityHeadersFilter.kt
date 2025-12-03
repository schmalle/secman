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
 * Security headers filter to add comprehensive security headers to all responses
 */
@Filter("/**")
class SecurityHeadersFilter : HttpServerFilter {
    
    private val logger = LoggerFactory.getLogger(SecurityHeadersFilter::class.java)
    
    companion object {
        // Content Security Policy - Strict policy to prevent XSS
        private const val CSP_POLICY = "default-src 'self'; " +
            "script-src 'self' 'unsafe-inline' 'unsafe-eval' https://cdn.jsdelivr.net; " +
            "style-src 'self' 'unsafe-inline' https://cdn.jsdelivr.net; " +
            "img-src 'self' data: https:; " +
            "font-src 'self' data: https://cdn.jsdelivr.net; " +
            "connect-src 'self'; " +
            "frame-ancestors 'none'; " +
            "form-action 'self'; " +
            "base-uri 'self'; " +
            "object-src 'none'"
        
        // Permissions Policy (formerly Feature Policy)
        private const val PERMISSIONS_POLICY = "geolocation=(), " +
            "microphone=(), " +
            "camera=(), " +
            "payment=(), " +
            "usb=(), " +
            "magnetometer=(), " +
            "gyroscope=(), " +
            "accelerometer=()"
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