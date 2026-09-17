package com.secman.crowdstrike.dto

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class DeviceTypeTest {
    @Test
    fun `server family covers servers and domain controllers only`() {
        assertThat(DeviceType.SERVER_FAMILY.atomicTypes())
            .containsExactly(DeviceType.SERVER, DeviceType.DOMAIN_CONTROLLER)
    }

    @Test
    fun `all covers every supported Falcon host category`() {
        assertThat(DeviceType.ALL.atomicTypes())
            .containsExactly(
                DeviceType.SERVER,
                DeviceType.DOMAIN_CONTROLLER,
                DeviceType.WORKSTATION
            )
    }

    @Test
    fun `domain controller has the exact Falcon filter`() {
        assertThat(DeviceType.DOMAIN_CONTROLLER.toFqlFilter())
            .isEqualTo("product_type_desc:'Domain Controller'")
        assertThat(DeviceType.fromString("domain_controller"))
            .isEqualTo(DeviceType.DOMAIN_CONTROLLER)
    }
}
