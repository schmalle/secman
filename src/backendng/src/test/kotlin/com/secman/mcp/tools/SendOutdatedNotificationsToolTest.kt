package com.secman.mcp.tools

import com.secman.domain.OutdatedAssetMaterializedView
import com.secman.domain.UserMapping
import com.secman.dto.mcp.McpExecutionContext
import com.secman.repository.AssetRepository
import com.secman.repository.OutdatedAssetMaterializedViewRepository
import com.secman.repository.UserMappingRepository
import com.secman.service.NotificationService
import com.secman.testutil.TestDataFactory
import io.micronaut.data.model.Page
import io.micronaut.data.model.Pageable
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.Optional

class SendOutdatedNotificationsToolTest {
    @Test
    fun `reminders exclude stale missing and future sensor contact despite fresh ingestion`() = runBlocking<Unit> {
        val notifications = mockk<NotificationService>()
        val outdated = mockk<OutdatedAssetMaterializedViewRepository>()
        val assets = mockk<AssetRepository>()
        val mappings = mockk<UserMappingRepository>()
        val context = mockk<McpExecutionContext>()
        every { context.hasDelegation() } returns true
        every { context.isAdmin } returns true
        val now = LocalDateTime.now(ZoneOffset.UTC)
        val contacts = listOf(now.minusHours(1), now.minusDays(4), null, now.plusDays(1))
        val views = contacts.mapIndexed { index, seenAt ->
            val id = index.toLong() + 1
            val asset = TestDataFactory.createAsset(name = "asset-$id", owner = "account").apply {
                this.id = id
                crowdStrikeAgentSeenAt = seenAt
                crowdStrikeLastImportedAt = now
            }
            every { assets.findByIdWithWorkgroups(id) } returns Optional.of(asset)
            OutdatedAssetMaterializedView(
                assetId = id, assetName = asset.name, assetType = asset.type,
                totalOverdueCount = 1, highCount = 1, oldestVulnDays = 60
            )
        }
        every { outdated.findOutdatedAssets(null, null, null, null, any()) } returns
            Page.of(views, Pageable.unpaged(), views.size.toLong())
        every { mappings.findByAwsAccountId("account") } returns
            listOf(UserMapping(email = "owner@example.test", awsAccountId = "account", domain = null))
        val selected = slot<List<NotificationService.OutdatedAssetData>>()
        every { notifications.processOutdatedAssets(capture(selected), true) } returns
            NotificationService.ProcessingResult(1, 0, 0, emptyList())

        val result = SendOutdatedNotificationsTool(notifications, outdated, assets, mappings)
            .execute(mapOf("dryRun" to true), context)

        assertThat(result).isInstanceOf(McpToolResult.Success::class.java)
        assertThat(selected.captured.map { it.assetId }).containsExactly(1L)
    }
}
