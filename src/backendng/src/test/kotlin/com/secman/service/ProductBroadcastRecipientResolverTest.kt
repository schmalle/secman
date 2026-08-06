package com.secman.service

import com.secman.domain.Asset
import com.secman.domain.User
import com.secman.domain.UserMapping
import com.secman.repository.AssetRepository
import com.secman.repository.InstalledProductRepository
import com.secman.repository.UserMappingRepository
import com.secman.repository.UserRepository
import io.micronaut.security.authentication.Authentication
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.Optional

class ProductBroadcastRecipientResolverTest {

    private val assetRepository = mockk<AssetRepository>()
    private val installedProductRepository = mockk<InstalledProductRepository>()
    private val userRepository = mockk<UserRepository>()
    private val userMappingRepository = mockk<UserMappingRepository>()
    private val assetFilterService = mockk<AssetFilterService>()
    private val resolver = ProductBroadcastRecipientResolver(
        assetRepository = assetRepository,
        installedProductRepository = installedProductRepository,
        userRepository = userRepository,
        userMappingRepository = userMappingRepository,
        assetFilterService = assetFilterService
    )

    @Test
    fun `resolves active users responsible for assets running the product`() {
        val owner = user(1, "owner", "owner@example.com", active = true)
        val inactiveOwner = user(2, "inactive", "inactive@example.com", active = false)
        val awsUser = user(3, "aws-user", "aws-user@example.com", active = true)
        val domainUser = user(4, "domain-user", "domain-user@example.com", active = true)
        val manualCreator = user(5, "creator", "creator@example.com", active = true)

        every { assetRepository.findAssetsByProductForAllNoLimit("Access 2016") } returns listOf(
            asset(
                owner = "owner",
                cloudAccountId = "123456789012",
                adDomain = "Example.COM",
                manualCreator = manualCreator
            ),
            asset(owner = "inactive")
        )
        every { installedProductRepository.findAssetsByProductName("Access 2016") } returns emptyList()
        every { userRepository.findByUsername("owner") } returns Optional.of(owner)
        every { userRepository.findByUsername("inactive") } returns Optional.of(inactiveOwner)
        every { userMappingRepository.findByAwsAccountId("123456789012") } returns listOf(
            UserMapping(email = "aws-user@example.com", user = awsUser, awsAccountId = "123456789012", domain = null)
        )
        every { userMappingRepository.findByDomain("example.com") } returns listOf(
            UserMapping(email = "domain-user@example.com", user = domainUser, awsAccountId = null, domain = "example.com"),
            UserMapping(email = "owner@example.com", user = owner, awsAccountId = null, domain = "example.com")
        )

        val recipients = resolver.resolve("Access 2016", adminAuthentication())

        assertThat(recipients.map { it.email })
            .containsExactlyInAnyOrder(
                "owner@example.com",
                "aws-user@example.com",
                "domain-user@example.com",
                "creator@example.com"
            )
    }

    @Test
    fun `resolves active users for installed-product search matches`() {
        val owner = user(10, "installed-owner", "installed-owner@example.com", active = true)
        val variantOwner = user(11, "variant-owner", "variant-owner@example.com", active = true)
        val installedAsset = asset(owner = "installed-owner").also { it.id = 99 }
        val variantAsset = asset(owner = "variant-owner").also { it.id = 100 }

        every { assetRepository.findAssetsByProductForAllNoLimit("7zip") } returns emptyList()
        every { installedProductRepository.findAssetsByProductName("7zip") } returns listOf(installedAsset, variantAsset)
        every { userRepository.findByUsername("installed-owner") } returns Optional.of(owner)
        every { userRepository.findByUsername("variant-owner") } returns Optional.of(variantOwner)

        val recipients = resolver.resolve("7zip", adminAuthentication())

        assertThat(recipients.map { it.email })
            .containsExactlyInAnyOrder("installed-owner@example.com", "variant-owner@example.com")
    }

    @Test
    fun `secchampion product recipients are limited to scoped assets`() {
        val inScopeOwner = user(20, "in-scope-owner", "in-scope@example.com", active = true)
        val outOfScopeOwner = user(21, "out-of-scope-owner", "out-of-scope@example.com", active = true)
        val inScopeAsset = asset(owner = "in-scope-owner").also { it.id = 201 }
        val outOfScopeAsset = asset(owner = "out-of-scope-owner").also { it.id = 202 }
        val authentication = secchampionAuthentication()

        every { assetFilterService.getScopedAccessibleAssetIds(authentication) } returns setOf(201L)
        every {
            assetRepository.findAssetsByProductWithAccessControlNoLimit("7zip", setOf(201L))
        } returns listOf(inScopeAsset)
        every {
            installedProductRepository.findAssetsByProductNameForAssets("7zip", setOf(201L))
        } returns emptyList()
        every { userRepository.findByUsername("in-scope-owner") } returns Optional.of(inScopeOwner)
        every { userRepository.findByUsername("out-of-scope-owner") } returns Optional.of(outOfScopeOwner)

        val recipients = resolver.resolve("7zip", authentication)

        assertThat(recipients.map { it.email })
            .containsExactly("in-scope@example.com")
    }

    @Test
    fun `admin product recipients include global vulnerable and installed product assets`() {
        val vulnOwner = user(30, "vuln-owner", "vuln@example.com", active = true)
        val installedOwner = user(31, "installed-owner", "installed@example.com", active = true)

        every { assetRepository.findAssetsByProductForAllNoLimit("Chrome") } returns listOf(asset(owner = "vuln-owner"))
        every { installedProductRepository.findAssetsByProductName("Chrome") } returns listOf(asset(owner = "installed-owner"))
        every { userRepository.findByUsername("vuln-owner") } returns Optional.of(vulnOwner)
        every { userRepository.findByUsername("installed-owner") } returns Optional.of(installedOwner)

        val recipients = resolver.resolve("Chrome", adminAuthentication())

        assertThat(recipients.map { it.email })
            .containsExactlyInAnyOrder("vuln@example.com", "installed@example.com")
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
