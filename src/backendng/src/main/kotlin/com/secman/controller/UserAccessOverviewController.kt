package com.secman.controller

import com.secman.dto.UserAccessOverview
import com.secman.service.UserAccessOverviewService
import io.micronaut.http.HttpResponse
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Get
import io.micronaut.http.annotation.QueryValue
import io.micronaut.scheduling.TaskExecutors
import io.micronaut.scheduling.annotation.ExecuteOn
import io.micronaut.security.annotation.Secured
import io.micronaut.security.authentication.Authentication
import org.slf4j.LoggerFactory

@Controller("/api/users/access-overview")
@Secured("ADMIN")
@ExecuteOn(TaskExecutors.BLOCKING)
class UserAccessOverviewController(private val service: UserAccessOverviewService) {
    @Get
    fun overview(@QueryValue email: String, @QueryValue(defaultValue = "0") page: Int,
                 @QueryValue(defaultValue = "100") size: Int, authentication: Authentication): HttpResponse<UserAccessOverview> {
        val result = service.overview(email, page, size)
        LoggerFactory.getLogger(javaClass).info("User access overview actorId={} targetId={} page={} outcome=success",
            authentication.attributes["userId"], result.user.id, page)
        return HttpResponse.ok(result).header("Cache-Control", "no-store")
    }
}
