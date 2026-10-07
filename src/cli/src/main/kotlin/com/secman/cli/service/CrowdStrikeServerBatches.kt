package com.secman.cli.service

import com.secman.crowdstrike.client.QueriedHost
import com.secman.crowdstrike.dto.CrowdStrikeVulnerabilityDto
import com.secman.crowdstrike.dto.resolveHostIp
import java.time.Instant

/** Build one replacement per device identity, including siblings with no findings. */
fun buildCrowdStrikeServerBatches(
    rows: List<CrowdStrikeVulnerabilityDto>,
    devices: Set<QueriedHost> = emptySet(),
    failedAids: Set<String> = emptySet()
): Map<String, ServerVulnerabilityBatch> {
    val byAid = devices.mapNotNull { device -> device.crowdStrikeAid?.let { it to device } }.toMap()
    fun deviceIdentity(device: QueriedHost): String = device.deviceSelection?.selected?.let {
        "selected:${it.aid}"
    } ?: identityForLegacyDevice(device.hostname, device.instanceId)
    fun rowIdentity(row: CrowdStrikeVulnerabilityDto): String {
        val device = byAid[row.crowdStrikeAid]
        return device?.let(::deviceIdentity) ?: identityForLegacyDevice(row.hostname, row.cloudInstanceId)
    }
    val devicesByIdentity = devices.groupBy(::deviceIdentity)
    val failedNames = devices.filter { it.crowdStrikeAid in failedAids }
        .mapNotNull { it.hostname?.trim()?.substringBefore(".")?.lowercase() }.toSet()
    val failedIdentities = devices.filter { it.crowdStrikeAid in failedAids }
        .map(::deviceIdentity).toSet()
    val findingsByIdentity = rows.groupBy(::rowIdentity)
    return (findingsByIdentity.keys + devicesByIdentity.keys).associateWith { findingsByIdentity[it].orEmpty() }.filter { (key, findings) ->
        key !in failedIdentities && findings.none {
            it.crowdStrikeAid in failedAids || it.hostname.trim().substringBefore(".").lowercase() in failedNames ||
                byAid[it.crowdStrikeAid]?.hostname?.trim()?.substringBefore(".")?.lowercase() in failedNames
        }
    }.mapValues { (key, findings) ->
        val siblings = devicesByIdentity[key].orEmpty()
        val accounts = (siblings.mapNotNull { it.cloudAccountId } + findings.mapNotNull { it.cloudAccountId })
            .map(String::trim).filter(String::isNotBlank).distinctBy(String::lowercase)
        require(accounts.size <= 1) { "Conflicting cloud accounts in one device identity group" }
        val domains = (siblings.mapNotNull { it.adDomain } + findings.mapNotNull { it.adDomain })
            .map(String::trim).filter(String::isNotBlank).distinctBy(String::lowercase)
        if (key.startsWith("host:")) {
            require(domains.size <= 1) { "Ambiguous hostname across AD domains" }
            val fullNames = (siblings.mapNotNull { it.hostname } + findings.map { it.hostname })
                .map(String::trim).filter { it.contains('.') }.distinctBy(String::lowercase)
            require(fullNames.size <= 1) { "Ambiguous fully qualified hostnames in one short-name group" }
        }
        val representative = siblings.maxByOrNull { it.lastSeen ?: Instant.MIN }
        val first = findings.firstOrNull()
        val hostname = representative?.hostname?.takeIf(String::isNotBlank) ?: requireNotNull(first).hostname
        ServerVulnerabilityBatch(
            hostname = hostname,
            vulnerabilities = findings,
            cloudAccountId = representative?.cloudAccountId?.takeIf(String::isNotBlank) ?: accounts.singleOrNull(),
            cloudInstanceId = representative?.instanceId ?: first?.cloudInstanceId,
            adDomain = representative?.adDomain?.takeIf(String::isNotBlank) ?: domains.singleOrNull(),
            osVersion = representative?.osVersion ?: first?.osVersion,
            ip = representative?.ip ?: findings.resolveHostIp(),
            crowdStrikeAids = (siblings.mapNotNull { it.crowdStrikeAid } + findings.mapNotNull { it.crowdStrikeAid }).toSet(),
            deviceSelection = siblings.mapNotNull { it.deviceSelection }.also {
                require(it.isEmpty() || (it.size == 1 && siblings.size == 1)) { "Ambiguous selected Falcon devices" }
            }.singleOrNull().also { selection ->
                if (selection != null) require(siblings.size == 1 && findings.all { it.crowdStrikeAid == selection.selected.aid }) {
                    "Only the selected Falcon device may contribute findings"
                }
            }
        )
    }
}

private fun identityForLegacyDevice(hostname: String?, instance: String?): String =
    instance?.trim()?.takeIf(String::isNotBlank)?.let { "instance:${it.lowercase()}" }
        ?: "host:${hostname?.trim()?.substringBefore(".")?.lowercase().orEmpty()}"
