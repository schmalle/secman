package com.secman.domain

/**
 * The minimal vulnerability+asset projection the exception-match predicate needs.
 *
 * Implemented by every row shape that gets matched against active
 * [VulnerabilityException]s in memory (statistics-cache raw rows, status-filtered
 * page rows, and the (Vulnerability, Asset) entity pair via an adapter), so the
 * subject/scope match logic lives in exactly one place:
 * [com.secman.service.ExceptionMatchIndex].
 *
 * Keep the semantics in sync with the entity predicate
 * [VulnerabilityException.matches] and the native-SQL predicate
 * [com.secman.repository.ExceptionMatchSql.EXCEPTION_MATCH].
 */
interface ExceptionMatchable {
    val assetId: Long?
    val assetIp: String?
    val cloudAccountId: String?
    val osVersion: String?
    val vulnerabilityId: String?
    val vulnerableProductVersions: String?
}
