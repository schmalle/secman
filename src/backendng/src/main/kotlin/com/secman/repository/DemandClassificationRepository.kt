package com.secman.repository

import com.secman.domain.*
import io.micronaut.data.annotation.Query
import io.micronaut.data.annotation.Repository
import io.micronaut.data.jpa.repository.JpaRepository
import java.util.Optional

@Repository
interface DemandClassificationRuleRepository : JpaRepository<DemandClassificationRule, Long> {

    fun findByActiveTrue(): List<DemandClassificationRule>

    fun findByActiveTrueOrderByPriorityOrderAsc(): List<DemandClassificationRule>

    fun findByName(name: String): Optional<DemandClassificationRule>

    @Query("SELECT r FROM DemandClassificationRule r WHERE r.active = true AND r.name = :name")
    fun findActiveByName(name: String): Optional<DemandClassificationRule>

    fun findByCreatedById(createdById: Long): List<DemandClassificationRule>
}

@Repository
interface DemandClassificationResultRepository : JpaRepository<DemandClassificationResult, Long> {

    fun findByDemandId(demandId: Long): Optional<DemandClassificationResult>

    fun findByClassificationHash(hash: String): Optional<DemandClassificationResult>

    @Query("SELECT r FROM DemandClassificationResult r WHERE r.demand.id = :demandId ORDER BY r.classifiedAt DESC")
    fun findLatestByDemandId(demandId: Long): Optional<DemandClassificationResult>

    @Query("SELECT COUNT(r) FROM DemandClassificationResult r WHERE r.classification = :classification")
    fun countByClassification(classification: Classification): Long

    @Query("SELECT r FROM DemandClassificationResult r WHERE r.classification = :classification ORDER BY r.classifiedAt DESC")
    fun findByClassification(classification: Classification): List<DemandClassificationResult>

    @Query("SELECT r FROM DemandClassificationResult r WHERE r.appliedRule.id = :ruleId")
    fun findByAppliedRuleId(ruleId: Long): List<DemandClassificationResult>

    fun findByOverriddenById(overriddenById: Long): List<DemandClassificationResult>
}