package com.secman.crowdstrike.dto

import io.micronaut.serde.annotation.Serdeable
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.Instant

/** Falcon timestamps, deliberately separate from SecMan's import observation times. */
@Serdeable
data class CrowdStrikeDeviceRecord(
    @field:NotBlank @field:Size(max = 64)
    val aid: String,
    @field:NotBlank @field:Size(max = 255)
    val hostname: String,
    @field:Size(max = 255)
    val instanceId: String? = null,
    @field:Size(max = 255)
    val cloudAccountId: String? = null,
    @field:Size(max = 255)
    val adDomain: String? = null,
    val firstSeen: Instant? = null,
    val lastSeen: Instant? = null,
    @field:Size(max = 64)
    val productType: String? = null
)

@Serdeable
data class CrowdStrikeDeviceSelection(
    @field:Valid
    val selected: CrowdStrikeDeviceRecord,
    @field:Valid @field:Size(max = 100)
    val superseded: List<CrowdStrikeDeviceRecord> = emptyList()
)

/** Never collapse equal short names across FQDNs, AD domains or cloud accounts. */
fun CrowdStrikeDeviceRecord.hostnameScope(): List<String> =
    listOf(hostname, adDomain.orEmpty(), cloudAccountId.orEmpty()).map { it.trim().lowercase() }

fun selectLatestCrowdStrikeDevices(records: List<CrowdStrikeDeviceRecord>): List<CrowdStrikeDeviceSelection> {
    require(records.map { it.aid }.distinct().size == records.size) { "Duplicate Falcon device IDs" }
    require(records.all { it.aid.matches(Regex("[A-Za-z0-9_-]{1,64}")) &&
        it.hostname.matches(Regex("[A-Za-z0-9_.-]{1,255}")) && it.firstSeen != null }) {
        "Incomplete Falcon device identity or enrollment timestamp"
    }
    // A cloud instance may keep its AID history under different hostname aliases.
    val parents = records.indices.toMutableList()
    fun root(index: Int): Int {
        var current = index
        while (parents[current] != current) current = parents[current]
        var child = index
        while (parents[child] != current) {
            val next = parents[child]
            parents[child] = current
            child = next
        }
        return current
    }
    val accountsByHostname = records.groupBy { it.hostnameScope().take(2) }.mapValues { (_, group) ->
        group.map { it.cloudAccountId.orEmpty().trim().lowercase() }.filter(String::isNotBlank).distinct()
    }
    val owners = mutableMapOf<List<String>, Int>()
    records.forEachIndexed { index, device ->
        val scope = device.hostnameScope()
        // Missing account metadata is compatible only with one unambiguous hostname account.
        val account = scope.last().ifBlank { accountsByHostname.getValue(scope.take(2)).singleOrNull().orEmpty() }
        // A reused hostname is not proof that two cloud instances are the same machine.
        // Records without an instance ID must not bridge otherwise independent instances.
        val instance = device.instanceId?.trim()?.takeIf(String::isNotBlank)
        val keys = if (instance != null) {
            listOf(listOf("instance", instance.lowercase(), account))
        } else {
            listOf(listOf("hostname") + scope.take(2) + account)
        }
        keys.forEach { key ->
            owners.putIfAbsent(key, index)?.let { previous -> parents[root(index)] = root(previous) }
        }
    }
    return records.indices.groupBy(::root).values.map { indices ->
        val group = indices.map(records::get)
        require(group.size <= 101) { "Too many Falcon device identities for one hostname" }
        val ordered = group.sortedWith(compareByDescending<CrowdStrikeDeviceRecord> { it.firstSeen }
            .thenByDescending { it.lastSeen ?: Instant.MIN })
        val selected = ordered.first()
        require(ordered.drop(1).none { it.firstSeen == selected.firstSeen && it.lastSeen == selected.lastSeen }) {
            "Ambiguous latest Falcon device timestamp"
        }
        CrowdStrikeDeviceSelection(selected, ordered.drop(1))
    }
}
