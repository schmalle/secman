package com.secman.filter

import io.micronaut.http.HttpMethod
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
import reactor.core.publisher.Mono

/**
 * Custom CORS preflight handler.
 *
 * Micronaut 4.10's built-in CorsFilter adds CORS headers to actual requests
 * but does not correctly handle OPTIONS preflight requests (returns 403 for
 * all origins). This filter intercepts preflight requests and returns the
 * correct CORS preflight response based on the "web" CORS configuration.
 *
 * Runs at FIRST order minus 10 to execute before all other filters including
 * the built-in CorsFilter and McpDebugHeaderFilter.
 */
@Filter("/**")
class CorsPreflightFilter : HttpServerFilter {

    private val logger = LoggerFactory.getLogger(CorsPreflightFilter::class.java)

    companion object {
        private val ALLOWED_ORIGINS = setOf(
            "http://localhost:4321",
            "http://localhost:3000",
            "https://secman.covestro.net",
            "http://secman.covestro.net"
        )

        private val ALLOWED_METHODS = setOf(
            HttpMethod.GET,
            HttpMethod.POST,
            HttpMethod.PUT,
            HttpMethod.DELETE,
            HttpMethod.OPTIONS
        )

        private const val ALLOWED_HEADERS = "Content-Type, Authorization, Accept, Origin, Csrf-Token"
        private const val EXPOSED_HEADERS = "Authorization"
        private const val MAX_AGE = "3600"
    }

    override fun doFilter(request: HttpRequest<*>, chain: ServerFilterChain): Publisher<MutableHttpResponse<*>> {
        // Only handle CORS preflight requests
        if (request.method != HttpMethod.OPTIONS) {
            return chain.proceed(request)
        }

        val origin = request.headers.get("Origin") ?: return chain.proceed(request)
        val requestMethod = request.headers.get("Access-Control-Request-Method") ?: return chain.proceed(request)

        // This is a CORS preflight request
        if (origin !in ALLOWED_ORIGINS) {
            logger.debug("CORS preflight rejected - origin not allowed: {}", origin)
            return Mono.just(HttpResponse.status<Any>(HttpStatus.FORBIDDEN))
        }

        val method = try { HttpMethod.parse(requestMethod) } catch (e: Exception) { null }
        if (method == null || method !in ALLOWED_METHODS) {
            logger.debug("CORS preflight rejected - method not allowed: {}", requestMethod)
            return Mono.just(HttpResponse.status<Any>(HttpStatus.FORBIDDEN))
        }

        logger.debug("CORS preflight allowed: origin={}, method={}", origin, requestMethod)

        val response = HttpResponse.ok<Any>()
        response.header("Access-Control-Allow-Origin", origin)
        response.header("Access-Control-Allow-Methods", ALLOWED_METHODS.joinToString(", ") { it.name })
        response.header("Access-Control-Allow-Headers", ALLOWED_HEADERS)
        response.header("Access-Control-Expose-Headers", EXPOSED_HEADERS)
        response.header("Access-Control-Allow-Credentials", "true")
        response.header("Access-Control-Max-Age", MAX_AGE)
        response.header("Vary", "Origin")

        return Mono.just(response)
    }

    override fun getOrder(): Int {
        // Run before everything else, including the built-in CorsFilter
        return ServerFilterPhase.FIRST.order() - 10
    }
}
