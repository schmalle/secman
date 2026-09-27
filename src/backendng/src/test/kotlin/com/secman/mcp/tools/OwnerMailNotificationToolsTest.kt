package com.secman.mcp.tools

import com.secman.dto.mcp.McpExecutionContext
import com.secman.service.OwnerMailDeliveryService
import com.secman.mcp.McpToolPermissions
import com.secman.domain.McpPermission
import io.mockk.every
import io.mockk.Called
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class OwnerMailNotificationToolsTest {
    private val service = mockk<OwnerMailDeliveryService>()
    @Test fun `admin API key cannot bypass the delegated user role`() = runBlocking {
        val context = mockk<McpExecutionContext> {
            every { hasDelegation() } returns true
            every { isAdmin } returns true
            every { delegatedUserRoles } returns setOf("USER")
        }
        for (tool in listOf(ListOwnerMailNotificationsTool(service), RetryOwnerMailNotificationTool(service))) {
            assertTrue(tool.execute(mapOf("notificationId" to 1), context) is McpToolResult.Error)
            assertTrue(McpToolPermissions.LISTING[tool.name]!!.contains(McpPermission.USER_ACTIVITY))
            assertTrue(McpToolPermissions.CALLING[tool.name]!!.contains(McpPermission.USER_ACTIVITY))
        }
        verify { service wasNot Called }
    }
    @Test fun `fractional overflow and string identifiers never select a notification`() = runBlocking {
        val context = mockk<McpExecutionContext> {
            every { hasDelegation() } returns true
            every { delegatedUserRoles } returns setOf("ADMIN")
        }
        for (value in listOf(1.5, Double.POSITIVE_INFINITY, "1", -1, 0)) {
            assertTrue(RetryOwnerMailNotificationTool(service).execute(mapOf("notificationId" to value), context) is McpToolResult.Error)
        }
        for (value in listOf(0.5, Long.MAX_VALUE, "0")) {
            assertTrue(ListOwnerMailNotificationsTool(service).execute(mapOf("page" to value), context) is McpToolResult.Error)
        }
        verify { service wasNot Called }
    }

}
