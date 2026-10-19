package com.secman.controller

import com.secman.service.CrowdStrikeReconciliationPreviewService
import io.micronaut.http.HttpResponse
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Get
import io.micronaut.http.annotation.QueryValue
import io.micronaut.scheduling.TaskExecutors
import io.micronaut.scheduling.annotation.ExecuteOn
import io.micronaut.security.annotation.Secured

@Controller("/api/crowdstrike/snapshots")
@Secured("ADMIN")
@ExecuteOn(TaskExecutors.BLOCKING)
class CrowdStrikeReconciliationController(private val previewService: CrowdStrikeReconciliationPreviewService) {
    @Get("/reconciliation")
    fun preview(@QueryValue(defaultValue = "0") afterId: Long, @QueryValue(defaultValue = "100") limit: Int): HttpResponse<*> {
        if (afterId < 0 || limit !in 1..100) return HttpResponse.badRequest(mapOf("error" to "Invalid reconciliation page"))
        val candidates = previewService.preview(afterId, limit)
        return HttpResponse.ok(mapOf("candidates" to candidates, "nextAfterId" to candidates.lastOrNull()?.assetId,
            "hasMore" to (candidates.size == limit)))
    }
}
