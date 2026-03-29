package com.secman.filter

import io.micronaut.context.annotation.Requires
import io.micronaut.http.HttpRequest
import io.micronaut.http.MutableHttpResponse
import io.micronaut.http.annotation.Filter
import io.micronaut.http.filter.HttpServerFilter
import io.micronaut.http.filter.ServerFilterChain
import io.micronaut.http.filter.ServerFilterPhase
import org.reactivestreams.Publisher
import org.slf4j.LoggerFactory
import java.util.Base64

/**
 * Debug filter that logs all HTTP headers on MCP and API requests.
 *
 * Only active when the environment variable SECMAN_DEBUG is set to "true".
 * Useful for diagnosing header-related issues in dedicated environments
 * (e.g., missing delegation headers, proxy-stripped headers, JWT problems).
 *
 * When a JWT Bearer token is present, the payload claims are decoded and logged
 * alongside the headers (signature is not logged).
 */
@Filter(value = ["/mcp", "/mcp/**", "/api/**"])
@Requires(property = "secman.debug", value = "true")
class McpDebugHeaderFilter : HttpServerFilter {

    private val logger = LoggerFactory.getLogger(McpDebugHeaderFilter::class.java)

    override fun doFilter(request: HttpRequest<*>, chain: ServerFilterChain): Publisher<MutableHttpResponse<*>> {
        if (logger.isDebugEnabled) {
            val headers = request.headers.asMap()
                .entries
                .sortedBy { it.key }
                .joinToString("\n  ") { (name, values) ->
                    if (name.equals("Authorization", ignoreCase = true)) {
                        "$name: [PRESENT - ${values.size} value(s)]"
                    } else {
                        "$name: ${values.joinToString(", ")}"
                    }
                }

            logger.debug(
                "Debug headers [{} {}]:\n  {}",
                request.method,
                request.uri,
                headers
            )

            // Decode JWT claims if Authorization Bearer header is present
            val authHeader = request.headers.get("Authorization")
            if (authHeader != null && authHeader.startsWith("Bearer ", ignoreCase = true)) {
                val token = authHeader.substring(7)
                val claims = decodeJwtPayload(token)
                if (claims != null) {
                    logger.debug("JWT claims [{} {}]: {}", request.method, request.uri, claims)
                } else {
                    logger.debug("JWT claims [{} {}]: [could not decode payload]", request.method, request.uri)
                }
            }
        }

        return chain.proceed(request)
    }

    /**
     * Decode the payload section of a JWT token (base64url-encoded JSON).
     * Returns the raw JSON string of claims, or null if the token is malformed.
     * The signature is never logged.
     */
    private fun decodeJwtPayload(token: String): String? {
        return try {
            val parts = token.split(".")
            if (parts.size < 2) return null
            val payload = parts[1]
            String(Base64.getUrlDecoder().decode(payload), Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    override fun getOrder(): Int {
        // Run before all other filters so we capture the raw inbound headers
        return ServerFilterPhase.FIRST.order()
    }
}
