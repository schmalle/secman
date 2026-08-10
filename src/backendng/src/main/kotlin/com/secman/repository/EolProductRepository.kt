package com.secman.repository

import com.secman.domain.EolProduct
import io.micronaut.data.annotation.Query
import io.micronaut.data.annotation.Repository
import io.micronaut.data.jpa.repository.JpaRepository
import io.micronaut.data.model.Pageable

@Repository
interface EolProductRepository : JpaRepository<EolProduct, Long> {

    fun findBySourceKeyAndProductKey(sourceKey: String, productKey: String): EolProduct?

    fun findBySourceKey(sourceKey: String, pageable: Pageable): List<EolProduct>

    @Query("SELECT COUNT(p) FROM EolProduct p WHERE p.sourceKey = :sourceKey")
    fun countBySourceKey(sourceKey: String): Long

    @Query("SELECT p FROM EolProduct p ORDER BY p.productKey ASC")
    fun findAllOrdered(pageable: Pageable): List<EolProduct>
}
