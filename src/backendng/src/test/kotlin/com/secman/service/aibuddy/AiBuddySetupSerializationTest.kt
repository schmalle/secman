package com.secman.service.aibuddy

import com.secman.testutil.BaseIntegrationTest
import io.micronaut.json.JsonMapper
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

@MicronautTest(environments = ["test"], transactional = false)
class AiBuddySetupSerializationTest : BaseIntegrationTest() {
    @Inject lateinit var jsonMapper: JsonMapper

    @Test
    fun `production mapper includes empty reasons on compatible provider routes`() {
        val result = AiBuddySetupResult(true, emptyList(),
            listOf(AiBuddyProviderOption("vendor", "Vendor", true, emptyList())))
        val wire = requireNotNull(jsonMapper.readValue(jsonMapper.writeValueAsString(result), Map::class.java))
        assertThat(wire["ready"]).isEqualTo(true)
        assertThat(wire["reasons"]).isEqualTo(emptyList<String>())
        val provider = (wire["providers"] as List<*>).single() as Map<*, *>
        assertThat(provider["compatible"]).isEqualTo(true)
        assertThat(provider["reasons"]).isEqualTo(emptyList<String>())
    }

    @Test
    fun `production mapper includes empty provider lists and false readiness`() {
        val result = AiBuddySetupResult(false, emptyList(), emptyList())
        val wire = requireNotNull(jsonMapper.readValue(jsonMapper.writeValueAsString(result), Map::class.java))
        assertThat(wire["ready"]).isEqualTo(false)
        assertThat(wire["reasons"]).isEqualTo(emptyList<String>())
        assertThat(wire["providers"]).isEqualTo(emptyList<AiBuddyProviderOption>())
    }
}
