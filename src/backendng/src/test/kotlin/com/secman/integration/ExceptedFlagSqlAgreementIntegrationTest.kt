package com.secman.integration

import com.secman.domain.Asset
import com.secman.domain.ExceptionKind
import com.secman.domain.Vulnerability
import com.secman.domain.VulnerabilityException
import com.secman.repository.AssetRepository
import com.secman.repository.VulnerabilityExceptionRepository
import com.secman.repository.VulnerabilityRepository
import com.secman.testutil.BaseIntegrationTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

/**
 * Closes the third leg of the exception-match agreement triangle.
 *
 * The predicate exists in three synchronized forms:
 *   1. `VulnerabilityException.matches()`             — canonical entity predicate
 *   2. `ExceptionMatchIndex`                          — in-memory bulk index
 *   3. `ExceptionMatchSql.EXCEPTION_MATCH`            — native SQL
 *
 * `ExceptionMatchIndexTest` already agreement-tests 1 against 2 across the full subject × scope
 * matrix. Leg 3 had NO agreement test — and it just became far more load-bearing: every statistics
 * family now filters on `VulnQuerySql.NOT_EXCEPTED` = `v.excepted = 0`, and that column is produced
 * *solely* by `recomputeExcepted*`, which interpolates `EXCEPTION_MATCH`. So if the SQL predicate
 * ever drifts from the entity predicate, dashboards silently disagree with the exception a user
 * created, with nothing to catch it.
 *
 * This test asserts, for every subject × scope combination against assets that do and do not match:
 *
 *     vulnerability.excepted (written by SQL)  ==  exception.matches(vuln, asset) (Kotlin)
 *
 * If you change `ExceptionMatchSql.EXCEPTION_MATCH`, `VulnerabilityException.matches()`, or
 * `ExceptionMatchIndex`, keep this passing.
 */
@DisplayName("excepted flag: SQL recompute agrees with the entity predicate")
class ExceptedFlagSqlAgreementIntegrationTest : BaseIntegrationTest() {

    @Inject
    lateinit var assetRepository: AssetRepository

    @Inject
    lateinit var vulnerabilityRepository: VulnerabilityRepository

    @Inject
    lateinit var exceptionRepository: VulnerabilityExceptionRepository

    @Inject
    lateinit var entityManager: EntityManager

    /**
     * Runs the production recompute STATEMENT and then re-reads with a cleared session.
     *
     * Three traps this navigates, all worth knowing:
     *  - It calls `vulnerabilityRepository.recomputeExceptedAll()` rather than
     *    `ExceptionMaterializationService.recomputeAllExceptedOnce()`. The service method is
     *    `@Transactional(REQUIRES_NEW)`, and @MicronautTest wraps each test in its own
     *    transaction — so the new transaction blocks on this test's uncommitted row locks and dies
     *    with a Lock wait timeout. The repository call joins the test transaction instead. The SQL
     *    under test is identical; only the transaction plumbing differs.
     *  - FLUSH FIRST. A native bulk statement does not trigger Hibernate's auto-flush, so pending
     *    INSERTs/DELETEs on `vulnerability_exception` are invisible to it. Without this, an
     *    exception "deleted" at the end of one loop iteration is still live in the database when
     *    the next iteration recomputes, and the matrix reports false disagreements
     *    (`sql=true entity=false`) for rows the current exception does not match.
     *  - CLEAR AFTER. A bulk UPDATE bypasses the persistence context, so entities already loaded
     *    in this session keep their stale `excepted` value — without the clear, the assertions
     *    below would test Hibernate's first-level cache rather than the database.
     */
    private fun recomputeAndReload(): List<Vulnerability> {
        entityManager.flush()
        vulnerabilityRepository.recomputeExceptedAll()
        entityManager.clear()
        return vulnerabilityRepository.findAll()
    }

    @AfterEach
    fun cleanup() {
        exceptionRepository.deleteAll()
        vulnerabilityRepository.deleteAll()
        assetRepository.deleteAll()
    }

    /**
     * Mirrors the fixture in ExceptionMatchIndexTest's matrix test: one asset that matches every
     * non-GLOBAL scope value and one that matches none, each carrying a matching and a
     * non-matching vulnerability.
     */
    private fun seedFixture(): Pair<Asset, Asset> {
        val matching = assetRepository.save(
            Asset(
                name = "DCBRUS0001", ip = "10.0.0.5", type = "SERVER", owner = "ops",
                cloudAccountId = "111122223333"
            ).apply { osVersion = "Windows Server 2019 Datacenter" }
        )
        val other = assetRepository.save(
            Asset(
                name = "other-host", ip = "10.9.9.9", type = "SERVER", owner = "ops",
                cloudAccountId = "999988887777"
            ).apply { osVersion = "Ubuntu 22.04" }
        )

        listOf(
            Triple(matching, "CVE-2024-0001", "OpenSSL 1.1.1"),
            Triple(matching, "CVE-2024-9999", "curl 7.0"),
            Triple(other, "CVE-2024-0001", "OpenSSL 1.1.1"),
            Triple(other, "CVE-2024-9999", null)
        ).forEach { (asset, cve, product) ->
            vulnerabilityRepository.save(
                Vulnerability(
                    asset = asset,
                    vulnerabilityId = cve,
                    cvssSeverity = "High",
                    vulnerableProductVersions = product,
                    scanTimestamp = LocalDateTime.now().minusDays(10)
                )
            )
        }
        return matching to other
    }

    @Test
    @DisplayName("agrees with VulnerabilityException.matches across the whole subject-scope matrix")
    fun sqlRecomputeAgreesWithEntityPredicate() {
        val (matching, _) = seedFixture()

        val subjects = listOf(
            VulnerabilityException.Subject.ALL_VULNS to null,
            VulnerabilityException.Subject.PRODUCT to "OpenSSL",
            VulnerabilityException.Subject.CVE to "CVE-2024-0001, CVE-2024-0002"
        )
        val scopes = listOf(
            Triple(VulnerabilityException.Scope.GLOBAL, null, null),
            Triple(VulnerabilityException.Scope.IP, "10.0.0.5", null),
            Triple(VulnerabilityException.Scope.ASSET, null, matching.id),
            Triple(VulnerabilityException.Scope.AWS_ACCOUNT, "111122223333", null),
            // Case-differing substring: exercises LOCATE(LOWER(scope_value), LOWER(os_version))
            Triple(VulnerabilityException.Scope.OS, "windows server 2019", null)
        )

        val disagreements = mutableListOf<String>()

        for (kind in ExceptionKind.entries) {
            for ((subject, subjectValue) in subjects) {
                for ((scope, scopeValue, exAssetId) in scopes) {
                    // ALL_VULNS x GLOBAL is a forbidden combination in the product model.
                    if (subject == VulnerabilityException.Subject.ALL_VULNS &&
                        scope == VulnerabilityException.Scope.GLOBAL
                    ) continue

                    val ex = exceptionRepository.save(
                        VulnerabilityException(
                            subject = subject,
                            scope = scope,
                            kind = kind,
                            subjectValue = subjectValue,
                            scopeValue = scopeValue,
                            assetId = exAssetId,
                            reason = "agreement test",
                            createdBy = "tester"
                        )
                    )

                    // SQL writes `excepted` from EXCEPTION_MATCH, and the entity predicate must
                    // reach the same verdict for every row.
                    recomputeAndReload().forEach { vuln ->
                        val expected = ex.matches(vuln, vuln.asset)
                        if (vuln.excepted != expected) {
                            disagreements += "kind=$kind subject=$subject scope=$scope " +
                                "vuln=${vuln.vulnerabilityId} asset=${vuln.asset.name} " +
                                "sql=${vuln.excepted} entity=$expected"
                        }
                        // Independently of agreement: a NO_EDR exception must suppress nothing.
                        // Agreement alone would be satisfied if BOTH implementations were wrong.
                        if (kind == ExceptionKind.NO_EDR && vuln.excepted) {
                            disagreements += "NO_EDR suppressed a finding: subject=$subject " +
                                "scope=$scope vuln=${vuln.vulnerabilityId} asset=${vuln.asset.name}"
                        }
                    }

                    // Same flush-then-recompute discipline: the DELETE must reach the database before
                    // the native recompute, or it leaks into the next iteration.
                    exceptionRepository.delete(ex)
                    recomputeAndReload()
                }
            }
        }

        assertThat(disagreements)
            .withFailMessage(
                "ExceptionMatchSql.EXCEPTION_MATCH disagrees with VulnerabilityException.matches():\n%s",
                disagreements.joinToString("\n")
            )
            .isEmpty()
    }

    @Test
    @DisplayName("an expired exception leaves excepted = false")
    fun expiredExceptionDoesNotSuppress() {
        seedFixture()
        exceptionRepository.save(
            VulnerabilityException(
                subject = VulnerabilityException.Subject.CVE,
                scope = VulnerabilityException.Scope.GLOBAL,
                subjectValue = "CVE-2024-0001",
                expirationDate = LocalDateTime.now().minusDays(1),
                reason = "already expired",
                createdBy = "tester"
            )
        )

        // Both SQL (`expiration_date > NOW()`) and the entity predicate treat an expired exception
        // as inactive, so nothing may be suppressed.
        assertThat(recomputeAndReload()).allSatisfy { assertThat(it.excepted).isFalse() }
    }

    /**
     * Migration regression for the `kind` discriminator (V247).
     *
     * The failure this guards against is the worst one available: if `kind` ever holds NULL
     * — Hibernate's hbm2ddl.auto creating the column on an environment where V247 did not
     * apply, a restored pre-V247 dump, baseline-on-migrate skipping history — and
     * EXCEPTION_MATCH compared it with plain equality, then EVERY pre-existing exception
     * would stop matching and the nightly recomputeAllExceptedScheduled would clear
     * `excepted` fleet-wide, unattended, surfacing thousands of "new" findings.
     *
     * EXCEPTION_MATCH is therefore written `(e.kind IS NULL OR e.kind = 'VULNERABILITY')`.
     * This test forces the NULL that the migration is supposed to make impossible, and
     * asserts suppression survives it.
     *
     * The test schema is Hibernate `create-drop` (Flyway is off in the `test` profile), so
     * the column arrives NOT NULL from the entity annotation and the NULL has to be made
     * reachable first. The DDL is reverted in a finally block; note that DDL implicitly
     * commits in MariaDB, so the seeded fixture outlives the test transaction and is
     * removed by the @AfterEach cleanup rather than by rollback.
     */
    @Test
    @DisplayName("an exception with a NULL kind still suppresses (V247 fail-safe)")
    fun nullKindStillSuppresses() {
        seedFixture()
        val ex = exceptionRepository.save(
            VulnerabilityException(
                subject = VulnerabilityException.Subject.CVE,
                scope = VulnerabilityException.Scope.GLOBAL,
                subjectValue = "CVE-2024-0001",
                reason = "legacy row predating the kind column",
                createdBy = "tester"
            )
        )
        entityManager.flush()

        entityManager.createNativeQuery(
            "ALTER TABLE vulnerability_exception MODIFY kind VARCHAR(20) NULL"
        ).executeUpdate()
        try {
            // Simulate the un-backfilled column. Bypasses the entity, which cannot express NULL.
            entityManager.createNativeQuery("UPDATE vulnerability_exception SET kind = NULL WHERE id = :id")
                .setParameter("id", ex.id)
                .executeUpdate()

            val recomputed = recomputeAndReload()
            assertThat(recomputed.filter { it.vulnerabilityId == "CVE-2024-0001" })
                .isNotEmpty
                .allSatisfy { assertThat(it.excepted).isTrue() }
            assertThat(recomputed.filter { it.vulnerabilityId == "CVE-2024-9999" })
                .allSatisfy { assertThat(it.excepted).isFalse() }
        } finally {
            entityManager.createNativeQuery(
                "UPDATE vulnerability_exception SET kind = 'VULNERABILITY' WHERE kind IS NULL"
            ).executeUpdate()
            entityManager.createNativeQuery(
                "ALTER TABLE vulnerability_exception MODIFY kind VARCHAR(20) NOT NULL DEFAULT 'VULNERABILITY'"
            ).executeUpdate()
        }
    }

    /**
     * The straightforward half of the same regression: an ordinary exception created after
     * V247 must keep suppressing exactly as it did before the `kind` conjunct existed.
     */
    @Test
    @DisplayName("adding the kind conjunct did not stop ordinary exceptions suppressing")
    fun vulnerabilityKindStillSuppresses() {
        seedFixture()
        exceptionRepository.save(
            VulnerabilityException(
                subject = VulnerabilityException.Subject.CVE,
                scope = VulnerabilityException.Scope.GLOBAL,
                kind = ExceptionKind.VULNERABILITY,
                subjectValue = "CVE-2024-0001",
                reason = "ordinary suppression",
                createdBy = "tester"
            )
        )

        val recomputed = recomputeAndReload()
        assertThat(recomputed.filter { it.vulnerabilityId == "CVE-2024-0001" })
            .isNotEmpty
            .allSatisfy { assertThat(it.excepted).isTrue() }
    }

    /**
     * End-to-end statement of the feature's core invariant, at the SQL layer: an approved
     * "No EDR possible" exception on an asset must leave that asset's findings visible.
     * Stored exactly as the request pipeline stores it — ALL_VULNS × ASSET, which for
     * kind=VULNERABILITY would suppress every finding on the box.
     */
    @Test
    @DisplayName("a NO_EDR exception leaves the asset's vulnerabilities visible")
    fun noEdrExceptionSuppressesNothing() {
        val (matching, _) = seedFixture()
        exceptionRepository.save(
            VulnerabilityException(
                subject = VulnerabilityException.Subject.ALL_VULNS,
                scope = VulnerabilityException.Scope.ASSET,
                kind = ExceptionKind.NO_EDR,
                assetId = matching.id,
                reason = "appliance image cannot run a Falcon sensor",
                createdBy = "tester"
            )
        )

        assertThat(recomputeAndReload()).allSatisfy { assertThat(it.excepted).isFalse() }
    }
}
