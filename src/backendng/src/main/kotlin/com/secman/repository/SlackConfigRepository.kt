package com.secman.repository

import com.secman.domain.SlackConfig
import io.micronaut.data.annotation.Repository
import io.micronaut.data.jpa.repository.JpaRepository

/**
 * Repository for the workspace-level Slack configuration.
 *
 * Only the lowest-id row is meaningful; see [com.secman.service.SlackConfigService].
 */
@Repository
interface SlackConfigRepository : JpaRepository<SlackConfig, Long>
