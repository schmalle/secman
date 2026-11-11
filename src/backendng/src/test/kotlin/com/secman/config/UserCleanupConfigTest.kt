package com.secman.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class UserCleanupConfigTest {

    @Test
    fun `defaults are safe and valid`() {
        val config = UserCleanupConfig()

        assertThat(config.enabled).isFalse()
        assertThat(config.dryRun).isTrue()
        assertThat(config.inactiveDays).isEqualTo(365)
        assertThat(config.warningDays).isEqualTo(30)
        assertThat(config.maxDeletePercent).isEqualTo(10)
        assertThat(config.maxUsersPerRun).isEqualTo(500)
        assertThat(config.validate()).isNull()
    }

    @Test
    fun `config rejects warning-days at or above inactive-days`() {
        assertThat(UserCleanupConfig(inactiveDays = 30, warningDays = 30).validate()).isNotNull()
    }

    @Test
    fun `config rejects non-positive and out-of-range values`() {
        assertThat(UserCleanupConfig(inactiveDays = 0).validate()).isNotNull()
        assertThat(UserCleanupConfig(warningDays = 0).validate()).isNotNull()
        assertThat(UserCleanupConfig(maxDeletePercent = 101).validate()).isNotNull()
        assertThat(UserCleanupConfig(maxUsersPerRun = 0).validate()).isNotNull()
    }
}
