package com.secman.service

import io.micronaut.runtime.http.scope.RequestScope
import io.micronaut.security.authentication.Authentication

/**
 * Per-request cache for the user's accessible-asset-ID set.
 *
 * Why this exists: a single page render of the vulnerability overview can fan
 * out into the badge-count poller, the row count, the row list, exception
 * matching, and statistics. Each currently calls
 * `AssetFilterService.getAccessibleAssets(authentication)`, and that call is a
 * fan-in of multiple SQL queries (workgroup, AWS account mapping, AD-domain
 * mapping, AWS sharing, ownership, workgroup-AWS-account). Recomputing it per
 * dependent query is wasted work — the result is identical for the lifetime of
 * one HTTP request.
 *
 * `@RequestScope` gives us exactly that lifetime: the bean is constructed on
 * first inject and torn down at end of request, so cache pollution between
 * users / requests is structurally impossible.
 *
 * Thread-safety: `@RequestScope` beans live on a single request thread, so we
 * do not need synchronization. (If a request later spawns coroutines or
 * background work, those should not consult this cache; they have a different
 * authentication context anyway.)
 */
@RequestScope
open class AccessibleAssetIdsCache(
    private val assetFilterService: AssetFilterService
) {
    private var cached: Set<Long>? = null
    private var cachedForUserId: Long? = null

    /**
     * Return the user's accessible asset-ID set, computing it on first call
     * within this request and reusing the same result on subsequent calls.
     *
     * The userId guard is defensive: if the same request impersonates a
     * different identity (it should not, but the framework allows
     * authentication.swap-style flows in principle), we recompute rather than
     * serve a cached set for the wrong user.
     */
    fun get(authentication: Authentication): Set<Long> {
        val currentUserId = authentication.attributes["userId"]?.toString()?.toLongOrNull()
        val cachedSnapshot = cached
        if (cachedSnapshot != null && cachedForUserId == currentUserId) {
            return cachedSnapshot
        }
        val ids = assetFilterService.getAccessibleAssets(authentication)
            .mapNotNull { it.id }
            .toSet()
        cached = ids
        cachedForUserId = currentUserId
        return ids
    }
}
