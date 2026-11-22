package com.secman.crowdstrike.dto

import io.micronaut.serde.annotation.Serdeable

@Serdeable
data class CrowdStrikeRemediationDto(
    val action: String,
    val title: String? = null,
    val vendorUrl: String? = null
)
