package com.secman.filter

import io.micronaut.context.annotation.Value
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpResponse
import io.micronaut.http.HttpStatus
import io.micronaut.http.MutableHttpResponse
import io.micronaut.http.annotation.Filter
import io.micronaut.http.filter.HttpServerFilter
import io.micronaut.http.filter.ServerFilterChain
import io.micronaut.http.filter.ServerFilterPhase
import org.reactivestreams.Publisher
import org.slf4j.LoggerFactory
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

/**
 * Origin validation filter for MCP Streamable HTTP Transport.
 *
 * Per MCP specification, servers MUST validate the Origin header when present
 * to prevent CSRF attacks from malicious websites.
 *
 * - Requests without Origin header are allowed (non-browser clients like Claude Desktop)
 * - Requests with Origin header are validated against allowed origins list
 * - Localhost origins are always allowed for development
 *
 * @see https://modelcontextprotocol.io/specification/2024-11-05/basic/transports
 */
@Filter("/mcp/**")
class McpOriginValidationFilter : HttpServerFilter {

    private val logger = LoggerFactory.getLogger(McpOriginValidationFilter::class.java)

    @Value("\${secman.mcp.transport.allowed-origins:}")
    private var allowedOrigins: List<String> = emptyList()

    @Value("\${secman.mcp.transport.validate-origin:true}")
    private var validateOrigin: Boolean = true

    companion object {
        // Localhost patterns that are always allowed for development
        private val LOCALHOST_PATTERNS = listOf(
            Regex("^https?://localhost(:\\d+)?$"),
            Regex("^https?://127\\.0\\.0\\.1(:\\d+)?$"),
            Regex("^https?://\\[::1\\](:\\d+)?$")
        )
    }

    override fun doFilter(request: HttpRequest<*>, chain: ServerFilterChain): Publisher<MutableHttpResponse<*>> {
        val origin = request.headers.get("Origin")

        // No Origin header - allow (non-browser clients)
        if (origin == null) {
            logger.debug("MCP request without Origin header - allowed (non-browser client)")
            return chain.proceed(request)
        }

        // Origin validation disabled - allow all
        if (!validateOrigin) {
            logger.debug("MCP origin validation disabled - allowing: {}", origin)
            return chain.proceed(request)
        }

        // Check if origin is allowed
        if (isOriginAllowed(origin)) {
            logger.debug("MCP request from allowed origin: {}", origin)
            return chain.proceed(request)
        }

        // Origin not allowed - reject request
        logger.warn("MCP request from disallowed origin rejected: {}", origin)
        return Mono.just(
            HttpResponse.status<Map<String, Any>>(HttpStatus.FORBIDDEN)
                .body(mapOf(
                    "jsonrpc" to "2.0",
                    "id" to null,
                    "error" to mapOf(
                        "code" to -32003,
                        "message" to "Origin not allowed: $origin"
                    )
                ))
        )
    }

    /**
     * Check if the given origin is allowed.
     */
    private fun isOriginAllowed(origin: String): Boolean {
        // Localhost is always allowed for development
        if (LOCALHOST_PATTERNS.any { it.matches(origin) }) {
            return true
        }

        // Check against configured allowed origins
        if (allowedOrigins.isEmpty()) {
            // No explicit origins configured - allow all (backwards compatible)
            return true
        }

        return origin in allowedOrigins
    }

    override fun getOrder(): Int {
        // Run early in the filter chain, before authentication
        return ServerFilterPhase.SECURITY.order() - 10
    }
}
