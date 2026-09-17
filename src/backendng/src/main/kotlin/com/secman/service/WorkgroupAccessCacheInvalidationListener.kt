package com.secman.service

import com.secman.domain.WorkgroupAccessChangedEvent
import io.micronaut.transaction.annotation.TransactionalEventListener
import io.micronaut.transaction.annotation.TransactionalEventListener.TransactionPhase
import jakarta.inject.Singleton

@Singleton
open class WorkgroupAccessCacheInvalidationListener(
    private val cacheInvalidator: McpAccessibleAssetsCacheInvalidator
) {
    @TransactionalEventListener(TransactionPhase.AFTER_COMMIT)
    open fun onAccessChanged(@Suppress("UNUSED_PARAMETER") event: WorkgroupAccessChangedEvent) {
        cacheInvalidator.invalidate()
    }
}
