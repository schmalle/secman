package com.secman.crowdstrike

import com.secman.crowdstrike.dto.CrowdStrikeRemediationDto

/** Falcon returns remediation inline or as references on the affected applications. */
object FalconRemediation {
    private const val NO_FIX = "No fix or vendor recommendation"
    private val validId = Regex("[A-Za-z0-9_-]{1,128}")

    private fun facets(finding: Map<*, *>): List<Map<*, *>> =
        listOfNotNull(finding["remediation"] as? Map<*, *>) +
            (finding["apps"] as? List<*>).orEmpty().mapNotNull {
                (it as? Map<*, *>)?.get("remediation") as? Map<*, *>
            }

    private fun inlineEntities(facet: Map<*, *>): List<Map<*, *>> =
        listOf(facet) + (facet["entities"] as? List<*>).orEmpty().filterIsInstance<Map<*, *>>()

    private fun references(facets: List<Map<*, *>>) = facets.flatMap { facet ->
        (facet["ids"] as? List<*>).orEmpty().mapNotNull { (it as? String)?.takeIf(validId::matches) }
    }.distinct()

    private fun inlineById(facets: List<Map<*, *>>) = facets.flatMap(::inlineEntities).mapNotNull { entity ->
        val id = entity["id"] as? String ?: return@mapNotNull null
        recommendation(entity)?.let { id to it }
    }.toMap()

    fun unresolvedIds(finding: Map<*, *>): List<String> {
        val facets = facets(finding)
        return references(facets) - inlineById(facets).keys
    }

    fun recommendation(entity: Map<*, *>): CrowdStrikeRemediationDto? {
        val title = (entity["title"] as? String)?.trim()?.takeIf(String::isNotEmpty)
        val action = sequenceOf("action", "recommendation")
            .mapNotNull { (entity[it] as? String)?.trim()?.takeIf(String::isNotEmpty) }
            .firstOrNull() ?: title ?: return null
        val url = (entity["vendor_url"] as? String)?.takeIf(String::isNotBlank)
            ?: entity["link"] as? String
        return CrowdStrikeRemediationDto(action, title?.takeUnless { it == action }, url)
    }

    fun recommendations(
        finding: Map<*, *>, resolved: Map<String, CrowdStrikeRemediationDto>
    ): List<CrowdStrikeRemediationDto>? {
        val facets = facets(finding)
        val inline = facets.flatMap(::inlineEntities).mapNotNull(::recommendation)
        val text = (finding["remediation"] as? String)?.trim()?.takeIf(String::isNotEmpty)
        val refs = references(facets)
        val byId = inlineById(facets)
        if (refs.any { it !in byId && it !in resolved }) return null

        val guidance = listOfNotNull(text?.let { CrowdStrikeRemediationDto(it) }) +
            inline + refs.mapNotNull { byId[it] ?: resolved[it] }
        if (guidance.isEmpty() && noVendorFix(finding, resolved) != true) return null
        return merge(listOf(guidance))
    }

    /** Merge overlapping findings into a stable, bounded display order. */
    fun merge(lists: List<List<CrowdStrikeRemediationDto>?>): List<CrowdStrikeRemediationDto>? {
        val guidance = lists.filterNotNull().flatten().mapNotNull { entry ->
            val action = entry.action.trim().take(1000).takeIf(String::isNotEmpty) ?: return@mapNotNull null
            CrowdStrikeRemediationDto(
                action,
                entry.title?.trim()?.take(200)?.takeIf { it.isNotEmpty() && it != action },
                entry.vendorUrl?.trim()?.takeIf { it.isNotEmpty() && it.length <= 2048 }
            )
        }.distinct()
        if (guidance.isEmpty() && lists.any { it == null }) return null
        val actionable = guidance.filterNot { isNoFix(it.action) }
        return actionable.ifEmpty { guidance }
            .sortedWith(compareBy({ it.action }, { it.title }, { it.vendorUrl })).take(8)
    }

    private fun isNoFix(action: String): Boolean = action.trim().trimEnd('.').equals(NO_FIX, ignoreCase = true)

    // Exception review uses the original tri-state classification, independently of display text.
    fun noVendorFix(finding: Map<*, *>, resolved: Map<String, CrowdStrikeRemediationDto> = emptyMap()): Boolean? {
        val facet = finding["remediation"]
        if (facet is String) return isNoFix(facet).takeIf { it }
        val remediation = facet as? Map<*, *>
        val entities = remediation?.get("entities") as? List<*>
        val inline = entities.orEmpty().map { (it as? Map<*, *>)?.let(::recommendation)?.let { rec -> isNoFix(rec.action) } }
        val ids = classificationIds(finding, remediation)
        if (inline.contains(false)) return false
        if (!ids.isNullOrEmpty()) {
            val inlineById = entities.orEmpty().filterIsInstance<Map<*, *>>().associateBy { it["id"] }
            val decisions = ids.map { id ->
                (inlineById[id]?.let(::recommendation) ?: resolved[id])?.let { isNoFix(it.action) }
            }
            return when {
                false in decisions -> false
                null in decisions || null in inline -> null
                else -> true
            }
        }
        if (inline.isNotEmpty()) return true.takeIf { null !in inline }
        return remediation?.let(::recommendation)?.let { isNoFix(it.action) }
            ?: true.takeIf { ids != null && ids.isEmpty() }
    }

    private fun classificationIds(finding: Map<*, *>, facet: Map<*, *>?): List<String>? {
        val appFacets = (finding["apps"] as? List<*>).orEmpty().map {
            (it as? Map<*, *>)?.get("remediation") as? Map<*, *>
        }
        val lists = (listOfNotNull(facet) + appFacets.filterNotNull()).mapNotNull { it["ids"] as? List<*> }
        if (lists.isEmpty() || (facet?.containsKey("ids") != true && appFacets.any { it?.get("ids") !is List<*> })) return null
        return lists.flatten().map { (it as? String)?.takeIf(validId::matches) ?: return null }.distinct()
    }
}
