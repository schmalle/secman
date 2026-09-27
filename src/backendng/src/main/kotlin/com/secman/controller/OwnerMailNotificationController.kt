package com.secman.controller

import com.secman.service.OwnerMailDeliveryService
import io.micronaut.http.HttpResponse
import io.micronaut.http.HttpStatus
import io.micronaut.http.annotation.*
import io.micronaut.security.annotation.Secured
import io.micronaut.security.authentication.Authentication

@Controller("/api/admin/owner-mail-notifications")
@Secured("ADMIN")
@io.micronaut.scheduling.annotation.ExecuteOn(io.micronaut.scheduling.TaskExecutors.BLOCKING)
class OwnerMailNotificationController(private val service: OwnerMailDeliveryService) {
    @Get("{?page,pageSize}")
    fun list(@QueryValue(defaultValue = "0") page: Int, @QueryValue(defaultValue = "20") pageSize: Int): HttpResponse<*> =
        try { HttpResponse.ok(service.list(page, pageSize)) }
        catch (e: IllegalArgumentException) { HttpResponse.badRequest(mapOf("error" to "Invalid page or pageSize")) }

    @Post("/{id}/retry")
    fun retry(id: Long, authentication: Authentication): HttpResponse<*> {
        val actor = authentication.attributes["userId"]?.toString()?.toLongOrNull()
            ?: return HttpResponse.status<Any>(HttpStatus.FORBIDDEN)
        return try { HttpResponse.ok(service.retry(id, actor)) }
        catch (e: NoSuchElementException) { HttpResponse.notFound<Any>() }
        catch (e: IllegalStateException) {
            HttpResponse.status<Any>(HttpStatus.CONFLICT).body(mapOf("error" to "Only retained, failed notifications can be retried"))
        }
    }
}
