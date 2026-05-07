package com.secman.service

import io.micronaut.cache.CacheManager
import io.micronaut.cache.SyncCache
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory

/**
 * Invalidates the `mcp_accessible_assets` cache after any write that can
 * change which assets a user can reach through the MCP interface. Without
 * this, the 5-minute TTL on the cache would mask freshly granted (or
 * revoked) access — a security-relevant staleness window that matters for
 * AWS account sharing, user mappings, workgroup membership, and any asset
 * mutation that touches the access criteria (cloudAccountId / adDomain /
 * owner / manualCreator / scanUploader).
 *
 * Centralised so every write path has a single obvious call to make.
 */
@Singleton
class McpAccessibleAssetsCacheInvalidator(
    private val cacheManager: CacheManager<Any>
) {
    private val log = LoggerFactory.getLogger(McpAccessibleAssetsCacheInvalidator::class.java)

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
        const val CACHE_NAME = "mcp_accessible_assets"
    }
}
