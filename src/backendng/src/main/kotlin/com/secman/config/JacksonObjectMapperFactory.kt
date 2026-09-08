package com.secman.config

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.micronaut.context.annotation.Factory
import jakarta.inject.Singleton

@Factory
class JacksonObjectMapperFactory {
    /**
     * `jacksonObjectMapper()` brings the Kotlin module and nothing else, so this bean could not
     * write an `Instant` at all — it threw InvalidDefinitionException at the first temporal field.
     * findAndRegisterModules() picks up the Java 8 time module (see the jsr310 pin in
     * build.gradle.kts). ISO-8601 rather than Jackson's default epoch-decimal so this mapper
     * agrees with the micronaut-serde output on the HTTP API instead of diverging from it.
     */
    @Singleton
    fun objectMapper(): ObjectMapper = jacksonObjectMapper()
        .findAndRegisterModules()
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
}
