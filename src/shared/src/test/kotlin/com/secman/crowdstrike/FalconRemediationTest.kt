package com.secman.crowdstrike

import com.secman.crowdstrike.dto.CrowdStrikeRemediationDto
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class FalconRemediationTest {
    private val noFix = CrowdStrikeRemediationDto("No fix or vendor recommendation")
    private val update = CrowdStrikeRemediationDto("Update the package")

    @Test
    fun `resolves additional application advice even when inline guidance exists`() {
        val finding = mapOf(
            "remediation" to mapOf("entities" to listOf(mapOf("id" to "r1", "action" to update.action))),
            "apps" to listOf(mapOf("remediation" to mapOf("ids" to listOf("r1", "r2"))))
        )
        val additional = requireNotNull(FalconRemediation.recommendation(mapOf(
            "action" to "Upgrade the library", "title" to "Vendor update",
            "vendor_url" to "https://vendor.example.test/advisory"
        )))
        assertThat(FalconRemediation.unresolvedIds(finding)).containsExactly("r2")
        assertThat(FalconRemediation.recommendations(finding, emptyMap())).isNull()
        assertThat(FalconRemediation.recommendations(finding, mapOf("r2" to additional)))
            .containsExactly(update, additional)
        assertThat(additional.vendorUrl).isEqualTo("https://vendor.example.test/advisory")
        assertThat(additional.title).isEqualTo("Vendor update")
    }

    @Test
    fun `keeps explicit no fix distinct from empty and missing guidance`() {
        assertThat(FalconRemediation.recommendations(mapOf("remediation" to noFix.action), emptyMap()))
            .containsExactly(noFix)
        assertThat(FalconRemediation.recommendations(mapOf("remediation" to mapOf("ids" to emptyList<String>())), emptyMap()))
            .isEmpty()
        assertThat(FalconRemediation.recommendations(emptyMap<String, Any>(), emptyMap())).isNull()
        assertThat(FalconRemediation.merge(listOf(listOf(noFix, update), listOf(update)))).containsExactly(update)
    }

    @Test
    fun `distinguishes positive evidence from absent and unresolved remediation`() {
        fun classify(vararg actions: String) = FalconRemediation.noVendorFix(mapOf(
            "remediation" to mapOf("entities" to actions.map { mapOf("action" to it) })
        ))
        assertThat(classify("No fix or vendor recommendation")).isTrue()
        assertThat(classify("Update the package")).isFalse()
        assertThat(classify("No fix or vendor recommendation", "Update the package")).isFalse()
        val refs = mapOf("apps" to listOf(mapOf("remediation" to mapOf("ids" to listOf("r1", "r2")))))
        assertThat(FalconRemediation.noVendorFix(refs, mapOf("r1" to noFix))).isNull()
        assertThat(FalconRemediation.noVendorFix(refs, mapOf("r1" to noFix, "r2" to noFix))).isTrue()
        assertThat(FalconRemediation.noVendorFix(refs, mapOf("r2" to update))).isFalse()
        assertThat(classify()).isNull()
        assertThat(FalconRemediation.noVendorFix(emptyMap<String, Any>())).isNull()
    }
}
