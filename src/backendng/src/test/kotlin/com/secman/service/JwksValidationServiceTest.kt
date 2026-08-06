package com.secman.service

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class JwksValidationServiceTest {

    private val mapper = jacksonObjectMapper()

    @Test
    fun `parses provider jwks keys with certificate metadata`() {
        val jwksJson = """
            {
              "keys": [
                {
                  "kty": "RSA",
                  "use": "sig",
                  "kid": "test-key-id",
                  "x5t": "thumbprint",
                  "x5c": ["certificate"],
                  "issuer": "https://login.microsoftonline.com/example/v2.0",
                  "n": "AQAB",
                  "e": "AQAB"
                }
              ]
            }
        """.trimIndent()

        val jwks = mapper.readValue(jwksJson, JwksResponse::class.java)

        assertEquals(1, jwks.keys.size)
        assertEquals("test-key-id", jwks.keys.single().kid)
        assertEquals("RSA", jwks.keys.single().kty)
    }
}
