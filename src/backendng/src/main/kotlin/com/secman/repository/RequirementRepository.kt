package com.secman.repository

import com.secman.domain.Requirement
import io.micronaut.data.annotation.Query
import io.micronaut.data.annotation.Repository
import io.micronaut.data.jpa.repository.JpaRepository
import java.util.*

@Repository
interface RequirementRepository : JpaRepository<Requirement, Long> {
    
    fun findByLanguage(language: String): List<Requirement>
    
    fun findByShortreqContainingIgnoreCase(shortreq: String): List<Requirement>
    
    fun findByDetailsContainingIgnoreCase(details: String): List<Requirement>
    
    @Query("SELECT r FROM Requirement r WHERE r.isCurrent = true")
    fun findCurrentRequirements(): List<Requirement>
    
    @Query("SELECT r FROM Requirement r WHERE r.language = :language AND r.isCurrent = true")
    fun findCurrentRequirementsByLanguage(language: String): List<Requirement>
    
    @Query("SELECT r FROM Requirement r JOIN r.usecases u WHERE u.id = :usecaseId")
    fun findByUsecaseId(usecaseId: Long): List<Requirement>
    
    @Query("SELECT r FROM Requirement r JOIN r.norms n WHERE n.id = :normId")
    fun findByNormId(normId: Long): List<Requirement>

    @Query("""SELECT r FROM Requirement r WHERE r.isCurrent = true AND (
        LOWER(r.shortreq) LIKE LOWER(CONCAT('%', :query, '%'))
        OR LOWER(COALESCE(r.details, '')) LIKE LOWER(CONCAT('%', :query, '%'))
        OR LOWER(COALESCE(r.usecase, '')) LIKE LOWER(CONCAT('%', :query, '%'))
        OR LOWER(COALESCE(r.example, '')) LIKE LOWER(CONCAT('%', :query, '%'))
        OR LOWER(COALESCE(r.chapter, '')) LIKE LOWER(CONCAT('%', :query, '%'))
        OR LOWER(COALESCE(r.norm, '')) LIKE LOWER(CONCAT('%', :query, '%'))
    )""")
    fun searchCurrentRequirements(query: String): List<Requirement>

    @Query("""SELECT DISTINCT r FROM Requirement r JOIN r.usecases u
        WHERE r.isCurrent = true AND LOWER(u.name) = LOWER(:usecaseName)""")
    fun findCurrentByUsecaseName(usecaseName: String): List<Requirement>

    @Query("""SELECT DISTINCT r FROM Requirement r JOIN r.norms n
        WHERE r.isCurrent = true AND LOWER(n.name) = LOWER(:normName)""")
    fun findCurrentByNormName(normName: String): List<Requirement>

    @Query("""SELECT DISTINCT r FROM Requirement r LEFT JOIN r.usecases u
        WHERE r.isCurrent = true AND (
            LOWER(u.name) = LOWER(:usecaseName)
            OR LOWER(COALESCE(r.usecase, '')) LIKE LOWER(CONCAT('%', :usecaseName, '%'))
        )""")
    fun findCurrentByUsecaseNameOrTextField(usecaseName: String): List<Requirement>
}