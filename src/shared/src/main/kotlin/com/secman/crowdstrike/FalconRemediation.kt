package com.secman.crowdstrike

/** Falcon returns remediation inline or as references on the affected applications. */
object FalconRemediation {
    private const val NO_FIX = "No fix or vendor recommendation"
    private val validId = Regex("[A-Za-z0-9_-]{1,128}")

    fun referencedIds(finding: Map<*, *>): List<String>? {
        val facet = finding["remediation"] as? Map<*, *>
        val appFacets = (finding["apps"] as? List<*>)?.takeIf { it.isNotEmpty() }?.map {
            (it as? Map<*, *>)?.get("remediation") as? Map<*, *>
        }.orEmpty()
        val lists = (listOfNotNull(facet) + appFacets.filterNotNull()).mapNotNull { it["ids"] as? List<*> }
        if (lists.isEmpty()) return null
        val ids = lists.flatten().map { (it as? String)?.takeIf(validId::matches) ?: return null }
        if (facet?.containsKey("ids") != true && appFacets.any { it?.get("ids") !is List<*> }) return null
        return ids.distinct()
    }

    fun advice(entity: Map<*, *>): Boolean? {
        val action = sequenceOf("action", "recommendation", "title")
            .mapNotNull { (entity[it] as? String)?.trim()?.takeIf(String::isNotEmpty) }.firstOrNull() ?: return null
        return action.trimEnd('.').equals(NO_FIX, ignoreCase = true)
    }

    fun noVendorFix(finding: Map<*, *>, resolved: Map<String, Boolean> = emptyMap()): Boolean? {
        val facet = finding["remediation"]
        if (facet is String) return facet.trim().trimEnd('.').equals(NO_FIX, true).takeIf { it }
        val remediation = facet as? Map<*, *>
        val entities = remediation?.get("entities") as? List<*>
        val inline = entities.orEmpty().map { (it as? Map<*, *>)?.let(::advice) }
        val ids = referencedIds(finding)
        if (inline.contains(false)) return false
        if (!ids.isNullOrEmpty()) {
            val inlineById = entities.orEmpty().filterIsInstance<Map<*, *>>().associateBy { it["id"] }
            val decisions = ids.map { id -> inlineById[id]?.let(::advice) ?: resolved[id] }
            return when {
                false in decisions -> false
                null in decisions || null in inline -> null
                else -> true
            }
        }
        if (inline.isNotEmpty()) return true.takeIf { null !in inline }
        return remediation?.let(::advice) ?: true.takeIf { ids != null && ids.isEmpty() }
    }
}
