package com.secman.repository

import com.secman.domain.EmailBroadcastJob
import io.micronaut.data.annotation.Repository
import io.micronaut.data.jpa.repository.JpaRepository

@Repository
interface EmailBroadcastJobRepository : JpaRepository<EmailBroadcastJob, Long> {
    @io.micronaut.data.annotation.Query("SELECT j FROM EmailBroadcastJob j ORDER BY j.createdAt DESC")
    fun listRecent(): List<EmailBroadcastJob>
}
