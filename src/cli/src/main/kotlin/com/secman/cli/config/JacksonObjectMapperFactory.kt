package com.secman.cli.config

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.micronaut.context.annotation.Factory
import jakarta.inject.Singleton

@Factory
class JacksonObjectMapperFactory {
    @Singleton
    fun objectMapper(): ObjectMapper = jacksonObjectMapper()
}
