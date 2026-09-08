package com.secman.controller

import com.secman.dto.*
import com.secman.service.*
import io.micronaut.core.annotation.Nullable
import io.micronaut.http.HttpResponse
import io.micronaut.http.annotation.*
import io.micronaut.scheduling.TaskExecutors
import io.micronaut.scheduling.annotation.ExecuteOn
import io.micronaut.security.annotation.Secured
import io.micronaut.security.authentication.Authentication
import io.micronaut.security.rules.SecurityRule

@Controller("/api/integrations/v1")
@Secured(SecurityRule.IS_AUTHENTICATED)
@ExecuteOn(TaskExecutors.BLOCKING)
open class IntegrationController(
    private val admin: IntegrationAdminService,
    private val reads: IntegrationReadService,
    private val scans: IntegrationScanService,
    private val health: IntegrationHealthNotifier
) {
    @Get("/scanners")
    open fun scanners(authentication: Authentication) = reads.scanners(authentication)

    @Post("/scanners")
    @Secured("ADMIN")
    open fun createScanner(@Body request: IntegrationScannerRequest, authentication: Authentication) =
        admin.saveScanner(null, request, authentication)

    @Put("/scanners/{id}")
    @Secured("ADMIN")
    open fun updateScanner(@PathVariable id: Long, @Body request: IntegrationScannerRequest, authentication: Authentication) =
        admin.saveScanner(id, request, authentication)

    @Post("/scanners/{id}/subjects")
    @Secured("ADMIN")
    open fun bind(@PathVariable id: Long, @Body request: IntegrationSubjectRequest, authentication: Authentication) =
        reads.subject(admin.bind(id, request, authentication), authentication)

    @Get("/scanners/{id}/subjects")
    open fun subjects(@PathVariable id: Long, @QueryValue(defaultValue = "0") page: Int,
                      @QueryValue(defaultValue = "100") size: Int, authentication: Authentication) =
        reads.subjects(id, page, size, authentication)

    @Post("/runs")
    open fun submit(@Body request: IntegrationRunRequest, authentication: Authentication) =
        scans.submit(request, authentication).also(health::completed)

    @Get("/summary")
    open fun summary(authentication: Authentication) = reads.summary(authentication)

    @Get("/runs/{id}")
    open fun run(@PathVariable id: Long, authentication: Authentication) = reads.run(id, authentication)

    @Get("/runs")
    open fun runs(@QueryValue(defaultValue = "0") page: Int, @QueryValue(defaultValue = "100") size: Int,
                  @Nullable @QueryValue scannerId: Long?, @Nullable @QueryValue subjectId: Long?, authentication: Authentication) =
        reads.runs(page, size, scannerId, subjectId, authentication)

    @Get("/findings")
    open fun findings(@QueryValue(defaultValue = "0") page: Int, @QueryValue(defaultValue = "100") size: Int,
                      @Nullable @QueryValue scannerId: Long?, @Nullable @QueryValue subjectId: Long?,
                      @Nullable @QueryValue githubRepositoryId: Long?, @Nullable @QueryValue source: String?,
                      @Nullable @QueryValue owner: String?, @Nullable @QueryValue severity: String?,
                      @Nullable @QueryValue state: String?, @Nullable @QueryValue search: String?, authentication: Authentication) =
        reads.findings(page, size, IntegrationFindingFilter(scannerId, subjectId, githubRepositoryId, source, owner, severity, state, search), authentication)

    @Get("/findings/{id}")
    open fun finding(@PathVariable id: Long, authentication: Authentication) = reads.finding(id, authentication)

    @Get("/findings/{id}/attachments/{attachmentId}")
    open fun attachment(@PathVariable id: Long, @PathVariable attachmentId: Long, authentication: Authentication): HttpResponse<ByteArray> {
        val attachment = reads.attachment(id, attachmentId, authentication)
        return HttpResponse.ok(attachment.content).contentType(attachment.contentType)
            .header("X-Content-Type-Options", "nosniff")
            .header("Content-Disposition", "attachment; filename=\"" + attachment.fileName + "\"")
            .header("Cache-Control", "private, no-store")
    }
}
