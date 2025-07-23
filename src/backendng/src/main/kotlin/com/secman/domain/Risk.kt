package com.secman.domain

import io.micronaut.serde.annotation.Serdeable
import jakarta.persistence.*
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import java.time.LocalDate
import java.time.LocalDateTime

@Entity
@Table(name = "risk")
@Serdeable
data class Risk(
    @Id
    @GeneratedValue
    var id: Long? = null,

    @Column(nullable = false)
    @NotBlank
    var name: String,

    @Column(columnDefinition = "TEXT")
    var description: String? = null,

    @Column(name = "likelihood")
    @Min(1) @Max(5)
    var likelihood: Int? = 1,

    @Column(name = "impact")
    @Min(1) @Max(5)
    var impact: Int? = 1,

    @Column(name = "risk_level")
    var riskLevel: Int? = null,

    @Column(name = "status", length = 50)
    var status: String = "OPEN",

    @Column(name = "severity", length = 50)
    var severity: String? = null,

    @Column(name = "deadline")
    var deadline: LocalDate? = null,

    @ManyToOne
    @JoinColumn(name = "owner_id")
    var owner: User? = null,

    @ManyToOne
    @JoinColumn(name = "asset_id")
    var asset: Asset? = null,

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: LocalDateTime? = null,

    @Column(name = "updated_at", nullable = false)
    var updatedAt: LocalDateTime? = null
) {

    @PrePersist
    fun onCreate() {
        val now = LocalDateTime.now()
        createdAt = now
        updatedAt = now
        computeRiskLevel()
    }

    @PreUpdate
    fun onUpdate() {
        updatedAt = LocalDateTime.now()
        computeRiskLevel()
    }

    /**
     * Risk calculation algorithm from Java backend:
     * likelihood * impact → risk level
     * 1-4: Low (1), 5-9: Medium (2), 10-15: High (3), 16-25: Critical (4)
     */
    private fun computeRiskLevel() {
        val likelihoodValue = likelihood ?: 1
        val impactValue = impact ?: 1
        val product = likelihoodValue * impactValue
        
        riskLevel = when {
            product <= 4 -> 1   // Low
            product <= 9 -> 2   // Medium
            product <= 15 -> 3  // High
            else -> 4           // Critical
        }
    }

    fun getRiskLevelText(): String {
        return when (riskLevel) {
            1 -> "Low"
            2 -> "Medium"
            3 -> "High"
            4 -> "Critical"
            else -> "Unknown"
        }
    }

    override fun toString(): String {
        return "Risk(id=$id, name='$name', riskLevel=$riskLevel)"
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Risk) return false
        return id != null && id == other.id
    }

    override fun hashCode(): Int {
        return id?.hashCode() ?: 0
    }
}