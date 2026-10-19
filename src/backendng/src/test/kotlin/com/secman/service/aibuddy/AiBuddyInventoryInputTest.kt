package com.secman.service.aibuddy

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class AiBuddyInventoryInputTest {
    private val mapper = ObjectMapper()

    @Test
    fun `inventory accepts only bounded public facts and paired Firefox version and channel`() {
        val input = AiBuddyInventoryInput.parse(mapper.readTree("""{"build":"17763.1234","architecture":"x64","edition":"Standard","firefoxVersion":"128.7","firefoxChannel":"ESR"}"""))
        assertThat(input.firefoxChannel).isEqualTo("ESR")
        assertThat(input.build).isEqualTo("17763.1234")
        for (json in listOf("""{"hostname":"private-host"}""", """{"build":"1809"}""", """{"firefoxVersion":"128.7"}""",
            """{"firefoxVersion":"128.7; command","firefoxChannel":"ESR"}""", """{"firefoxVersion":"128.7","firefoxChannel":"NIGHTLY"}""")) {
            assertThatThrownBy { AiBuddyInventoryInput.parse(mapper.readTree(json)) }.isInstanceOf(AiBuddyFailure::class.java)
        }
    }
}
