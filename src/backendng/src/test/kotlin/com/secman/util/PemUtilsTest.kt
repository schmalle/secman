package com.secman.util

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.security.KeyPairGenerator
import java.util.Base64

class PemUtilsTest {

    private fun generatePkcs8Der(): ByteArray {
        val generator = KeyPairGenerator.getInstance("RSA")
        generator.initialize(2048)
        return generator.generateKeyPair().private.encoded // PKCS#8 DER
    }

    private fun toPem(der: ByteArray, label: String): String {
        val body = Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(der)
        return "-----BEGIN $label-----\n$body\n-----END $label-----\n"
    }

    /**
     * Extract the PKCS#1 body from a PKCS#8 encoding. For 2048-bit RSA keys
     * the PrivateKeyInfo prefix is fixed: outer SEQUENCE header (4 bytes,
     * long-form length), INTEGER 0 (3), AlgorithmIdentifier (15), OCTET
     * STRING header (4).
     */
    private fun pkcs8ToPkcs1(pkcs8: ByteArray): ByteArray = pkcs8.copyOfRange(26, pkcs8.size)

    @Test
    fun `parses PKCS8 PEM`() {
        val pem = toPem(generatePkcs8Der(), "PRIVATE KEY")
        val key = PemUtils.parseRsaPrivateKey(pem)
        assertThat(key.algorithm).isEqualTo("RSA")
    }

    @Test
    fun `parses PKCS1 PEM (GitHub App key format)`() {
        val pkcs8 = generatePkcs8Der()
        val pem = toPem(pkcs8ToPkcs1(pkcs8), "RSA PRIVATE KEY")
        val key = PemUtils.parseRsaPrivateKey(pem)
        assertThat(key.algorithm).isEqualTo("RSA")
        // Round-trip: the PKCS#8 wrapper must reproduce the original encoding
        assertThat(key.encoded).isEqualTo(pkcs8)
    }

    @Test
    fun `rejects unsupported format`() {
        assertThatThrownBy { PemUtils.parseRsaPrivateKey("not a key") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("Unsupported key format")
    }

    @Test
    fun `rejects invalid base64 body`() {
        val pem = "-----BEGIN PRIVATE KEY-----\n!!!not-base64!!!\n-----END PRIVATE KEY-----"
        assertThatThrownBy { PemUtils.parseRsaPrivateKey(pem) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `rejects garbage DER`() {
        val pem = toPem(ByteArray(64) { 1 }, "PRIVATE KEY")
        assertThatThrownBy { PemUtils.parseRsaPrivateKey(pem) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("Invalid RSA private key")
    }
}
