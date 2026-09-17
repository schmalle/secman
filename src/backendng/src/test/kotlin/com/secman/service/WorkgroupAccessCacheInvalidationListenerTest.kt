package com.secman.service

import com.secman.domain.WorkgroupAccessChangedEvent
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test

class WorkgroupAccessCacheInvalidationListenerTest {

    @Test
    fun `committed workgroup access change invalidates MCP asset cache`() {
        val invalidator = mockk<McpAccessibleAssetsCacheInvalidator>(relaxed = true)
        val listener = WorkgroupAccessCacheInvalidationListener(invalidator)

        listener.onAccessChanged(WorkgroupAccessChangedEvent(setOf(11L)))

        verify(exactly = 1) { invalidator.invalidate() }
    }
}
