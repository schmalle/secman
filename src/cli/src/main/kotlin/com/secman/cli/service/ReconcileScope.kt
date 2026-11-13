package com.secman.cli.service

import com.secman.crowdstrike.client.QueriedHost

/**
 * Hosts the reconcile sweep may clean: the run's queried population minus every host whose
 * fetch or persist failed, because their rows were never refreshed.
 *
 * A failed name excludes a host when the names are the same machine by the backend's
 * `matchesHostname` rule: equal ignoring case, or one side is a bare short name with the
 * same short label. Two different fully qualified names (`web01.a.example` and
 * `web01.b.example`) are different machines, so one failure no longer shields the other's
 * patched findings from the sweep.
 */
fun reconcileScopeHosts(
    queriedHosts: Set<QueriedHost>,
    fetchFailedHosts: Set<QueriedHost>,
    failedHostnames: Collection<String>,
    failedAids: Set<String>,
): Set<QueriedHost> {
    val failedNames = failedHostnames.map(String::trim).filter(String::isNotBlank)
    return queriedHosts.minus(fetchFailedHosts).filterNot { host ->
        host.crowdStrikeAid in failedAids ||
            host.hostname?.let { name -> failedNames.any { sameMachineName(name, it) } } == true
    }.toSet()
}

internal fun sameMachineName(first: String, second: String): Boolean {
    val a = first.trim()
    val b = second.trim()
    if (a.equals(b, ignoreCase = true)) return true
    val bothQualified = a.contains('.') && b.contains('.')
    return !bothQualified && a.substringBefore('.').equals(b.substringBefore('.'), ignoreCase = true)
}
