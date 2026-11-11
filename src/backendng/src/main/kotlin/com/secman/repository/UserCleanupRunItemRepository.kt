package com.secman.repository

import com.secman.domain.UserCleanupRunItem
import io.micronaut.data.annotation.Repository
import io.micronaut.data.jpa.repository.JpaRepository
import io.micronaut.data.model.Page
import io.micronaut.data.model.Pageable

@Repository
interface UserCleanupRunItemRepository : JpaRepository<UserCleanupRunItem, Long> {
    fun findByRunIdOrderById(runId: Long, pageable: Pageable): Page<UserCleanupRunItem>
}
