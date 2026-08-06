package com.secman.exception

import io.micronaut.context.annotation.Requires
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpResponse
import io.micronaut.http.HttpStatus
import io.micronaut.http.annotation.Produces
import io.micronaut.http.server.exceptions.ExceptionHandler
import jakarta.inject.Singleton
import org.hibernate.exception.ConstraintViolationException
import org.slf4j.LoggerFactory

/**
 * Global handler that maps Hibernate ConstraintViolationException (org.hibernate.exception,
 * NOT jakarta.validation) to HTTP 409 Conflict.
 *
 * This is a backstop for TOCTOU races: two concurrent callers can both pass an
 * existsBy* check in the service layer, then one wins the INSERT and the other
 * hits the DB unique constraint (e.g. uk_workgroup_aws_account, uk_aws_sharing_source_target).
 * Without this handler those races surface as 500s; with it they surface as 409s.
 *
 * The optimistic path (service-level DuplicateAccountException / IllegalStateException)
 * still produces 409 directly via the controller's existing catch blocks — this handler
 * only fires for the rare concurrent case.
 */
@Produces
@Singleton
@Requires(classes = [ConstraintViolationException::class, ExceptionHandler::class])
class HibernateConstraintViolationHandler :
    ExceptionHandler<ConstraintViolationException, HttpResponse<Map<String, String>>> {

    private val log = LoggerFactory.getLogger(HibernateConstraintViolationHandler::class.java)

    override fun handle(
        request: HttpRequest<*>,
        exception: ConstraintViolationException
    ): HttpResponse<Map<String, String>> {
        val constraint = exception.constraintName ?: "unknown"
        log.warn(
            "DB constraint violation on {} {}: {} (constraint: {})",
            request.method, request.path, exception.sqlException?.message, constraint
        )
        return HttpResponse.status<Map<String, String>>(HttpStatus.CONFLICT)
            .body(mapOf(
                "error" to "Conflict",
                "message" to "A duplicate entry violates a database constraint.",
                "constraint" to constraint
            ))
    }
}
