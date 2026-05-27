package com.secman.repository

import com.secman.domain.WorkgroupAdDomain
import io.micronaut.data.annotation.Query
import io.micronaut.data.annotation.Repository
import io.micronaut.data.jpa.repository.JpaRepository
import java.util.Optional

@Repository
interface WorkgroupAdDomainRepository : JpaRepository<WorkgroupAdDomain, Long> {

    @Query("SELECT DISTINCT wad.adDomain FROM WorkgroupAdDomain wad WHERE EXISTS (SELECT 1 FROM wad.workgroup w JOIN w.users u WHERE u.id = :userId)")
    fun findDistinctAdDomainsByUserId(userId: Long): List<String>

    @Query("SELECT wad FROM WorkgroupAdDomain wad LEFT JOIN FETCH wad.createdBy JOIN FETCH wad.workgroup WHERE wad.workgroup.id = :workgroupId")
    fun findByWorkgroupId(workgroupId: Long): List<WorkgroupAdDomain>

    fun findByWorkgroupIdAndAdDomain(workgroupId: Long, adDomain: String): Optional<WorkgroupAdDomain>

    fun existsByWorkgroupIdAndAdDomain(workgroupId: Long, adDomain: String): Boolean

    fun deleteByWorkgroupId(workgroupId: Long): Long

    fun countByWorkgroupId(workgroupId: Long): Long

    @Query("UPDATE WorkgroupAdDomain wad SET wad.createdBy = NULL WHERE wad.createdBy.id = :userId")
    fun nullifyCreatedByForUser(userId: Long): Int
}
