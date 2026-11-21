package com.secman.crowdstrike

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class FalconRemediationTest {
    @Test
    fun `distinguishes positive evidence from absent and unresolved remediation`() {
        fun classify(vararg actions: String) = FalconRemediation.noVendorFix(mapOf(
            "remediation" to mapOf("entities" to actions.map { mapOf("action" to it) })
        ))
        assertThat(classify("No fix or vendor recommendation")).isTrue()
        assertThat(classify("Update the package")).isFalse()
        assertThat(classify("No fix or vendor recommendation", "Update the package")).isFalse()
        val refs = mapOf("apps" to listOf(mapOf("remediation" to mapOf("ids" to listOf("r1", "r2")))))
        assertThat(FalconRemediation.noVendorFix(refs, mapOf("r1" to true))).isNull()
        assertThat(FalconRemediation.noVendorFix(refs, mapOf("r1" to true, "r2" to true))).isTrue()
        assertThat(FalconRemediation.noVendorFix(refs, mapOf("r2" to false))).isFalse()
        assertThat(classify()).isNull()
        assertThat(FalconRemediation.noVendorFix(emptyMap<String, Any>())).isNull()
    }
}
