package com.secman.service

import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.util.concurrent.ExecutionException
import java.util.concurrent.Flow

class SupplierPortalReadTest {
    private val client = SupplierPortalClient(jacksonObjectMapper().registerModule(JavaTimeModule()), "", "", "", "", "", "")
    private val valid = """
        {"schemaVersion":1,"invitationId":"10000000-0000-0000-0000-000000000000","generation":1,
        "snapshotHash":"example","submissionId":"20000000-0000-0000-0000-000000000000","submittedAt":"2026-01-01T00:00:00Z",
        "answers":[{"questionKey":"30000000-0000-0000-0000-000000000000","answer":"YES","comment":"approved"}]}
    """.trimIndent()

    @Test
    fun `completed response uses a strict versioned JSON shape`() {
        assertInstanceOf(SupplierPortalRead.Completed::class.java, client.decodeSubmission(valid.toByteArray(), "application/json; charset=utf-8"))
        listOf(
            valid + "{}",
            valid.replace("\"generation\":1", "\"generation\":1,\"generation\":2"),
            valid.replace("\"generation\":1", "\"generation\":null"),
            valid.replace("\"generation\":1", "\"generation\":1,\"internalAssetId\":1"),
            valid.replace("\"answer\":\"YES\"", "\"answer\":0"),
            valid.replace("\"answers\":[", "\"answers\":[null,")
        ).forEach {
            assertSame(SupplierPortalRead.Invalid, client.decodeSubmission(it.toByteArray(), "application/json"))
        }
        assertSame(SupplierPortalRead.Invalid, client.decodeSubmission(valid.toByteArray(), "text/html"))
    }

    @Test
    fun `oversized response is canceled before another read`() {
        val subscription = RecordingSubscription()
        val body = SupplierResponseBody(5)
        body.onSubscribe(subscription)
        body.onNext(listOf(ByteBuffer.wrap(byteArrayOf(1, 2, 3))))
        body.onNext(listOf(ByteBuffer.wrap(byteArrayOf(4, 5, 6))))
        assertTrue(subscription.canceled)
        assertEquals(2, subscription.requests)
        val error = assertThrows(ExecutionException::class.java) { body.getBody().get() }
        assertInstanceOf(SupplierResponseTooLarge::class.java, error.cause)
    }

    private class RecordingSubscription : Flow.Subscription {
        var canceled = false
        var requests = 0
        override fun request(n: Long) { requests += n.toInt() }
        override fun cancel() { canceled = true }
    }
}
