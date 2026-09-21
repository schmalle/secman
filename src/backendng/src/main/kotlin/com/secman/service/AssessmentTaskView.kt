package com.secman.service

import com.secman.domain.RiskAssessment

/** Minimal context for a task; never serializes asset grants or user account objects. */
@Suppress("DEPRECATION")
fun RiskAssessment.taskView(): Map<String, Any?> = mapOf(
    "id" to id, "status" to status, "startDate" to startDate, "endDate" to endDate,
    "assessmentBasisType" to assessmentBasisType, "assessmentBasisId" to assessmentBasisId,
    "answerRevision" to answerRevision, "authorshipComplete" to authorshipComplete,
    "asset" to asset?.let { mapOf("id" to it.id, "name" to it.name, "type" to it.type) },
    "awsAccount" to awsAccount?.let { mapOf("awsAccountId" to it.awsAccountId, "name" to it.name) },
    "demand" to demand?.let { mapOf("id" to it.id, "title" to it.title, "demandType" to it.demandType) },
    "assessor" to mapOf("id" to assessor.id, "username" to assessor.username),
    "respondent" to respondent?.let { mapOf("id" to it.id, "username" to it.username) },
    "useCases" to useCases.map { mapOf("id" to it.id, "name" to it.name) },
    "createdAt" to createdAt
)
