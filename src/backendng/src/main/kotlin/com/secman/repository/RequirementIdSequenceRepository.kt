package com.secman.repository

import com.secman.domain.RequirementIdSequence
import io.micronaut.data.annotation.Repository
import io.micronaut.data.jpa.repository.JpaRepository

@Repository
interface RequirementIdSequenceRepository : JpaRepository<RequirementIdSequence, Long>
// Note: the former findByIdForUpdate(id) was a plain SELECT despite its name (the explicit
// @Query carried no lock). Locked access now goes through RequirementIdService, which uses
// EntityManager.find(..., LockModeType.PESSIMISTIC_WRITE) for a real SELECT ... FOR UPDATE.
