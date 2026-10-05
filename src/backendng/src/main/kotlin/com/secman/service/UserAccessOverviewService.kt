package com.secman.service

import com.secman.domain.User
import com.secman.dto.*
import com.secman.repository.AssetAccessSql
import com.secman.repository.UserRepository
import io.micronaut.http.HttpStatus
import io.micronaut.http.exceptions.HttpStatusException
import io.micronaut.security.authentication.Authentication
import jakarta.inject.Singleton
import jakarta.persistence.EntityManager
import jakarta.transaction.Transactional
import java.time.Instant
import java.util.Locale

/** ADMIN diagnostics: target-user policy selects assets; grant rows only explain that selection. */
@Singleton
open class UserAccessOverviewService(
    private val users: UserRepository,
    private val filter: AssetFilterService,
    private val validation: InputValidationService,
    private val entityManager: EntityManager
) {
    private data class Grant(val kind: String, val value: String, val reason: AccessReason)

    @Transactional
    open fun overview(email: String, page: Int, size: Int): UserAccessOverview {
        if (page < 0 || size !in 1..500 || page.toLong() * size > Int.MAX_VALUE) {
            throw HttpStatusException(HttpStatus.BAD_REQUEST, "Invalid pagination: page >= 0 and size 1..500 required")
        }
        val normalized = email.trim().lowercase(Locale.ROOT)
        if (!validation.validateEmail(normalized).isValid) {
            throw HttpStatusException(HttpStatus.BAD_REQUEST, "Invalid email address")
        }
        val user = users.findByEmailIgnoreCase(normalized).orElseThrow {
            HttpStatusException(HttpStatus.NOT_FOUND, "User not found")
        }
        val roles = user.roles.map { it.name }.sorted()
        val identity = AccessOverviewUser(requireNotNull(user.id), user.username, user.email, user.enabled, roles)
        val globalReasons = roles.filter { it in setOf("ADMIN", "SECCHAMPION") }.map { AccessReason("GLOBAL_ROLE", sourceName = it) }
        val vulnerabilityAccess = user.enabled && roles.any { it in setOf("ADMIN", "VULN", "SECCHAMPION") }
        if (!user.enabled) {
            return UserAccessOverview(identity, Instant.now().toString(), false, false, 0, emptyList(), emptyList(), emptyList(), page, size, 0)
        }
        val target = Authentication.build(user.username, roles, mapOf("userId" to user.id!!, "email" to user.email))
        val assets = filter.getAccessibleAssetPage(target, page, size)
        val grants = grants(user)
        val scopes = scopeEntries(user, grants, globalReasons)
        val direct = directReasons(user.id!!, assets.content.map { requireNotNull(it.id) })
        val entries = assets.content.map { asset ->
            val reasons = globalReasons + direct[asset.id].orEmpty() + grants.filter {
                (it.kind == "AWS" && it.value == asset.cloudAccountId) ||
                    (it.kind == "AD" && it.value.equals(asset.adDomain, ignoreCase = true))
            }.map { it.reason }
            AccessOverviewAsset(asset.id!!, asset.name, asset.cloudAccountId, asset.adDomain, reasons.distinct())
        }
        return UserAccessOverview(identity, Instant.now().toString(), vulnerabilityAccess, globalReasons.isNotEmpty(),
            assets.totalSize, scopes.first, scopes.second, entries, page, size, assets.totalPages)
    }

    private fun grants(user: User): List<Grant> = rows(GRANTS, user).map { row ->
        Grant(row[0] as String, row[1] as String,
            AccessReason(row[2] as String, (row[3] as? Number)?.toLong(), row[4] as? String))
    }.distinct().sortedWith(compareBy({ it.kind }, { it.value }, { it.reason.type }, { it.reason.sourceId }))

    private fun scopeEntries(user: User, grants: List<Grant>, global: List<AccessReason>): Pair<List<AccessScopeEntry>, List<AccessScopeEntry>> {
        // Aggregate distinct scope values in SQL; never load the entire asset inventory for counts.
        val scope = if (global.isNotEmpty()) "TRUE" else "a.id IN (${AssetAccessSql.IDS})"
        val counts = rows("""
            SELECT 'AWS', a.cloud_account_id, COUNT(*) FROM asset a
            WHERE $scope AND a.cloud_account_id IS NOT NULL AND a.cloud_account_id <> '' GROUP BY a.cloud_account_id
            UNION ALL
            SELECT 'AD', LOWER(a.ad_domain), COUNT(*) FROM asset a
            WHERE $scope AND a.ad_domain IS NOT NULL AND a.ad_domain <> '' GROUP BY LOWER(a.ad_domain)
        """, if (global.isEmpty()) user else null)
        val accountIds = (counts.filter { it[0] == "AWS" }.map { it[1] as String } +
            grants.filter { it.kind == "AWS" }.map { it.value }).distinct()
        val names = accountNames(accountIds)
        fun entries(kind: String): List<AccessScopeEntry> {
            val represented = counts.filter { it[0] == kind }.associate { it[1] as String to (it[2] as Number).toLong() }
            val configured = grants.filter { it.kind == kind }.groupBy { it.value }
            return (represented.keys + configured.keys).sorted().map { value ->
                val reasons = (global + configured[value].orEmpty().map { it.reason }).distinct()
                AccessScopeEntry(value, if (kind == "AWS") names[value] else null,
                    represented[value] ?: 0, reasons.isNotEmpty(), reasons)
            }
        }
        return entries("AWS") to entries("AD")
    }

    @Suppress("UNCHECKED_CAST")
    private fun accountNames(ids: List<String>): Map<String, String> = ids.chunked(500).flatMap { chunk ->
        entityManager.createNativeQuery("""
            SELECT aws_account_id, MIN(aws_account_name) FROM user_mapping
            WHERE aws_account_id IN (:ids) AND aws_account_name IS NOT NULL GROUP BY aws_account_id
        """).setParameter("ids", chunk).resultList as List<Array<Any>>
    }.associate { it[0] as String to it[1] as String }

    private fun directReasons(userId: Long, ids: List<Long>): Map<Long, List<AccessReason>> {
        if (ids.isEmpty()) return emptyMap()
        val query = entityManager.createNativeQuery("""
            SELECT aw.asset_id, w.id, w.name FROM asset_workgroups aw
            JOIN workgroup w ON w.id = aw.workgroup_id
            JOIN user_workgroups uw ON uw.workgroup_id = w.id
            WHERE uw.user_id = :userId AND w.enabled = TRUE AND aw.asset_id IN (:ids)
            ORDER BY aw.asset_id, w.id
        """).setParameter("userId", userId).setParameter("ids", ids)
        @Suppress("UNCHECKED_CAST")
        val rows = query.resultList as List<Array<Any?>>
        return rows.groupBy({ (it[0] as Number).toLong() },
            { AccessReason("WORKGROUP_ASSET", (it[1] as Number).toLong(), it[2] as String) })
    }

    @Suppress("UNCHECKED_CAST")
    private fun rows(sql: String, user: User? = null): List<Array<Any?>> {
        val query = entityManager.createNativeQuery(sql)
        if (user != null) query.setParameter("userId", user.id).setParameter("userEmail", user.email)
        return query.resultList as List<Array<Any?>>
    }

    companion object {
        private const val GRANTS = """
            SELECT 'AWS', um.aws_account_id, 'PERSONAL_MAPPING', NULL, NULL FROM user_mapping um
            WHERE um.email = :userEmail AND um.aws_account_id IS NOT NULL
            UNION
            SELECT 'AD', LOWER(um.domain), 'PERSONAL_MAPPING', NULL, NULL FROM user_mapping um
            WHERE um.email = :userEmail AND um.domain IS NOT NULL
            UNION
            SELECT 'AWS', waa.aws_account_id, 'WORKGROUP_AWS', w.id, w.name FROM workgroup_aws_account waa
            JOIN workgroup w ON w.id = waa.workgroup_id JOIN user_workgroups uw ON uw.workgroup_id = w.id
            WHERE uw.user_id = :userId AND w.enabled = TRUE
            UNION
            SELECT 'AD', LOWER(wad.ad_domain), 'WORKGROUP_AD', w.id, w.name FROM workgroup_ad_domain wad
            JOIN workgroup w ON w.id = wad.workgroup_id JOIN user_workgroups uw ON uw.workgroup_id = w.id
            WHERE uw.user_id = :userId AND w.enabled = TRUE
            UNION
            SELECT 'AWS', um.aws_account_id, 'AWS_SHARING', acs.id, u.email FROM aws_account_sharing acs
            JOIN users u ON u.id = acs.source_user_id JOIN user_mapping um ON um.email = u.email
            WHERE acs.target_user_id = :userId AND um.aws_account_id IS NOT NULL AND (
                NOT EXISTS (SELECT 1 FROM aws_account_sharing_account asa WHERE asa.sharing_id = acs.id)
                OR EXISTS (SELECT 1 FROM aws_account_sharing_account asa WHERE asa.sharing_id = acs.id AND asa.aws_account_id = um.aws_account_id)
            )
        """
    }
}
