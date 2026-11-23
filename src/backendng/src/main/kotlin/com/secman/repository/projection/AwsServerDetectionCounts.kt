package com.secman.repository.projection

import io.micronaut.core.annotation.Introspected

@Introspected
data class AwsServerDetectionCounts(val total: Long, val clean: Long, val overdue: Long, val unknownCount: Long)
