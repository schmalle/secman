package com.secman.domain

import io.micronaut.serde.annotation.Serdeable
import jakarta.persistence.*
import jakarta.validation.constraints.NotBlank
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.LocalDateTime

@Entity
@Table(
    name = "email_broadcast_jobs",
    indexes = [
        Index(name = "idx_email_broadcast_status", columnList = "status"),
        Index(name = "idx_email_broadcast_created_at", columnList = "created_at")
    ]
)
@Serdeable
class EmailBroadcastJob(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: EmailBroadcastStatus = EmailBroadcastStatus.PENDING,

    @Column(nullable = false, length = 255)
    @NotBlank
    var subject: String,

    @Column(name = "html_content", nullable = false, columnDefinition = "MEDIUMTEXT")
    @NotBlank
    var htmlContent: String,

    @Column(name = "total_recipients", nullable = false)
    var totalRecipients: Int = 0,

    @Column(name = "sent_count", nullable = false)
    var sentCount: Int = 0,

    @Column(name = "failed_count", nullable = false)
    var failedCount: Int = 0,

    @Column(name = "error_message", length = 2000)
    var errorMessage: String? = null,

    @Column(name = "created_by", nullable = false, length = 100)
    var createdBy: String,

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: LocalDateTime = LocalDateTime.now(),

    @Column(name = "started_at")
    var startedAt: LocalDateTime? = null,

    @Column(name = "completed_at")
    var completedAt: LocalDateTime? = null,

    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Enumerated(EnumType.STRING)
    @Column(name = "target_group", nullable = false, length = 40)
    var targetGroup: EmailBroadcastTargetGroup = EmailBroadcastTargetGroup.ALL_USERS,

    @Column(name = "target_product", length = 255)
    var targetProduct: String? = null
) {
    fun progressPercent(): Int {
        if (totalRecipients == 0) return 0
        return (((sentCount + failedCount).toLong() * 100) / totalRecipients).toInt().coerceIn(0, 100)
    }
}

enum class EmailBroadcastStatus {
    PENDING,
    PROCESSING,
    COMPLETED,
    FAILED
}

enum class EmailBroadcastTargetGroup {
    ALL_USERS,
    ADMINS_ONLY,
    ADMINS_AND_SECCHAMPIONS,
    SELF,
    PRODUCT_USERS
}
