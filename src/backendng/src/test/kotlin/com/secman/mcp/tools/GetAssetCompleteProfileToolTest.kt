package com.secman.mcp.tools

import com.secman.domain.McpPermission
import com.secman.dto.mcp.McpExecutionContext
import com.secman.repository.AssetRepository
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class GetAssetCompleteProfileToolTest {

    private val assetRepository = mockk<AssetRepository>()
    private val tool = GetAssetCompleteProfileTool(assetRepository)

    @Test
    fun `disabled-workgroup-only asset is rejected before repository access`() = runBlocking<Unit> {
        val result = tool.execute(mapOf("assetId" to 7L), deniedContext())

        assertThat(result.isError).isTrue()
        assertThat((result as McpToolResult.Error).code).isEqualTo("ASSET_NOT_FOUND")
        verify(exactly = 0) { assetRepository.findById(any()) }
    }

    private fun deniedContext() = McpExecutionContext(
        apiKeyId = 1L,
        apiKeyName = GetAssetCompleteProfileTool::class.simpleName!!,
        delegatedUserId = 42L,
        delegatedUserEmail = "member@example.test",
        delegatedUsername = "member",
        delegatedUserRoles = setOf("USER"),
        effectivePermissions = setOf(McpPermission.ASSETS_READ),
        isAdmin = false,
        accessibleAssetIds = emptySet(),
        accessibleWorkgroupIds = emptySet()
    )
}
