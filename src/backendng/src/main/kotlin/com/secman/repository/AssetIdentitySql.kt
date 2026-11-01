package com.secman.repository

/** Host identity CrowdStrike matching compares against (V296). */
object AssetIdentitySql {
    /**
     * The source-owned hostname, falling back to [com.secman.domain.Asset.name] for rows that
     * predate source-hostname tracking. MariaDB keeps the generated column in step with both
     * sources; the expression is repeated on the entity only so Hibernate-created test schemas
     * carry the same column.
     */
    const val MATCH_NAME_DDL = "VARCHAR(255) GENERATED ALWAYS AS (COALESCE(crowdstrike_hostname, name)) VIRTUAL"
}
