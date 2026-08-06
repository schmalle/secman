package com.secman.config

import com.secman.service.AssetHeatmapService
import io.micronaut.context.annotation.Requires
import io.micronaut.context.event.ApplicationEventListener
import io.micronaut.runtime.event.ApplicationStartupEvent
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory

/**
 * Auto-populates the asset heatmap on startup when the table is empty.
 *
 * This handles the case where the heatmap feature is deployed but no
 * CrowdStrike import has occurred yet to trigger the first calculation.
 * Without this, the heatmap page shows "No heatmap data available"
 * until a materialized view refresh happens.
 */
@Requires(notEnv = ["cli"])
@Singleton
class AssetHeatmapInitializer(
    private val assetHeatmapService: AssetHeatmapService
) : ApplicationEventListener<ApplicationStartupEvent> {

    private val log = LoggerFactory.getLogger(AssetHeatmapInitializer::class.java)

    override fun onApplicationEvent(event: ApplicationStartupEvent) {
        try {
            if (assetHeatmapService.isEmpty()) {
                log.info("Asset heatmap table is empty — triggering initial calculation")
                val count = assetHeatmapService.recalculateHeatmap()
                log.info("Initial heatmap calculation completed: {} entries", count)
            }
        } catch (e: Exception) {
            log.error("Failed to initialize asset heatmap on startup: {}", e.message, e)
        }
    }
}
