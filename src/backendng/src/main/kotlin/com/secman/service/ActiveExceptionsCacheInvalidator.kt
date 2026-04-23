package com.secman.service

import io.micronaut.cache.CacheManager
import io.micronaut.cache.SyncCache
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory

/**
 * Invalidates the `active_exceptions` cache after any write that can change
 * which exceptions are active. Centralised so every write path has a single
 * obvious call to make; prevents the 5-minute TTL from masking freshly
 * approved/rejected/created/deleted/expired exceptions.
 */
@Singleton
class ActiveExceptionsCacheInvalidator(
    private val cacheManager: CacheManager<Any>
) {
    private val log = LoggerFactory.getLogger(ActiveExceptionsCacheInvalidator::class.java)

    fun invalidate() {
        try {
            val cache = cacheManager.getCache(CACHE_NAME)
            when (cache) {
                is SyncCache<*> -> cache.invalidateAll()
                else -> cache.invalidateAll()
            }
            log.debug("Invalidated cache '{}'", CACHE_NAME)
        } catch (e: Exception) {
            log.warn("Failed to invalidate cache '{}': {}", CACHE_NAME, e.message)
        }
    }

    companion object {
        const val CACHE_NAME = "active_exceptions"
    }
}
