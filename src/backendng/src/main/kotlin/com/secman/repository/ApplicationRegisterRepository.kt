package com.secman.repository

import com.secman.domain.ApplicationRegister
import io.micronaut.data.annotation.Query
import io.micronaut.data.annotation.Repository
import io.micronaut.data.jpa.repository.JpaRepository
import java.util.Optional

import java.time.LocalDate

@Repository
interface ApplicationRegisterRepository : JpaRepository<ApplicationRegister, Long> {

    fun existsByCarIdIgnoreCase(carId: String): Boolean

    fun findByCarIdIgnoreCase(carId: String): Optional<ApplicationRegister>

    @Query("SELECT a.carId FROM ApplicationRegister a")
    fun findAllCarIds(): List<String>

    @Query(
        """
        SELECT DISTINCT a FROM ApplicationRegister a
        LEFT JOIN FETCH a.assets
        ORDER BY a.name ASC
        """
    )
    fun findAllWithAssets(): List<ApplicationRegister>

    @Query(
        """
        SELECT a FROM ApplicationRegister a
        WHERE (:search IS NULL OR :search = ''
          OR LOWER(a.name) LIKE LOWER(CONCAT('%', :search, '%'))
          OR LOWER(a.carId) LIKE LOWER(CONCAT('%', :search, '%'))
          OR LOWER(a.businessOwner) LIKE LOWER(CONCAT('%', :search, '%'))
          OR LOWER(a.applicationManager) LIKE LOWER(CONCAT('%', :search, '%'))
          OR LOWER(COALESCE(a.operationalStatus, '')) LIKE LOWER(CONCAT('%', :search, '%'))
          OR LOWER(COALESCE(a.criticality, '')) LIKE LOWER(CONCAT('%', :search, '%')))
        ORDER BY a.name ASC
        """
    )
    fun search(search: String?): List<ApplicationRegister>

    @Query(
        """
        SELECT a FROM ApplicationRegister a
        LEFT JOIN FETCH a.assets
        WHERE a.id = :id
        """
    )
    fun findByIdWithAssets(id: Long): Optional<ApplicationRegister>

    @Query(
        """
        SELECT a FROM ApplicationRegister a
        WHERE a.lastQualityCheck IS NULL OR a.lastQualityCheck < :cutoff
        ORDER BY a.name ASC
        """
    )
    fun findEntriesOverdueForQualityCheck(cutoff: LocalDate): List<ApplicationRegister>

}
