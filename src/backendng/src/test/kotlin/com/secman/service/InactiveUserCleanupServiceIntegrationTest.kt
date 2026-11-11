package com.secman.service

import com.secman.config.UserCleanupConfig
import com.secman.domain.McpApiKey
import com.secman.domain.User
import com.secman.domain.UserCleanupOutcome
import com.secman.domain.UserCleanupRunStatus
import com.secman.domain.Workgroup
import com.secman.repository.McpApiKeyRepository
import com.secman.repository.UserCleanupRunItemRepository
import com.secman.repository.UserCleanupRunRepository
import com.secman.repository.UserRepository
import com.secman.repository.WorkgroupRepository
import com.secman.testutil.TestDataFactory
import io.micronaut.data.model.Pageable
import io.micronaut.test.annotation.MockBean
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import io.micronaut.transaction.TransactionOperations
import java.sql.Connection

/**
 * End-to-end behaviour of the inactive-user cleanup against a real schema. Mail is mocked:
 * the mailer's contract is "true when the mail went out".
 */
@MicronautTest(environments = ["test"], transactional = false)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class InactiveUserCleanupServiceIntegrationTest {

    @Inject lateinit var service: InactiveUserCleanupService
    @Inject lateinit var executor: InactiveUserDeletionExecutor
    @Inject lateinit var mailer: InactiveUserWarningMailer
    @Inject lateinit var userRepository: UserRepository
    @Inject lateinit var userService: UserService
    @Inject lateinit var workgroupRepository: WorkgroupRepository
    @Inject lateinit var mcpApiKeyRepository: McpApiKeyRepository
    @Inject lateinit var runRepository: UserCleanupRunRepository
    @Inject lateinit var itemRepository: UserCleanupRunItemRepository
    @Inject lateinit var transactions: TransactionOperations<Connection>

    @MockBean(InactiveUserWarningMailer::class)
    fun mockMailer(): InactiveUserWarningMailer = mockk()

    /** Brake off unless a test turns it on; the denominator is every user in the schema. */
    private val live = UserCleanupConfig(enabled = true, dryRun = false, maxDeletePercent = 100)
    private val now = Instant.now()
    private val created = mutableListOf<Long>()
    private val workgroups = mutableListOf<Long>()

    @BeforeEach
    fun mailWorks() {
        clearMocks(mailer)
        every { mailer.send(any(), any()) } returns true
    }

    @AfterEach
    fun cleanUp() {
        itemRepository.deleteAll()
        runRepository.deleteAll()
        created.filter { userRepository.existsById(it) }.forEach { userService.deleteUser(it) }
        created.clear()
        workgroups.forEach { workgroupRepository.deleteById(it) }
        workgroups.clear()
    }

    @Test
    fun `dry run writes only the run record and sends no mail`() {
        val warned = user(inactiveDays = 400, warnedDaysAgo = 40)
        val dueForWarning = user(inactiveDays = 340)

        val summary = service.run("test", live.copy(dryRun = true))

        assertThat(summary.status).isEqualTo(UserCleanupRunStatus.DRY_RUN)
        assertThat(outcome(summary.runId!!, warned)).isEqualTo(UserCleanupOutcome.WOULD_DELETE)
        assertThat(outcome(summary.runId!!, dueForWarning)).isEqualTo(UserCleanupOutcome.WOULD_WARN)
        assertThat(userRepository.existsById(warned.id!!)).isTrue()
        assertThat(userRepository.findById(dueForWarning.id!!).get().inactivityWarningSentAt).isNull()
        verify(exactly = 0) { mailer.send(any(), any()) }
    }

    @Test
    fun `first live run warns but deletes nobody`() {
        val neverWarned = user(inactiveDays = 400)

        val summary = service.run("test", live)

        assertThat(outcome(summary.runId!!, neverWarned)).isEqualTo(UserCleanupOutcome.WARNED)
        assertThat(userRepository.existsById(neverWarned.id!!)).isTrue()
        assertThat(userRepository.findById(neverWarned.id!!).get().inactivityWarningSentAt).isNotNull()
    }

    @Test
    fun `validly warned inactive user is deleted`() {
        val target = user(inactiveDays = 400, warnedDaysAgo = 31)

        val summary = service.run("test", live)

        assertThat(summary.status).isEqualTo(UserCleanupRunStatus.COMPLETED)
        assertThat(outcome(summary.runId!!, target)).isEqualTo(UserCleanupOutcome.DELETED)
        assertThat(userRepository.existsById(target.id!!)).isFalse()
        assertThat(itemRepository.findByRunIdOrderById(summary.runId!!, Pageable.from(0, 50)).content
            .single { it.userId == target.id }.email).isEqualTo(target.email)
    }

    @Test
    fun `user active again between selection and deletion is skipped`() {
        val target = user(inactiveDays = 400, warnedDaysAgo = 31)
        userRepository.touchLastLogin(target.id!!, Instant.now(), Instant.now())

        val result = executor.deleteIfStillEligible(target.id!!, now, live)

        assertThat(result.outcome).isEqualTo(UserCleanupOutcome.SKIPPED)
        assertThat(result.reason).isEqualTo(InactiveUserCleanupService.REASON_ACTIVE_AGAIN)
        assertThat(userRepository.existsById(target.id!!)).isTrue()
    }

    @Test
    fun `login committed while the re-check waits is seen and the user is kept`() {
        val target = user(inactiveDays = 400, warnedDaysAgo = 31)
        val locked = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            // A login updates the user row and holds it uncommitted while the executor re-checks.
            val login = pool.submit {
                transactions.executeWrite { status ->
                    status.connection.prepareStatement("UPDATE users SET last_login = UTC_TIMESTAMP(6) WHERE id = ?").use {
                        it.setLong(1, target.id!!)
                        it.executeUpdate()
                    }
                    locked.countDown()
                    release.await(30, TimeUnit.SECONDS)
                }
            }
            assertThat(locked.await(30, TimeUnit.SECONDS)).isTrue()
            val result = pool.submit<com.secman.dto.UserCleanupItemDto> {
                executor.deleteIfStillEligible(target.id!!, now, live)
            }
            Thread.sleep(1500)
            release.countDown()
            login.get(30, TimeUnit.SECONDS)

            assertThat(result.get(60, TimeUnit.SECONDS).reason).isEqualTo(InactiveUserCleanupService.REASON_ACTIVE_AGAIN)
            assertThat(userRepository.existsById(target.id!!)).isTrue()
        } finally {
            release.countDown()
            pool.shutdownNow()
        }
    }

    @Test
    fun `executor re-checks exclusions such as a role granted after selection`() {
        val promoted = user(inactiveDays = 400, warnedDaysAgo = 31, roles = setOf(User.Role.ADMIN))

        val result = executor.deleteIfStillEligible(promoted.id!!, now, live)

        assertThat(result.reason).isEqualTo("ADMIN")
        assertThat(userRepository.existsById(promoted.id!!)).isTrue()
    }

    @Test
    fun `failed warning mail is not recorded as warned`() {
        every { mailer.send(any(), any()) } returns false
        val target = user(inactiveDays = 400)

        val summary = service.run("test", live)

        assertThat(outcome(summary.runId!!, target)).isEqualTo(UserCleanupOutcome.WARNING_FAILED)
        assertThat(userRepository.findById(target.id!!).get().inactivityWarningSentAt).isNull()
    }

    @Test
    fun `brake aborts deletion but still warns`() {
        val deletable = user(inactiveDays = 400, warnedDaysAgo = 31)
        val dueForWarning = user(inactiveDays = 340)

        val summary = service.run("test", live.copy(maxDeletePercent = 0))

        assertThat(summary.status).isEqualTo(UserCleanupRunStatus.ABORTED_BRAKE)
        assertThat(userRepository.existsById(deletable.id!!)).isTrue()
        assertThat(outcome(summary.runId!!, dueForWarning)).isEqualTo(UserCleanupOutcome.WARNED)
    }

    @Test
    fun `admin, service account, recent API key owner and blocked user are skipped with reason`() {
        val admin = user(inactiveDays = 400, warnedDaysAgo = 31, roles = setOf(User.Role.ADMIN))
        val service = user(inactiveDays = 400, warnedDaysAgo = 31, serviceAccount = true)
        val keyOwner = user(inactiveDays = 400, warnedDaysAgo = 31)
        mcpApiKeyRepository.save(
            McpApiKey(keyId = "cleanup-test-key-${keyOwner.id}", keyHash = "h", name = "k", userId = keyOwner.id!!,
                permissions = "ASSETS_READ", lastUsedAt = LocalDateTime.now().minusDays(2))
        )

        val summary = this.service.run("test", live)

        assertThat(reason(summary.runId!!, admin)).isEqualTo("ADMIN")
        assertThat(reason(summary.runId!!, service)).isEqualTo("SERVICE_ACCOUNT")
        assertThat(reason(summary.runId!!, keyOwner)).isEqualTo("ACTIVE_API_KEY")
        assertThat(listOf(admin, service, keyOwner).map { userRepository.existsById(it.id!!) }).containsOnly(true)
        verify(exactly = 0) { mailer.send(any(), any()) }
    }

    @Test
    fun `orphaned workgroup is reported`() {
        val target = user(inactiveDays = 400, warnedDaysAgo = 31)
        val workgroup = workgroupRepository.save(Workgroup(name = "cleanup-wg-${target.id}"))
        workgroups += workgroup.id!!
        userRepository.findByIdWithWorkgroups(target.id!!).get().let {
            it.workgroups.add(workgroup)
            userRepository.update(it)
        }

        val summary = service.run("test", live)

        assertThat(summary.orphanedWorkgroupIds).contains(workgroup.id)
    }

    @Test
    fun `concurrent run is refused`() {
        user(inactiveDays = 400)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        every { mailer.send(any(), any()) } answers { entered.countDown(); release.await(10, TimeUnit.SECONDS); true }
        val pool = Executors.newSingleThreadExecutor()
        try {
            val first = pool.submit { service.run("first", live) }
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue()

            assertThatThrownBy { service.run("second", live) }.isInstanceOf(UserCleanupInProgressException::class.java)

            release.countDown()
            first.get(30, TimeUnit.SECONDS)
        } finally {
            release.countDown()
            pool.shutdownNow()
        }
    }

    private fun user(
        inactiveDays: Long,
        warnedDaysAgo: Long? = null,
        roles: Set<User.Role> = emptySet(),
        serviceAccount: Boolean = false
    ): User {
        val tag = UUID.randomUUID().toString().take(8)
        val user = TestDataFactory.createRegularUser("cleanup-$tag", "cleanup-$tag@secman.test").apply {
            this.roles.addAll(roles)
            this.serviceAccount = serviceAccount
        }
        val saved = userRepository.save(user)
        created += saved.id!!
        // created_at is not updatable and defaults to now; last_login is what makes it inactive.
        saved.lastLogin = now.minus(Duration.ofDays(inactiveDays))
        saved.inactivityWarningSentAt = warnedDaysAgo?.let { now.minus(Duration.ofDays(it)) }
        return userRepository.update(saved)
    }

    private fun item(runId: Long, user: User) =
        itemRepository.findByRunIdOrderById(runId, Pageable.from(0, 500)).content.single { it.userId == user.id }

    private fun outcome(runId: Long, user: User) = item(runId, user).outcome

    private fun reason(runId: Long, user: User) = item(runId, user).reason
}
