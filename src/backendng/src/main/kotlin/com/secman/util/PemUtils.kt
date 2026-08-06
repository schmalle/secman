package com.secman.util

import java.security.KeyFactory
import java.security.PrivateKey
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64

/**
 * Parses PEM-encoded RSA private keys. GitHub App keys download as PKCS#1
 * (`BEGIN RSA PRIVATE KEY`); Java's [PKCS8EncodedKeySpec] only accepts
 * PKCS#8, so PKCS#1 bodies are wrapped in the standard PKCS#8 envelope
 * (RFC 5208) before parsing — no BouncyCastle required.
 */
object PemUtils {

    private const val PKCS8_HEADER = "-----BEGIN PRIVATE KEY-----"
    private const val PKCS1_HEADER = "-----BEGIN RSA PRIVATE KEY-----"

    fun parseRsaPrivateKey(pem: String): PrivateKey {
        val trimmed = pem.trim()
        val der = when {
            trimmed.contains(PKCS8_HEADER) -> decodeBody(trimmed, "PRIVATE KEY")
            trimmed.contains(PKCS1_HEADER) -> wrapPkcs1InPkcs8(decodeBody(trimmed, "RSA PRIVATE KEY"))
            else -> throw IllegalArgumentException(
                "Unsupported key format: expected PEM with 'BEGIN PRIVATE KEY' (PKCS#8) or 'BEGIN RSA PRIVATE KEY' (PKCS#1)"
            )
        }
        return try {
            KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(der))
        } catch (e: Exception) {
            throw IllegalArgumentException("Invalid RSA private key: ${e.message}", e)
        }
    }

    private fun decodeBody(pem: String, label: String): ByteArray {
        val body = pem
            .substringAfter("-----BEGIN $label-----")
            .substringBefore("-----END $label-----")
            .replace(Regex("\\s"), "")
        require(body.isNotEmpty()) { "Empty PEM body" }
        return try {
            Base64.getDecoder().decode(body)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("Invalid base64 in PEM body: ${e.message}", e)
        }
    }

    /**
     * Wraps PKCS#1 DER bytes in a PKCS#8 PrivateKeyInfo:
     * SEQUENCE { INTEGER 0, SEQUENCE { OID rsaEncryption, NULL }, OCTET STRING { pkcs1 } }
     */
    private fun wrapPkcs1InPkcs8(pkcs1: ByteArray): ByteArray {
        val version = byteArrayOf(0x02, 0x01, 0x00)
        // AlgorithmIdentifier for rsaEncryption (1.2.840.113549.1.1.1) with NULL params
        val algorithmId = byteArrayOf(
            0x30, 0x0d,
            0x06, 0x09, 0x2a, 0x86.toByte(), 0x48, 0x86.toByte(), 0xf7.toByte(), 0x0d, 0x01, 0x01, 0x01,
            0x05, 0x00
        )
        val octetString = derEncode(0x04, pkcs1)
        return derEncode(0x30, version + algorithmId + octetString)
    }

    private fun derEncode(tag: Int, content: ByteArray): ByteArray {
        val len = content.size
        val lengthBytes = when {
            len < 0x80 -> byteArrayOf(len.toByte())
            len <= 0xFF -> byteArrayOf(0x81.toByte(), len.toByte())
            len <= 0xFFFF -> byteArrayOf(0x82.toByte(), (len shr 8).toByte(), len.toByte())
            else -> byteArrayOf(0x83.toByte(), (len shr 16).toByte(), (len shr 8).toByte(), len.toByte())
        }
        return byteArrayOf(tag.toByte()) + lengthBytes + content
    }
}
