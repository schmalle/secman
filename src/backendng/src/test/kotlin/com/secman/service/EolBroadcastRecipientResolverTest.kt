package com.secman.service

import com.secman.domain.Asset
import com.secman.domain.User
import com.secman.domain.UserMapping
import com.secman.repository.AssetRepository
import com.secman.repository.EolFindingRepository
import com.secman.repository.UserMappingRepository
import com.secman.repository.UserRepository
import io.micronaut.security.authentication.Authentication
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.Optional

class EolBroadcastRecipientResolverTest {

    private val eolFindingRepository = mockk<EolFindingRepository>()
    private val assetRepository = mockk<AssetRepository>()
    private val userRepository = mockk<UserRepository>()
    private val userMappingRepository = mockk<UserMappingRepository>()
    private val assetFilterService = mockk<AssetFilterService>()
    private val resolver = EolBroadcastRecipientResolver(
        eolFindingRepository = eolFindingRepository,
        assetRepository = assetRepository,
        userRepository = userRepository,
        userMappingRepository = userMappingRepository,
        assetFilterService = assetFilterService
    )

    @Test
    fun `resolves active users responsible for systems affected by the product`() {
        val owner = user(1, "owner", "owner@example.com", active = true)
        val awsUser = user(3, "aws-user", "aws-user@example.com", active = true)
        val domainUser = user(4, "domain-user", "domain-user@example.com", active = true)
        val manualCreator = user(5, "creator", "creator@example.com", active = true)
        val authentication = adminAuthentication()

        every { assetFilterService.getAccessibleAssetIds(authentication) } returns setOf(201L)
        every {
            eolFindingRepository.findAssetIdsByComponentNameForAssets("Internet Explorer", setOf(201L))
        } returns listOf(201L)
        every { assetRepository.findByIdIn(listOf(201L)) } returns listOf(
            asset(
                owner = "owner",
                cloudAccountId = "123456789012",
                adDomain = "Example.COM",
                manualCreator = manualCreator
            ).also { it.id = 201 }
        )
        every { userRepository.findByUsername("owner") } returns Optional.of(owner)
        every { userMappingRepository.findByAwsAccountId("123456789012") } returns listOf(
            UserMapping(email = "aws-user@example.com", user = awsUser, awsAccountId = "123456789012", domain = null)
        )
        every { userMappingRepository.findByDomain("example.com") } returns listOf(
            UserMapping(email = "domain-user@example.com", user = domainUser, awsAccountId = null, domain = "example.com")
        )

        val recipients = resolver.resolve("Internet Explorer", authentication)

        assertThat(recipients.map { it.user.email })
            .containsExactlyInAnyOrder(
                "owner@example.com",
                "aws-user@example.com",
                "domain-user@example.com",
                "creator@example.com"
            )
    }

    @Test
    fun `secchampion recipients are limited to scoped assets`() {
        val inScopeOwner = user(20, "in-scope-owner", "in-scope@example.com", active = true)
        val authentication = secchampionAuthentication()

        every { assetFilterService.getAccessibleAssetIds(authentication) } returns setOf(201L)
        every {
            eolFindingRepository.findAssetIdsByComponentNameForAssets("Firefox", setOf(201L))
        } returns listOf(201L)
        every { assetRepository.findByIdIn(listOf(201L)) } returns listOf(
            asset(owner = "in-scope-owner").also { it.id = 201 }
        )
        every { userRepository.findByUsername("in-scope-owner") } returns Optional.of(inScopeOwner)

        val recipients = resolver.resolve("Firefox", authentication)

        assertThat(recipients.map { it.user.email }).containsExactly("in-scope@example.com")
    }

    @Test
    fun `no accessible assets returns no recipients without querying findings`() {
        val authentication = secchampionAuthentication()
        every { assetFilterService.getAccessibleAssetIds(authentication) } returns emptySet()

        val recipients = resolver.resolve("Firefox", authentication)

        assertThat(recipients).isEmpty()
    }

    @Test
    fun `inactive owner is excluded from recipients`() {
        val inactiveOwner = user(2, "inactive", "inactive@example.com", active = false)
        val authentication = adminAuthentication()

        every { assetFilterService.getAccessibleAssetIds(authentication) } returns setOf(202L)
        every {
            eolFindingRepository.findAssetIdsByComponentNameForAssets("Internet Explorer", setOf(202L))
        } returns listOf(202L)
        every { assetRepository.findByIdIn(listOf(202L)) } returns listOf(
            asset(owner = "inactive").also { it.id = 202 }
        )
        every { userRepository.findByUsername("inactive") } returns Optional.of(inactiveOwner)

        val recipients = resolver.resolve("Internet Explorer", authentication)

        assertThat(recipients).isEmpty()
    }

    /**
     * The linkage this asserts is what scopes the broadcast body: each recipient's
     * copy of the mail lists only these assets. If owner A's set ever contained
     * owner B's system, the mail would disclose a system A cannot see in the UI
     * (CLAUDE.md §A01), so this is a security assertion, not a data-shape one.
     */
    @Test
    fun `each recipient carries only the systems that linked them to the product`() {
        val ownerA = user(10, "owner-a", "a@example.com", active = true)
        val ownerB = user(11, "owner-b", "b@example.com", active = true)
        val sharedCreator = user(12, "creator", "creator@example.com", active = true)
        val authentication = adminAuthentication()

        every { assetFilterService.getAccessibleAssetIds(authentication) } returns setOf(301L, 302L)
        every {
            eolFindingRepository.findAssetIdsByComponentNameForAssets("Amazon Linux", setOf(301L, 302L))
        } returns listOf(301L, 302L)
        every { assetRepository.findByIdIn(listOf(301L, 302L)) } returns listOf(
            asset(owner = "owner-a", manualCreator = sharedCreator).also { it.id = 301 },
            asset(owner = "owner-b", manualCreator = sharedCreator).also { it.id = 302 }
        )
        every { userRepository.findByUsername("owner-a") } returns Optional.of(ownerA)
        every { userRepository.findByUsername("owner-b") } returns Optional.of(ownerB)

        val byEmail = resolver.resolve("Amazon Linux", authentication).associateBy { it.user.email }

        assertThat(byEmail["a@example.com"]?.assetIds).containsExactly(301L)
        assertThat(byEmail["b@example.com"]?.assetIds).containsExactly(302L)
        // The creator of both systems legitimately sees both.
        assertThat(byEmail["creator@example.com"]?.assetIds).containsExactlyInAnyOrder(301L, 302L)
    }

    private fun user(id: Long, username: String, email: String, active: Boolean): User =
        User(
            id = id,
            username = username,
            email = email,
            passwordHash = "x",
            lastLogin = if (active) Instant.now() else null
        )

    private fun asset(
        owner: String,
        cloudAccountId: String? = null,
        adDomain: String? = null,
        manualCreator: User? = null
    ): Asset =
        Asset(
            name = "host-$owner",
            type = "SERVER",
            owner = owner,
            cloudAccountId = cloudAccountId,
            adDomain = adDomain,
            manualCreator = manualCreator
        )

    private fun adminAuthentication(): Authentication =
        Authentication.build("admin", listOf("ADMIN"), mapOf("userId" to 1L, "email" to "admin@example.com"))

    private fun secchampionAuthentication(): Authentication =
        Authentication.build("champion", listOf("SECCHAMPION"), mapOf("userId" to 2L, "email" to "champion@example.com"))
}
