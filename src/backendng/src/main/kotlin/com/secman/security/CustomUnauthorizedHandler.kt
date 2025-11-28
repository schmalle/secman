package com.secman.security

import io.micronaut.context.annotation.Replaces
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpResponse
import io.micronaut.http.HttpStatus
import io.micronaut.http.MutableHttpResponse
import io.micronaut.http.server.exceptions.ExceptionHandler
import io.micronaut.security.authentication.AuthorizationException
import io.micronaut.security.authentication.DefaultAuthorizationExceptionHandler
import jakarta.inject.Singleton

/**
 * Custom handler for unauthorized requests.
 *
 * This handler returns a 401 response WITHOUT the WWW-Authenticate: Basic header,
 * which prevents browsers from showing the Basic Auth popup dialog.
 *
 * The default Micronaut handler sends WWW-Authenticate: Basic which triggers
 * the browser's native authentication dialog - undesirable for SPA/JWT apps.
 */
@Singleton
@Replaces(DefaultAuthorizationExceptionHandler::class)
class CustomUnauthorizedHandler : ExceptionHandler<AuthorizationException, MutableHttpResponse<*>> {

    override fun handle(request: HttpRequest<*>, exception: AuthorizationException): MutableHttpResponse<*> {
        return if (exception.isForbidden) {
            HttpResponse.status<Any>(HttpStatus.FORBIDDEN)
        } else {
            // Return 401 without WWW-Authenticate header to prevent Basic Auth popup
            HttpResponse.status<Any>(HttpStatus.UNAUTHORIZED)
        }
    }
}
