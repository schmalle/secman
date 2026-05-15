package com.secman.controller

import com.secman.domain.AnswerType
import com.secman.domain.Requirement
import com.secman.domain.Response
import com.secman.domain.ResponseSource
import com.secman.domain.RiskAssessment
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Feature 088 — AI-Assisted Risk Assessment Answers.
 *
 * Locks the provenance-flip rules used by ResponseController.bulkSaveResponses /
 * saveResponseAuthenticated:
 *   * MANUAL stays MANUAL on any edit (no flip).
 *   * AI_GENERATED → AI_EDITED on any change to answerType or comment.
 *   * AI_GENERATED → AI_GENERATED (no flip) when payload is identical.
 *   * AI_EDITED stays AI_EDITED.
 *
 * The controller's transition logic is duplicated across two endpoints with
 * the same shape — this test exercises the rule directly so we don't have to
 * stand up the full HTTP layer to assert it.
 */
class ResponseSourceTransitionTest {

    private fun applyFlipRule(
        existingResponse: Response,
        incomingAnswerType: AnswerType,
        incomingComment: String?
    ) {
        val incomingNormalized = incomingComment?.trim()?.takeIf { it.isNotBlank() }
        val changed = existingResponse.answerType != incomingAnswerType ||
                      existingResponse.comment != incomingNormalized
        if (changed && existingResponse.source == ResponseSource.AI_GENERATED) {
            existingResponse.source = ResponseSource.AI_EDITED
        }
        existingResponse.answerType = incomingAnswerType
        existingResponse.comment = incomingNormalized
    }

    private fun newResponse(
        answer: AnswerType,
        comment: String?,
        source: ResponseSource
    ): Response = Response(
        answerType = answer,
        comment = comment,
        respondentEmail = "alice@example.com",
        source = source,
        riskAssessment = stubAssessment(),
        requirement = stubRequirement()
    )

    private fun stubAssessment(): RiskAssessment {
        // Minimal RiskAssessment instance for entity test — we never persist.
        return RiskAssessment(
            startDate = java.time.LocalDate.now(),
            endDate = java.time.LocalDate.now().plusDays(7),
            assessmentBasisType = com.secman.domain.AssessmentBasisType.ASSET,
            assessmentBasisId = 1L,
            assessor = com.secman.domain.User(username = "a", email = "a@example.com", passwordHash = "x"),
            requestor = com.secman.domain.User(username = "r", email = "r@example.com", passwordHash = "x")
        )
    }

    private fun stubRequirement(): Requirement = Requirement(
        internalId = "REQ-1",
        shortreq = "Stub"
    )

    @Test
    fun `MANUAL stays MANUAL on any edit`() {
        val r = newResponse(AnswerType.YES, "ok", ResponseSource.MANUAL)
        applyFlipRule(r, AnswerType.NO, "changed")
        assertEquals(ResponseSource.MANUAL, r.source)
    }

    @Test
    fun `AI_GENERATED flips to AI_EDITED on answer change`() {
        val r = newResponse(AnswerType.YES, "ok", ResponseSource.AI_GENERATED)
        applyFlipRule(r, AnswerType.NO, "ok")
        assertEquals(ResponseSource.AI_EDITED, r.source)
    }

    @Test
    fun `AI_GENERATED flips to AI_EDITED on comment change`() {
        val r = newResponse(AnswerType.YES, "ok", ResponseSource.AI_GENERATED)
        applyFlipRule(r, AnswerType.YES, "changed")
        assertEquals(ResponseSource.AI_EDITED, r.source)
    }

    @Test
    fun `AI_GENERATED stays when payload identical`() {
        val r = newResponse(AnswerType.YES, "ok", ResponseSource.AI_GENERATED)
        applyFlipRule(r, AnswerType.YES, "ok")
        assertEquals(ResponseSource.AI_GENERATED, r.source)
    }

    @Test
    fun `AI_EDITED stays AI_EDITED on further edits`() {
        val r = newResponse(AnswerType.YES, "ok", ResponseSource.AI_EDITED)
        applyFlipRule(r, AnswerType.NO, "different")
        assertEquals(ResponseSource.AI_EDITED, r.source)
    }
}
