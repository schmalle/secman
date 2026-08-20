package com.secman.config

import com.secman.service.EolAdminService
import io.micronaut.context.annotation.Requires
import io.micronaut.context.event.ApplicationEventListener
import io.micronaut.runtime.event.ApplicationStartupEvent
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory

/**
 * Clears EOL sync runs left RUNNING by a previous process on startup.
 *
 * At most one EOL sync may be live at a time, and that guard reads the
 * `eol_sync_run` table. A backend that stops mid-run leaves its row RUNNING
 * with no worker behind it, so without this every subsequent trigger is
 * deferred to a dead run — the CLI polls it forever and reports a hang.
 *
 * See [EolAdminService.reclaimRunsOrphanedByRestart] for why age is the wrong
 * test here and for the single-instance assumption this relies on.
 */
@Requires(notEnv = ["cli"])
@Singleton
open class EolSyncRunReclaimer(
    private val eolAdminService: EolAdminService
) : ApplicationEventListener<ApplicationStartupEvent> {

    private val log = LoggerFactory.getLogger(EolSyncRunReclaimer::class.java)

    override fun onApplicationEvent(event: ApplicationStartupEvent) {
        try {
            val reclaimed = eolAdminService.reclaimRunsOrphanedByRestart()
            if (reclaimed > 0) {
                log.warn("Reclaimed {} EOL sync run(s) orphaned by a restart", reclaimed)
            }
        } catch (e: Exception) {
            // Never block startup on this: a failure here costs one hour of
            // EOL syncing (reclaimStaleRuns still catches it), while a thrown
            // exception would cost the whole application (§A09 - logged, not
            // swallowed silently).
            log.error("Could not reclaim orphaned EOL sync runs at startup", e)
        }
    }
}
