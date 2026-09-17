package com.secman.mcp.tools

import com.secman.domain.Asset
import com.secman.domain.McpPermission
import com.secman.dto.mcp.McpExecutionContext
import com.secman.repository.AssetRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import java.util.Optional

/** Verifies that MCP name edits preserve CrowdStrike source identity and asset scoping. */
class UpdateAssetToolTest {

    private val assetRepository = mockk<AssetRepository>()
    private val tool = UpdateAssetTool(assetRepository)

    private fun context(canAccess: Boolean = true) = McpExecutionContext(
        apiKeyId = 1L,
        apiKeyName = UpdateAssetTool::class.simpleName!!,
        delegatedUserId = 42L,
        delegatedUserEmail = "owner@example.com",
        delegatedUsername = "asset-owner",
        delegatedUserRoles = setOf("USER"),
        effectivePermissions = setOf(McpPermission.ASSETS_WRITE),
        isAdmin = false,
        accessibleAssetIds = if (canAccess) setOf(7L) else emptySet(),
        accessibleWorkgroupIds = emptySet()
    )

    @Test
    fun `editing a name records a user override`() = runBlocking<Unit> {
        val asset = asset()
        every { assetRepository.findById(7L) } returns Optional.of(asset)
        every { assetRepository.save(asset) } returns asset

        val result = tool.execute(mapOf("assetId" to 7L, "name" to "SAP production"), context())

        assertThat(result.isError).isFalse()
        assertThat(asset.name).isEqualTo("SAP production")
        assertThat(asset.crowdStrikeHostname).isEqualTo("server-7.example.com")
        assertThat(asset.nameOverriddenAt).isNotNull()
        assertThat(asset.nameOverriddenBy).isEqualTo("asset-owner")
    }

    @Test
    fun `reset restores the CrowdStrike hostname`() = runBlocking<Unit> {
        val asset = asset().apply {
            overrideName("SAP production", "asset-owner", LocalDateTime.now().minusMinutes(5))
        }
        every { assetRepository.findById(7L) } returns Optional.of(asset)
        every { assetRepository.save(asset) } returns asset

        val result = tool.execute(mapOf("assetId" to 7L, "resetNameToCrowdStrike" to true), context())

        assertThat(result.isError).isFalse()
        assertThat(asset.name).isEqualTo("server-7.example.com")
        assertThat(asset.nameOverriddenAt).isNull()
        assertThat(asset.nameOverriddenBy).isNull()
    }

    @Test
    fun `disabled-workgroup-only asset is rejected by write tool`() = runBlocking<Unit> {
        val result = tool.execute(mapOf("assetId" to 7L, "name" to "hidden"), context(canAccess = false))

        assertThat(result.isError).isTrue()
        assertThat((result as McpToolResult.Error).code).isEqualTo("NOT_FOUND")
        io.mockk.verify(exactly = 0) { assetRepository.findById(any()) }
    }

    private fun asset() = Asset(
        id = 7L,
        name = "server-7.example.com",
        crowdStrikeHostname = "server-7.example.com",
        type = "SERVER",
        owner = "asset-owner"
    )
}
