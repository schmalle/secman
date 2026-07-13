package com.secman.repository

import com.secman.domain.GithubOwnerEmailMapping
import io.micronaut.data.annotation.Repository
import io.micronaut.data.jpa.repository.JpaRepository
import java.util.Optional

@Repository
interface GithubOwnerEmailMappingRepository : JpaRepository<GithubOwnerEmailMapping, Long> {

    fun findByOwnerIgnoreCase(owner: String): Optional<GithubOwnerEmailMapping>
}
