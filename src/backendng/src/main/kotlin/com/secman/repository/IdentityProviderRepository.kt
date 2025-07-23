package com.secman.repository

import com.secman.domain.IdentityProvider
import io.micronaut.data.annotation.Repository
import io.micronaut.data.jpa.repository.JpaRepository
import java.util.*

@Repository
interface IdentityProviderRepository : JpaRepository<IdentityProvider, Long> {
    
    fun findByEnabled(enabled: Boolean): List<IdentityProvider>
    
    fun findByNameIgnoreCase(name: String): Optional<IdentityProvider>
    
    fun existsByNameIgnoreCase(name: String): Boolean
    
    fun findByType(type: IdentityProvider.ProviderType): List<IdentityProvider>
}