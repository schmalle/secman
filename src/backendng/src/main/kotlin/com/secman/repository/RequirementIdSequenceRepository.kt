package com.secman.repository

import com.secman.domain.RequirementIdSequence
import io.micronaut.data.annotation.Query
import io.micronaut.data.annotation.Repository
import io.micronaut.data.jpa.repository.JpaRepository
import java.util.*

@Repository
interface RequirementIdSequenceRepository : JpaRepository<RequirementIdSequence, Long> {

    @Query("SELECT s FROM RequirementIdSequence s WHERE s.id = :id", nativeQuery = false)
    fun findByIdForUpdate(id: Long): Optional<RequirementIdSequence>
}
