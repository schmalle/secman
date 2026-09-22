package com.secman.service.mcp

import com.secman.controller.DelegationContext
import com.secman.domain.Criticality
import com.secman.domain.McpApiKey
import com.secman.domain.McpPermission
import com.secman.domain.User
import com.secman.domain.Workgroup
import com.secman.repository.AssetRepository
import com.secman.repository.UserRepository
import com.secman.service.AssetFilterService
import com.secman.testutil.TestDataFactory
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.Optional

class McpAccessControlServiceTest {

    private val assetRepository = mockk<AssetRepository>()
    private val userRepository = mockk<UserRepository>()
    private val assetFilterService = mockk<AssetFilterService>()
    private val service = McpAccessControlService(assetRepository, userRepository, assetFilterService)

    @Test
    fun `disabled-only workgroup asset is absent from delegated context`() {
        val disabled = Workgroup(
            id = 71L,
            name = "disabled",
            criticality = Criticality.MEDIUM,
            enabled = false
        )
        every { userRepository.findByIdWithWorkgroups(42L) } returns Optional.of(
            user(roles = mutableSetOf(User.Role.USER), workgroups = mutableSetOf(disabled))
        )
        stubNoAccessibleAssets()

        val context = service.buildExecutionContext(apiKey(), delegation())

        assertThat(context.accessibleAssetIds).isEmpty()
        assertThat(context.accessibleWorkgroupIds).isEmpty()
        assertThat(context.canAccessAsset(700L)).isFalse()
        verify(exactly = 1) {
            assetFilterService.getAccessibleAssetIds(any())
        }
    }

    @Test
    fun `security champion keeps universal asset access without admin privileges`() {
        every { userRepository.findByIdWithWorkgroups(42L) } returns Optional.of(
            user(roles = mutableSetOf(User.Role.USER, User.Role.SECCHAMPION))
        )
        every { assetRepository.findAllIds() } returns listOf(700L, 701L)

        val context = service.buildExecutionContext(apiKey(), delegation())

        assertThat(context.isAdmin).isFalse()
        assertThat(context.accessibleAssetIds).containsExactlyInAnyOrder(700L, 701L)
        assertThat(context.canAccessAsset(700L)).isTrue()
    }

    @Test
    fun `admin keeps universal access without materializing asset ids`() {
        every { userRepository.findByIdWithWorkgroups(42L) } returns Optional.of(
            user(roles = mutableSetOf(User.Role.USER, User.Role.ADMIN))
        )

        val context = service.buildExecutionContext(apiKey(), delegation())

        assertThat(context.isAdmin).isTrue()
        assertThat(context.accessibleAssetIds).isNull()
        assertThat(context.canAccessAsset(700L)).isTrue()
        verify(exactly = 0) { assetRepository.findAllIds() }
    }

    private fun stubNoAccessibleAssets() {
        every { assetFilterService.getAccessibleAssetIds(any()) } returns emptySet()
    }

    @Test
    fun `each MCP request recomputes visibility after revocation`() {
        every { userRepository.findByIdWithWorkgroups(42L) } returns Optional.of(user(mutableSetOf(User.Role.USER)))
        every { assetFilterService.getAccessibleAssetIds(any()) } returnsMany listOf(setOf(700L), emptySet())
        assertThat(service.buildExecutionContext(apiKey(), delegation()).canAccessAsset(700L)).isTrue()
        assertThat(service.buildExecutionContext(apiKey(), delegation()).canAccessAsset(700L)).isFalse()
    }

    private fun user(
        roles: MutableSet<User.Role>,
        workgroups: MutableSet<Workgroup> = mutableSetOf()
    ) = User(
        id = 42L,
        username = "user",
        email = "user@example.test",
        passwordHash = TestDataFactory.createRegularUser().passwordHash,
        roles = roles,
        workgroups = workgroups
    )

    private fun apiKey() = McpApiKey(
        id = 5L,
        keyId = "test-key-identifier",
        keyHash = "not-used",
        name = "test-key",
        userId = 1L,
        permissions = McpPermission.ASSETS_READ.name,
        delegationEnabled = true
    )

    private fun delegation() = DelegationContext(
        delegatedUserEmail = "user@example.test",
        delegatedUserId = 42L,
        effectivePermissions = setOf(McpPermission.ASSETS_READ)
    )
}
