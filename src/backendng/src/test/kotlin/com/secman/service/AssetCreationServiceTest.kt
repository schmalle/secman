package com.secman.service

import com.secman.domain.Asset
import com.secman.domain.User
import com.secman.domain.Workgroup
import com.secman.repository.AssetRepository
import com.secman.repository.UserRepository
import com.secman.repository.WorkgroupRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.Optional

/** Ensures unauthorized placement cannot persist an asset or a grant. */
class AssetCreationServiceTest {
    private val assets = mockk<AssetRepository>()
    private val users = mockk<UserRepository>()
    private val groups = mockk<WorkgroupRepository>()
    private val service = AssetCreationService(assets, users, groups)
    private val actor = User(id = 1L, username = "alice", email = "alice@example.test", passwordHash = "unused")
    private fun asset() = Asset(name = "server", type = "SERVER", owner = "metadata")
    private fun setup(group: Workgroup? = null) {
        every { users.findById(1L) } returns Optional.of(actor)
        every { groups.findForPlacement(any()) } returns emptyList()
        if (group != null) every { groups.findForPlacement(setOf(2L)) } returns listOf(group)
        every { assets.save(any<Asset>()) } answers { firstArg() }
    }

    @Test
    fun `ordinary creation without placement writes nothing`() {
        setup()
        assertThrows(IllegalArgumentException::class.java) { service.create(asset(), 1L, emptyList()) }
        verify(exactly = 0) { assets.save(any<Asset>()) }
    }

    @Test
    fun `creation in enabled direct AWS managed group saves the initial grant`() {
        val group = Workgroup(id = 2L, name = "direct", users = mutableSetOf(actor), awsAccountManaged = true)
        setup(group)
        val saved = service.create(asset(), 1L, listOf(2L))
        assertEquals(setOf(2L), saved.workgroups.map { it.id }.toSet())
        assertSame(actor, saved.manualCreator)
    }

    @Test
    fun `disabled and unrelated groups cannot receive ordinary creations`() {
        for (group in listOf(Workgroup(id = 2L, name = "disabled", enabled = false, users = mutableSetOf(actor)),
            Workgroup(id = 2L, name = "unrelated"))) {
            setup(group)
            assertThrows(IllegalArgumentException::class.java) { service.create(asset(), 1L, listOf(2L)) }
        }
        verify(exactly = 0) { assets.save(any<Asset>()) }
    }

    @Test
    fun `ordinary creation cannot set account or domain associations`() {
        setup()
        for (value in listOf(asset().apply { cloudAccountId = "123456789012" }, asset().apply { adDomain = "corp.test" })) {
            assertThrows(IllegalArgumentException::class.java) { service.create(value, 1L, listOf(2L)) }
        }
        verify(exactly = 0) { assets.save(any<Asset>()) }
    }

    @Test
    fun `global grant managers can create without placement`() {
        setup()
        for (role in listOf(User.Role.ADMIN, User.Role.SECCHAMPION)) {
            actor.roles = mutableSetOf(role)
            assertNotNull(service.create(asset(), 1L, emptyList()))
        }
    }
}
