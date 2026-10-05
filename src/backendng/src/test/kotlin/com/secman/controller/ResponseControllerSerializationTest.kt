package com.secman.controller

import io.micronaut.serde.ObjectMapper
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ResponseControllerSerializationTest {
    private val mapper = ObjectMapper.getDefault()

    @Test
    fun `unanswered questionnaire includes empty response and requirement arrays`() {
        val data = ResponseController.AssessmentData(
            assessment = mapOf("id" to 1L),
            requirements = emptyList(),
            responses = emptyList(),
            isComplete = false,
            completionPercentage = 0
        )

        val json = mapper.writeValueAsString(data)

        assertTrue(json.contains("\"responses\":[]"), "Missing empty responses array: $json")
        assertTrue(json.contains("\"requirements\":[]"), "Missing empty requirements array: $json")
    }
}
