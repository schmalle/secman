package com.secman.crowdstrike

/** Snapshot buckets, including provider results without a usable severity. */
object CrowdStrikeSeverityScope {
    val ALL = listOf("CRITICAL", "HIGH", "MEDIUM", "LOW", "INFORMATIONAL", "UNKNOWN")

    fun fromQuery(severity: String): List<String> =
        if (severity.equals("ALL", ignoreCase = true)) ALL
        else severity.split(",").map(String::trim).filter(String::isNotBlank)
}
