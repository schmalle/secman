package com.secman.filter

import com.secman.service.ActiveUserTracker
import io.micronaut.http.HttpRequest
import io.micronaut.http.MutableHttpResponse
import io.micronaut.http.annotation.Filter
import io.micronaut.http.filter.HttpServerFilter
import io.micronaut.http.filter.ServerFilterChain
import io.micronaut.http.filter.ServerFilterPhase
import io.micronaut.security.authentication.Authentication
import org.reactivestreams.Publisher

@Filter("/api/**")
class ActiveUserTrackingFilter(
    private val activeUserTracker: ActiveUserTracker
) : HttpServerFilter {

    override fun doFilter(request: HttpRequest<*>, chain: ServerFilterChain): Publisher<MutableHttpResponse<*>> {
        request.getUserPrincipal(Authentication::class.java)
            .ifPresent { authentication -> activeUserTracker.markActive(authentication.name) }

        return chain.proceed(request)
    }

    override fun getOrder(): Int {
        return ServerFilterPhase.SECURITY.order() + 10
    }
}
