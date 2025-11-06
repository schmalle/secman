package com.secman.config

import io.micronaut.context.annotation.ConfigurationProperties
import io.micronaut.serde.annotation.Serdeable

@ConfigurationProperties("app")
@Serdeable
data class AppConfig(
    val backend: BackendConfig = BackendConfig(),
    val frontend: FrontendConfig = FrontendConfig()
)

@ConfigurationProperties("backend")
@Serdeable
data class BackendConfig(
    val baseUrl: String = "http://localhost:8080"
)

@ConfigurationProperties("frontend")
@Serdeable
data class FrontendConfig(
    val baseUrl: String = "http://localhost:4321"
)