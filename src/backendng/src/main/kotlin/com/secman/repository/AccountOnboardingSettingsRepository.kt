package com.secman.repository

import com.secman.domain.AccountOnboardingSettings
import io.micronaut.data.annotation.Repository
import io.micronaut.data.jpa.repository.JpaRepository
import java.util.Optional

@Repository
interface AccountOnboardingSettingsRepository : JpaRepository<AccountOnboardingSettings, Long> {
    fun findBySingletonKey(singletonKey: Int): Optional<AccountOnboardingSettings>
}
