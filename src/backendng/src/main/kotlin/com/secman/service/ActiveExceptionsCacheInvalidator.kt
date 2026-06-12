package com.secman.service

import io.micronaut.cache.CacheManager
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory

/**
 * Invalidates caches derived from active exceptions after any write that can change
 * exception coverage. Centralised so every write path has a single obvious call to
 * make; prevents the TTL from masking freshly approved/rejected/created/deleted/
 * expired exceptions.
 */
@Singleton
class ActiveExceptionsCacheInvalidator(
    private val cacheManager: CacheManager<Any>
) {
    private val log = LoggerFactory.getLogger(ActiveExceptionsCacheInvalidator::class.java)

    fun invalidate() {
        CACHE_NAMES.forEach { cacheName ->
            try {
                val cache = cacheManager.getCache(cacheName)
                cache.invalidateAll()
                log.debug("Invalidated cache '{}'", cacheName)
            } catch (e: Exception) {
                log.warn("Failed to invalidate cache '{}': {}", cacheName, e.message)
            }
        }
    }

    companion object {
        const val CACHE_NAME = "active_exceptions"
        const val NOT_EXCEPTED_COUNT_CACHE_NAME = "vulnerability_not_excepted_count"
        private val CACHE_NAMES = listOf(CACHE_NAME, NOT_EXCEPTED_COUNT_CACHE_NAME)
    }
}
