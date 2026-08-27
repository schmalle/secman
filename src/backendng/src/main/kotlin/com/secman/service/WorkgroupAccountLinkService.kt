package com.secman.service

import com.secman.domain.Workgroup
import com.secman.dto.WorkgroupAccountLinkInfo
import com.secman.dto.WorkgroupAccountLinkSummary
import com.secman.repository.UserMappingRepository
import com.secman.repository.WorkgroupAwsAccountRepository
import com.secman.repository.WorkgroupRepository
import io.micronaut.data.model.Pageable
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory
import java.time.Instant

/**
 * The one implementation of "an AWS account whose display name is X belongs to the
 * workgroup named aws-X".
 *
 * Every surface goes through it, so the rule cannot drift:
 * - the import side effect in [UserMappingBulkImportService] (CLI `manage-user-mappings
 *   import`, REST `POST /api/user-mappings/bulk`, MCP `import_user_mappings`)
 * - the CSV upload path in `ImportController.uploadUserMappingsCSV`
 * - the correction path (CLI `manage-user-mappings link-workgroups`, REST
 *   `POST /api/user-mappings/link-workgroup-accounts`, MCP `link_workgroup_aws_accounts`),
 *   which re-links from the display names already stored on the mappings
 *
 * Matching is **exact** on `"aws-" + displayName`, compared case-insensitively
 * ([WorkgroupRepository.findByNameIgnoreCase]). Suffixed variants such as
 * `aws-DevOps-x-rolegreen` are deliberately NOT matched: a fuzzy rule here would hand
 * an unrelated workgroup's members access to an account's assets (access rule #9).
 *
 * A missing workgroup is created. A display name that cannot legally BE a workgroup
 * name is reported as an error instead — see [validateWorkgroupName].
 *
 * Authorization is the caller's job: every entry point is ADMIN-gated. This service
 * trusts that and only records the actor.
 */
@Singleton
open class WorkgroupAccountLinkService(
    private val workgroupRepository: WorkgroupRepository,
    private val workgroupAwsAccountRepository: WorkgroupAwsAccountRepository,
    private val workgroupService: WorkgroupService,
    private val workgroupAwsAccountService: WorkgroupAwsAccountService,
    private val userMappingRepository: UserMappingRepository
) {
    private val log = LoggerFactory.getLogger(WorkgroupAccountLinkService::class.java)

    /** One (account, display name) pair to link. */
    data class AccountDisplayName(
        val awsAccountId: String,
        val displayName: String
    )

    /**
     * Link every pair to its `aws-<displayName>` workgroup, creating the workgroup when
     * it does not exist yet.
     *
     * Not `@Transactional`: each workgroup creation and each assignment commits on its
     * own (through [WorkgroupService] / [WorkgroupAwsAccountService], which are), so one
     * bad pair cannot roll back the rest of the batch. The same reason the import's other
     * side effects run after the mappings have committed.
     *
     * @param actorId the ADMIN who triggered this; recorded as `createdBy`. May be null
     *        on an automated path where no interactive user is resolvable.
     * @param dryRun report what would happen; create and write nothing.
     */
    open fun link(
        pairs: List<AccountDisplayName>,
        actorId: Long?,
        dryRun: Boolean
    ): WorkgroupAccountLinkSummary {
        // Same (account, name) can appear once per owner in a mapping file — collapse
        // before touching the DB. LinkedHashSet keeps the report order stable.
        val deduped = pairs
            .mapNotNull { pair ->
                val accountId = pair.awsAccountId.trim()
                val displayName = pair.displayName.trim()
                if (accountId.isEmpty() || displayName.isEmpty()) null
                else AccountDisplayName(accountId, displayName)
            }
            .toCollection(LinkedHashSet())

        if (deduped.isEmpty()) return WorkgroupAccountLinkSummary(dryRun = dryRun)

        val capped = deduped.size > MAX_PAIRS
        if (capped) {
            // Never a silent cap: what was dropped is said out loud.
            log.warn(
                "Workgroup linking capped at {} of {} account/display-name pairs; " +
                    "the remainder is left for the next run",
                MAX_PAIRS, deduped.size
            )
        }

        val results = deduped.take(MAX_PAIRS).map { pair -> linkOne(pair, actorId, dryRun) }
        return summarize(results, dryRun, truncatedInput = capped)
    }

    /**
     * The correction path: re-link from the display names already stored on user
     * mappings, with no source file involved. Idempotent — an account already assigned
     * to its workgroup comes back as `alreadyLinked`.
     *
     * When one account carries several display names (it was renamed between imports),
     * the most recently updated mapping wins. Stale assignments made under the previous
     * name are left in place: removing one revokes access, which is never something a
     * correction run should do without being asked.
     */
    open fun linkFromStoredMappings(actorId: Long?, dryRun: Boolean): WorkgroupAccountLinkSummary {
        // Bounded at the query (MAX_PAIRS + 1 so truncation is detectable), grouped so the
        // row count is distinct (account, name) combinations rather than mappings.
        val rows = userMappingRepository.findAwsAccountDisplayNames(Pageable.from(0, MAX_PAIRS + 1))

        val newestPerAccount = LinkedHashMap<String, Pair<String, Instant?>>()
        rows.forEach { row ->
            val accountId = row[0] as? String ?: return@forEach
            val displayName = row[1] as? String ?: return@forEach
            val updatedAt = row.getOrNull(2) as? Instant
            val existing = newestPerAccount[accountId]
            if (existing == null || isNewer(updatedAt, existing.second)) {
                newestPerAccount[accountId] = displayName to updatedAt
            }
        }

        val truncated = rows.size > MAX_PAIRS
        if (truncated) {
            log.warn(
                "Stored-mapping linking scanned the first {} (account, display name) rows; " +
                    "more exist and are left for the next run",
                MAX_PAIRS
            )
        }

        val pairs = newestPerAccount.map { (accountId, named) ->
            AccountDisplayName(awsAccountId = accountId, displayName = named.first)
        }

        log.info(
            "AUDIT: operation=LINK_WORKGROUP_ACCOUNTS_FROM_MAPPINGS, actorId={}, dryRun={}, candidates={}",
            actorId, dryRun, pairs.size
        )

        val summary = link(pairs, actorId, dryRun)
        return if (truncated) summary.copy(truncated = true) else summary
    }

    /**
     * Resolve (and if needed create) the workgroup for one pair, then assign the account.
     *
     * Every failure mode becomes a result row rather than an exception, so a single bad
     * display name in a 10,000-account import cannot abort the run.
     */
    private fun linkOne(
        pair: AccountDisplayName,
        actorId: Long?,
        dryRun: Boolean
    ): WorkgroupAccountLinkInfo {
        val workgroupName = workgroupNameFor(pair.displayName)
        val safeDisplayName = UserMappingService.sanitizeForMessage(pair.displayName)
        val safeWorkgroupName = UserMappingService.sanitizeForMessage(workgroupName)

        // Six of the seven exits below report the same account under the same names and
        // differ only in which flags are set. Routing them through one builder keeps the
        // shared half in a single place — a field added to the result would otherwise
        // reach five exits and be forgotten on the sixth, which is a reporting bug that
        // no test for the happy path can see.
        fun result(
            resolvedName: String = safeWorkgroupName,
            workgroupId: Long? = null,
            workgroupCreated: Boolean = false,
            linked: Boolean = false,
            alreadyLinked: Boolean = false
        ) = WorkgroupAccountLinkInfo(
            awsAccountId = pair.awsAccountId,
            displayName = safeDisplayName,
            workgroupName = resolvedName,
            workgroupId = workgroupId,
            workgroupCreated = workgroupCreated,
            linked = linked,
            alreadyLinked = alreadyLinked,
            dryRun = dryRun
        )

        fun failure(message: String) = WorkgroupAccountLinkInfo(
            awsAccountId = pair.awsAccountId,
            displayName = safeDisplayName,
            workgroupName = safeWorkgroupName,
            dryRun = dryRun,
            error = message
        )

        if (!ACCOUNT_ID_PATTERN.matches(pair.awsAccountId)) {
            return failure(
                "AWS account ID must be exactly 12 numeric digits " +
                    "(got '${UserMappingService.sanitizeForMessage(pair.awsAccountId)}')"
            )
        }

        validateWorkgroupName(workgroupName)?.let { return failure(it) }

        return try {
            val existing = workgroupRepository.findByNameIgnoreCase(workgroupName).orElse(null)

            if (existing == null && dryRun) {
                // Nothing to look up an assignment against — say what would happen.
                return result(workgroupCreated = true, linked = true)
            }

            val workgroup = existing ?: createWorkgroup(workgroupName, actorId)
            val workgroupId = workgroup.id
                ?: return failure("Workgroup '$safeWorkgroupName' has no id after creation")

            val alreadyLinked = workgroupAwsAccountRepository
                .existsByWorkgroupIdAndAwsAccountId(workgroupId, pair.awsAccountId)

            if (alreadyLinked) {
                return result(resolvedName = workgroup.name, workgroupId = workgroupId, alreadyLinked = true)
            }

            if (dryRun) {
                return result(resolvedName = workgroup.name, workgroupId = workgroupId, linked = true)
            }

            try {
                workgroupAwsAccountService.add(workgroupId, pair.awsAccountId, actorId)
            } catch (e: DuplicateAccountException) {
                // Another import linked the same account between the check and the write.
                // The unique index is the arbiter; the desired end state holds either way.
                log.debug("AWS account {} was already linked to workgroup {}", pair.awsAccountId, workgroupId)
                return result(resolvedName = workgroup.name, workgroupId = workgroupId, alreadyLinked = true)
            }

            log.info(
                "AUDIT: operation=LINK_WORKGROUP_ACCOUNT, actorId={}, awsAccountId={}, " +
                    "workgroup={}, workgroupId={}, outcome=LINKED",
                actorId, pair.awsAccountId, safeWorkgroupName, workgroupId
            )

            result(
                resolvedName = workgroup.name,
                workgroupId = workgroupId,
                workgroupCreated = existing == null,
                linked = true
            )
        } catch (e: Exception) {
            log.warn(
                "Failed to link AWS account {} to workgroup {}: {}",
                pair.awsAccountId, safeWorkgroupName, e.message
            )
            failure(e.message?.let { UserMappingService.sanitizeForMessage(it) } ?: "Linking failed")
        }
    }

    /**
     * Create the workgroup, tolerating the race where a concurrent import created it
     * first: the unique name check is re-read rather than turning a lost race into an
     * error the operator has to interpret.
     */
    private fun createWorkgroup(workgroupName: String, actorId: Long?): Workgroup {
        return try {
            val created = workgroupService.createWorkgroup(
                name = workgroupName,
                description = WORKGROUP_DESCRIPTION,
                creatorUserId = actorId
            )
            log.info(
                "AUDIT: operation=CREATE_WORKGROUP_FOR_AWS_ACCOUNT, actorId={}, workgroup={}, " +
                    "workgroupId={}, outcome=CREATED",
                actorId, UserMappingService.sanitizeForMessage(workgroupName), created.id
            )
            created
        } catch (e: Exception) {
            workgroupRepository.findByNameIgnoreCase(workgroupName).orElseThrow { e }
        }
    }

    /**
     * Reject display names that could never be a workgroup: `Workgroup.name` is
     * constrained to letters, digits, spaces and hyphens, 1..100 characters. Creating
     * one anyway would either fail at the DB or, worse, persist an entity that violates
     * its own contract — so the pair is reported instead.
     */
    private fun validateWorkgroupName(workgroupName: String): String? = when {
        workgroupName.length > MAX_WORKGROUP_NAME_LENGTH ->
            "Workgroup name '${UserMappingService.sanitizeForMessage(workgroupName)}' exceeds " +
                "$MAX_WORKGROUP_NAME_LENGTH characters"
        !WORKGROUP_NAME_PATTERN.matches(workgroupName) ->
            "Display name yields workgroup name " +
                "'${UserMappingService.sanitizeForMessage(workgroupName)}', which may contain " +
                "only letters, numbers, spaces and hyphens"
        else -> null
    }

    private fun isNewer(candidate: Instant?, current: Instant?): Boolean = when {
        candidate == null -> false
        current == null -> true
        else -> candidate.isAfter(current)
    }

    private fun summarize(
        results: List<WorkgroupAccountLinkInfo>,
        dryRun: Boolean,
        truncatedInput: Boolean
    ): WorkgroupAccountLinkSummary = WorkgroupAccountLinkSummary(
        processed = results.size,
        workgroupsCreated = results.count { it.workgroupCreated && it.error == null },
        linked = results.count { it.linked && it.error == null },
        alreadyLinked = results.count { it.alreadyLinked },
        failed = results.count { it.error != null },
        dryRun = dryRun,
        links = results.take(MAX_REPORTED_LINKS),
        truncated = truncatedInput || results.size > MAX_REPORTED_LINKS
    )

    companion object {
        /** Every AWS account workgroup is named after its display name with this prefix. */
        const val WORKGROUP_NAME_PREFIX = "aws-"

        /** Upper bound on pairs processed in one run (A04: unbounded is a design bug). */
        const val MAX_PAIRS = 20_000

        /** Upper bound on the per-account rows returned to a caller. Counters stay exact. */
        const val MAX_REPORTED_LINKS = 500

        private const val MAX_WORKGROUP_NAME_LENGTH = 100
        private const val WORKGROUP_DESCRIPTION = "Auto-created from AWS account display name"

        private val ACCOUNT_ID_PATTERN = Regex("^\\d{12}$")

        /** Mirrors the @Pattern on Workgroup.name — kept identical on purpose. */
        private val WORKGROUP_NAME_PATTERN = Regex("^[a-zA-Z0-9 -]+$")

        /** The naming rule, in one place. */
        fun workgroupNameFor(displayName: String): String =
            WORKGROUP_NAME_PREFIX + displayName.trim()
    }
}
