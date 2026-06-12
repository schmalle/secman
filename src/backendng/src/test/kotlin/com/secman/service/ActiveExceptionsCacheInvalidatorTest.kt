package com.secman.service

import io.micronaut.cache.CacheManager
import io.micronaut.cache.SyncCache
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test

class ActiveExceptionsCacheInvalidatorTest {

    @Test
    fun `invalidates active exceptions and not excepted count caches`() {
        val cacheManager = mockk<CacheManager<Any>>()
        val activeExceptionsCache = mockk<SyncCache<Any>>(relaxed = true)
        val notExceptedCountCache = mockk<SyncCache<Any>>(relaxed = true)
        every { cacheManager.getCache("active_exceptions") } returns activeExceptionsCache
        every { cacheManager.getCache("vulnerability_not_excepted_count") } returns notExceptedCountCache

        ActiveExceptionsCacheInvalidator(cacheManager).invalidate()

        verify(exactly = 1) { activeExceptionsCache.invalidateAll() }
        verify(exactly = 1) { notExceptedCountCache.invalidateAll() }
    }
}
