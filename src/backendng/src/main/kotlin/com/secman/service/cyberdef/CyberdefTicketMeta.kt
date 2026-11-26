package com.secman.service.cyberdef

import com.secman.domain.CyberdefSeverity
import com.secman.dto.CyberdefSeverityMetaDto
import com.secman.dto.CyberdefTicketMetaDto
import jakarta.inject.Singleton

/** Builds the submission rules from the same constants and settings validation uses. */
@Singleton
open class CyberdefTicketMeta(private val settings: CyberdefTicketSettings) {

    open fun describe(): CyberdefTicketMetaDto = CyberdefTicketMetaDto(
        severities = CyberdefSeverity.entries.map { CyberdefSeverityMetaDto(it.name, settings.slaDays(it)) },
        limits = mapOf(
            "title" to CyberdefTicketInput.TITLE_MAX, "description" to CyberdefTicketInput.DESCRIPTION_MAX,
            "recommendedAction" to CyberdefTicketInput.ACTION_MAX, "comment" to CyberdefTicketInput.COMMENT_MAX,
            "cves" to CyberdefTicketInput.CVES_MAX, "resources" to CyberdefTicketInput.RESOURCES_MAX,
            "references" to CyberdefTicketInput.REFERENCES_MAX, "recipients" to CyberdefTicketInput.RECIPIENTS_MAX,
            "batch" to CyberdefTicketInput.BATCH_MAX, "identifier" to CyberdefTicketInput.IDENTIFIER_MAX
        ),
        resourceTypes = RESOURCE_TYPES,
        maxTicketsPerHour = settings.maxTicketsPerHour,
        recipientOverrideDomains = settings.overrideAllowedDomains.sorted()
    )

    companion object {
        /** The types [AwsArn.resourceType] derives for common ARNs, plus two non-ARN kinds. */
        val RESOURCE_TYPES = listOf(
            "EC2_INSTANCE", "EC2_SECURITY_GROUP", "EC2_VOLUME", "S3_BUCKET", "RDS_DB", "RDS_CLUSTER", "RDS_SNAPSHOT",
            "IAM_ROLE", "IAM_USER", "LAMBDA_FUNCTION", "EKS_CLUSTER", "ECR_REPOSITORY", "SNS_TOPIC", "SQS_QUEUE",
            "HOSTNAME", "IP_ADDRESS"
        )
    }
}
