package com.secman.domain

import io.micronaut.serde.annotation.Serdeable
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.PrePersist
import jakarta.persistence.PreUpdate
import jakarta.persistence.Table
import java.time.LocalDateTime

/**
 * One admin-maintained rule that classifies a finding as [ProductClass.INSTALLER_ARTIFACT]
 * (noise) or [ProductClass.INSTALLED] (an explicit allowlist entry that overrides the
 * artifact rules).
 *
 * Evaluation order is defined by [ProductClassifier]: every [ProductClass.INSTALLED] rule is
 * tested before any artifact rule, so an allowlist entry always wins. Within a classification,
 * rules are ordered by [priority] then [id] so the outcome is deterministic.
 *
 * [pattern] is a GLOB (`*`, `?`) — never a regular expression. Admin-supplied regex would be a
 * ReDoS vector on a per-row hot path; [ProductClassifier] translates the glob itself with every
 * other metacharacter escaped.
 */
@Entity
@Table(
    name = "product_classification_rule",
    indexes = [
        Index(name = "idx_prod_class_rule_enabled", columnList = "enabled, priority")
    ]
)
@Serdeable
data class ProductClassificationRule(
    @Id
    // IDENTITY, not the AUTO default — see InstalledProduct for why a native sequence
    // fights an AUTO_INCREMENT column on MariaDB.
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "match_field", nullable = false, length = 20)
    var matchField: RuleMatchField = RuleMatchField.PRODUCT_NAME,

    /** Case-insensitive glob matched against the whole normalized value. */
    @Column(nullable = false, length = MAX_PATTERN_LENGTH)
    var pattern: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var classification: ProductClass = ProductClass.INSTALLER_ARTIFACT,

    @Column(nullable = false)
    var priority: Int = 100,

    @Column(nullable = false)
    var enabled: Boolean = true,

    @Column(length = 512)
    var description: String? = null,

    @Column(name = "created_by", length = 255)
    var createdBy: String? = null,

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
    }

    @PreUpdate
    fun onUpdate() {
        updatedAt = LocalDateTime.now()
    }

    companion object {
        const val MAX_PATTERN_LENGTH = 512

        /**
         * Hard ceiling on enabled rules. Classification runs once per row over multi-million-row
         * tables, so an unbounded rule list is a design bug, not a configuration choice.
         */
        const val MAX_ENABLED_RULES = 200
    }
}
