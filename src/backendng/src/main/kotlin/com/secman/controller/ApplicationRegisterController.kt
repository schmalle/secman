package com.secman.controller

import com.secman.dto.ApplicationRegisterAssetUpdateRequest
import com.secman.dto.ApplicationRegisterDetail
import com.secman.dto.ApplicationRegisterRequest
import com.secman.dto.ApplicationRegisterSummary
import com.secman.service.ApplicationRegisterService
import io.micronaut.http.HttpResponse
import io.micronaut.http.annotation.Body
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Delete
import io.micronaut.http.annotation.Get
import io.micronaut.http.annotation.PathVariable
import io.micronaut.http.annotation.Post
import io.micronaut.http.annotation.Put
import io.micronaut.http.annotation.QueryValue
import io.micronaut.security.annotation.Secured
import io.micronaut.security.authentication.Authentication
import io.micronaut.security.rules.SecurityRule

@Controller("/api/applications")
open class ApplicationRegisterController(
    private val service: ApplicationRegisterService
) {

    @Get
    @Secured(SecurityRule.IS_AUTHENTICATED)
    open fun list(@QueryValue(defaultValue = "") search: String?): HttpResponse<List<ApplicationRegisterSummary>> {
        return HttpResponse.ok(service.list(search))
    }

    @Get("/{id}")
    @Secured(SecurityRule.IS_AUTHENTICATED)
    open fun get(@PathVariable id: Long): HttpResponse<ApplicationRegisterDetail> {
        return try {
            HttpResponse.ok(service.get(id))
        } catch (e: NoSuchElementException) {
            HttpResponse.notFound()
        }
    }

    @Post
    @Secured("ADMIN", "SECCHAMPION")
    open fun create(
        @Body request: ApplicationRegisterRequest,
        authentication: Authentication
    ): HttpResponse<Any> {
        return try {
            HttpResponse.created(service.create(request, authentication.name))
        } catch (e: IllegalArgumentException) {
            HttpResponse.badRequest(mapOf("error" to e.message))
        }
    }

    @Put("/{id}")
    @Secured("ADMIN", "SECCHAMPION")
    open fun update(
        @PathVariable id: Long,
        @Body request: ApplicationRegisterRequest,
        authentication: Authentication
    ): HttpResponse<Any> {
        return try {
            HttpResponse.ok(service.update(id, request, authentication.name))
        } catch (e: NoSuchElementException) {
            HttpResponse.notFound()
        } catch (e: IllegalArgumentException) {
            HttpResponse.badRequest(mapOf("error" to e.message))
        }
    }

    @Delete("/{id}")
    @Secured("ADMIN", "SECCHAMPION")
    open fun delete(@PathVariable id: Long): HttpResponse<Void> {
        return try {
            service.delete(id)
            HttpResponse.noContent()
        } catch (e: NoSuchElementException) {
            HttpResponse.notFound()
        }
    }

    @Put("/{id}/assets")
    @Secured("ADMIN", "SECCHAMPION")
    open fun replaceAssets(
        @PathVariable id: Long,
        @Body request: ApplicationRegisterAssetUpdateRequest,
        authentication: Authentication
    ): HttpResponse<Any> {
        return try {
            HttpResponse.ok(service.replaceAssets(id, request.assetIds, authentication.name))
        } catch (e: NoSuchElementException) {
            HttpResponse.notFound()
        } catch (e: IllegalArgumentException) {
            HttpResponse.badRequest(mapOf("error" to e.message))
        }
    }
}
