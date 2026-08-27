package com.secman.service

import com.secman.domain.Workgroup
import com.secman.domain.WorkgroupAwsAccount
import com.secman.repository.UserMappingRepository
import com.secman.repository.WorkgroupAwsAccountRepository
import com.secman.repository.WorkgroupRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.Optional

/**
 * Pins the rule "an AWS account whose display name is X belongs to the workgroup aws-X".
 *
 * These are authorization-relevant: a link grants every member of the workgroup access to
 * the account's assets (unified asset access rule #9). The assertions are therefore about
 * the exact workgroup matched or created, never "roughly the right one".
 */
class WorkgroupAccountLinkServiceTest {

    private val workgroupRepository = mockk<WorkgroupRepository>()
    private val workgroupAwsAccountRepository = mockk<WorkgroupAwsAccountRepository>()
    private val workgroupService = mockk<WorkgroupService>()
    private val workgroupAwsAccountService = mockk<WorkgroupAwsAccountService>()
    private val userMappingRepository = mockk<UserMappingRepository>()

    private lateinit var service: WorkgroupAccountLinkService

    private val ACCOUNT = "706840063453"

    @BeforeEach
    fun setUp() {
        service = WorkgroupAccountLinkService(
            workgroupRepository,
            workgroupAwsAccountRepository,
            workgroupService,
            workgroupAwsAccountService,
            userMappingRepository
        )
    }

    private fun workgroup(id: Long, name: String) = Workgroup(id = id, name = name)

    private fun pair(accountId: String = ACCOUNT, displayName: String = "DevOps-x") =
        WorkgroupAccountLinkService.AccountDisplayName(accountId, displayName)

    private fun existingWorkgroup(name: String, id: Long = 42L) {
        every { workgroupRepository.findByNameIgnoreCase(any()) } returns
            Optional.of(workgroup(id, name))
    }

    private fun noWorkgroup() {
        every { workgroupRepository.findByNameIgnoreCase(any()) } returns Optional.empty()
    }

    private fun notYetLinked() {
        every { workgroupAwsAccountRepository.existsByWorkgroupIdAndAwsAccountId(any(), any()) } returns false
        every { workgroupAwsAccountService.add(any(), any(), any()) } returns
            mockk<WorkgroupAwsAccount>(relaxed = true)
    }

    // --- Naming rule ---

    @Test
    fun `the workgroup name is the display name with the aws prefix`() {
        assertThat(WorkgroupAccountLinkService.workgroupNameFor("DevOps-x")).isEqualTo("aws-DevOps-x")
        // Surrounding whitespace in a source file must not produce "aws- DevOps-x".
        assertThat(WorkgroupAccountLinkService.workgroupNameFor("  DevOps-x  ")).isEqualTo("aws-DevOps-x")
    }

    @Test
    fun `an existing workgroup is matched case-insensitively and never recreated`() {
        existingWorkgroup("aws-DevOps-x")
        notYetLinked()

        val summary = service.link(listOf(pair(displayName = "devops-X")), actorId = 7L, dryRun = false)

        val lookedUpName = slot<String>()
        verify { workgroupRepository.findByNameIgnoreCase(capture(lookedUpName)) }
        assertThat(lookedUpName.captured).isEqualTo("aws-devops-X")

        verify(exactly = 0) { workgroupService.createWorkgroup(any(), any(), any(), any()) }
        verify { workgroupAwsAccountService.add(42L, ACCOUNT, 7L) }
        assertThat(summary.linked).isEqualTo(1)
        assertThat(summary.workgroupsCreated).isZero()
        // The stored name is reported, not the one derived from the file's casing.
        assertThat(summary.links.single().workgroupName).isEqualTo("aws-DevOps-x")
    }

    @Test
    fun `a missing workgroup is created and then linked`() {
        noWorkgroup()
        every { workgroupService.createWorkgroup(any(), any(), any(), any()) } returns
            workgroup(99L, "aws-DevOps-x")
        notYetLinked()

        val summary = service.link(listOf(pair()), actorId = 7L, dryRun = false)

        val createdName = slot<String>()
        verify { workgroupService.createWorkgroup(capture(createdName), any(), any(), 7L) }
        assertThat(createdName.captured).isEqualTo("aws-DevOps-x")
        verify { workgroupAwsAccountService.add(99L, ACCOUNT, 7L) }
        assertThat(summary.workgroupsCreated).isEqualTo(1)
        assertThat(summary.linked).isEqualTo(1)
        assertThat(summary.links.single().workgroupCreated).isTrue
    }

    @Test
    fun `an account already in the workgroup is an idempotent no-op, not a failure`() {
        existingWorkgroup("aws-DevOps-x")
        every { workgroupAwsAccountRepository.existsByWorkgroupIdAndAwsAccountId(42L, ACCOUNT) } returns true

        val summary = service.link(listOf(pair()), actorId = 7L, dryRun = false)

        verify(exactly = 0) { workgroupAwsAccountService.add(any(), any(), any()) }
        assertThat(summary.alreadyLinked).isEqualTo(1)
        assertThat(summary.linked).isZero()
        assertThat(summary.failed).isZero()
    }

    @Test
    fun `losing the race to another import counts as already linked`() {
        existingWorkgroup("aws-DevOps-x")
        every { workgroupAwsAccountRepository.existsByWorkgroupIdAndAwsAccountId(any(), any()) } returns false
        every { workgroupAwsAccountService.add(any(), any(), any()) } throws
            DuplicateAccountException("already assigned")

        val summary = service.link(listOf(pair()), actorId = 7L, dryRun = false)

        // The desired end state holds, so this is not an error the operator must act on.
        assertThat(summary.alreadyLinked).isEqualTo(1)
        assertThat(summary.failed).isZero()
    }

    @Test
    fun `losing the race to create the workgroup falls back to the winner's row`() {
        every { workgroupRepository.findByNameIgnoreCase("aws-DevOps-x") } returnsMany listOf(
            Optional.empty(),                             // pre-create check
            Optional.of(workgroup(77L, "aws-DevOps-x"))   // re-read after the failed create
        )
        every { workgroupService.createWorkgroup(any(), any(), any(), any()) } throws
            IllegalArgumentException("Workgroup name already exists (case-insensitive): aws-DevOps-x")
        notYetLinked()

        val summary = service.link(listOf(pair()), actorId = 7L, dryRun = false)

        verify { workgroupAwsAccountService.add(77L, ACCOUNT, 7L) }
        assertThat(summary.failed).isZero()
        assertThat(summary.linked).isEqualTo(1)
    }

    // --- Rejections ---

    @Test
    fun `a display name that cannot be a workgroup name is reported, never created`() {
        // Workgroup.name allows letters, digits, spaces and hyphens only.
        val summary = service.link(
            listOf(pair(displayName = "dev_ops.team/1")),
            actorId = 7L,
            dryRun = false
        )

        verify(exactly = 0) { workgroupService.createWorkgroup(any(), any(), any(), any()) }
        verify(exactly = 0) { workgroupAwsAccountService.add(any(), any(), any()) }
        assertThat(summary.failed).isEqualTo(1)
        assertThat(summary.links.single().error).contains("letters, numbers, spaces and hyphens")
    }

    @Test
    fun `a display name too long to be a workgroup name is reported, never created`() {
        val summary = service.link(
            listOf(pair(displayName = "x".repeat(120))),
            actorId = 7L,
            dryRun = false
        )

        verify(exactly = 0) { workgroupService.createWorkgroup(any(), any(), any(), any()) }
        assertThat(summary.failed).isEqualTo(1)
        assertThat(summary.links.single().error).contains("exceeds 100 characters")
    }

    @Test
    fun `an account id that is not 12 digits is reported, never linked`() {
        val summary = service.link(listOf(pair(accountId = "12345")), actorId = 7L, dryRun = false)

        verify(exactly = 0) { workgroupRepository.findByNameIgnoreCase(any()) }
        assertThat(summary.failed).isEqualTo(1)
        assertThat(summary.links.single().error).contains("12 numeric digits")
    }

    @Test
    fun `one bad pair does not stop the others`() {
        every { workgroupRepository.findByNameIgnoreCase("aws-DevOps-x") } returns
            Optional.of(workgroup(42L, "aws-DevOps-x"))
        notYetLinked()

        val summary = service.link(
            listOf(pair(displayName = "bad_name"), pair()),
            actorId = 7L,
            dryRun = false
        )

        assertThat(summary.failed).isEqualTo(1)
        assertThat(summary.linked).isEqualTo(1)
    }

    @Test
    fun `blank display names and account ids are dropped before any DB work`() {
        val summary = service.link(
            listOf(pair(displayName = "   "), pair(accountId = "  ")),
            actorId = 7L,
            dryRun = false
        )

        verify(exactly = 0) { workgroupRepository.findByNameIgnoreCase(any()) }
        assertThat(summary.processed).isZero()
    }

    // --- Dedup and dry run ---

    @Test
    fun `the same account and name repeated in one file is processed once`() {
        existingWorkgroup("aws-DevOps-x")
        notYetLinked()

        val summary = service.link(
            listOf(pair(), pair(), pair()),
            actorId = 7L,
            dryRun = false
        )

        assertThat(summary.processed).isEqualTo(1)
        verify(exactly = 1) { workgroupAwsAccountService.add(any(), any(), any()) }
    }

    @Test
    fun `a dry run creates nothing and assigns nothing`() {
        noWorkgroup()

        val summary = service.link(listOf(pair()), actorId = 7L, dryRun = true)

        verify(exactly = 0) { workgroupService.createWorkgroup(any(), any(), any(), any()) }
        verify(exactly = 0) { workgroupAwsAccountService.add(any(), any(), any()) }
        assertThat(summary.dryRun).isTrue
        assertThat(summary.workgroupsCreated).isEqualTo(1)  // what *would* be created
        assertThat(summary.links.single().dryRun).isTrue
    }

    @Test
    fun `a dry run against an existing link reports it as already linked`() {
        existingWorkgroup("aws-DevOps-x")
        every { workgroupAwsAccountRepository.existsByWorkgroupIdAndAwsAccountId(42L, ACCOUNT) } returns true

        val summary = service.link(listOf(pair()), actorId = 7L, dryRun = true)

        assertThat(summary.alreadyLinked).isEqualTo(1)
        assertThat(summary.linked).isZero()
    }

    // --- Correction path (from stored mappings) ---

    @Test
    fun `stored mappings drive the correction path`() {
        every { userMappingRepository.findAwsAccountDisplayNames(any()) } returns listOf(
            arrayOf<Any>(ACCOUNT, "DevOps-x", Instant.parse("2026-08-01T00:00:00Z"))
        )
        existingWorkgroup("aws-DevOps-x")
        notYetLinked()

        val summary = service.linkFromStoredMappings(actorId = 7L, dryRun = false)

        verify { workgroupAwsAccountService.add(42L, ACCOUNT, 7L) }
        assertThat(summary.linked).isEqualTo(1)
    }

    @Test
    fun `a renamed account links under its most recent display name`() {
        every { userMappingRepository.findAwsAccountDisplayNames(any()) } returns listOf(
            arrayOf<Any>(ACCOUNT, "DevOps-old", Instant.parse("2026-01-01T00:00:00Z")),
            arrayOf<Any>(ACCOUNT, "DevOps-new", Instant.parse("2026-08-01T00:00:00Z"))
        )
        existingWorkgroup("aws-DevOps-new")
        notYetLinked()

        val summary = service.linkFromStoredMappings(actorId = 7L, dryRun = false)

        val lookedUp = slot<String>()
        verify { workgroupRepository.findByNameIgnoreCase(capture(lookedUp)) }
        assertThat(lookedUp.captured).isEqualTo("aws-DevOps-new")
        // One link, not two: the stale name is not re-linked, and the assignment made
        // under it is deliberately left alone (removing one would revoke access).
        assertThat(summary.processed).isEqualTo(1)
    }

    @Test
    fun `no stored display names means nothing is processed`() {
        every { userMappingRepository.findAwsAccountDisplayNames(any()) } returns emptyList()

        val summary = service.linkFromStoredMappings(actorId = 7L, dryRun = false)

        assertThat(summary.processed).isZero()
        verify(exactly = 0) { workgroupRepository.findByNameIgnoreCase(any()) }
    }
}
