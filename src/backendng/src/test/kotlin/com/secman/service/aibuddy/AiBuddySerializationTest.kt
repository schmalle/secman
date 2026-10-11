package com.secman.service.aibuddy

import com.secman.dto.AiBuddyCapabilities
import com.secman.dto.AiBuddySystem
import io.micronaut.serde.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class AiBuddySerializationTest {
    private val mapper = ObjectMapper.getDefault()

    @Test fun `empty report preserves collections zero counts and unknown inventory on the wire`() {
        val now = Instant.now()
        val snapshot = AiBuddySnapshot(AiBuddySystem(1, "fixture", null), now, null, null,
            emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), "snapshot")
        val report = RemediationPlanner().report("job", now.plusSeconds(60), snapshot,
            emptyList(), null, emptySet(), false, 7)
        val wire = mapper.writeValueAsString(report)
        for (key in listOf("actions", "findings", "evidence", "remediationObservations")) {
            assertThat(wire).contains("\"$key\":[]")
        }
        assertThat(wire).contains("\"consideredFindingCount\":0", "\"model\":null", "\"build\":null")
    }

    @Test fun `ineligible capability retains explicit false values and empty roles`() {
        assertThat(mapper.writeValueAsString(AiBuddyCapabilities(eligible = false, roles = emptyList(),
            available = false, unavailableReason = null)))
            .contains("\"eligible\":false", "\"available\":false", "\"roles\":[]", "\"unavailableReason\":null")
    }
}
