package com.secman.cli.service

import io.micronaut.context.ApplicationContext
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class CliHttpClientBeanTest {

    @Test
    fun `cli context provides CliHttpClient dependencies`() {
        ApplicationContext.builder()
            .environments("cli")
            .start()
            .use { context ->
                assertThat(context.getBean(CliHttpClient::class.java)).isNotNull
            }
    }
}
