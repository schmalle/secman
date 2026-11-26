package com.secman.dto

import com.secman.domain.CyberdefTicket
import com.secman.domain.CyberdefTicketEvent
import com.secman.domain.CyberdefTicketNotification
import com.secman.domain.CyberdefTicketResource
import com.secman.service.cyberdef.CyberdefFieldError
import io.micronaut.serde.annotation.Serdeable
import java.time.Instant

/**
 * Wire shapes of the account-ticket feature. REST (`/api/security-tickets`) and the MCP
 * `*_cyberdef_*` / `*_security_ticket*` tools return the same classes, so a field means the
 * same thing on both surfaces. See docs/CYBERDEF_TICKETS.md.
 */

@Serdeable
data class CyberdefTicketSummaryDto(
    val id: Long,
    val ticketKey: String?,
    val awsAccountId: String,
    val awsAccountName: String?,
    val title: String,
    val severity: String,
    val status: String,
    val dueAt: Instant?,
    val overdue: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
    val createdByUsername: String,
    val sourceSystem: String?,
    val externalReference: String?,
    val version: Long
) {
    companion object {
        /** List row of [ticket]; [now] decides `overdue`. */
        fun from(ticket: CyberdefTicket, now: Instant = Instant.now()) = CyberdefTicketSummaryDto(
            id = ticket.id!!, ticketKey = ticket.ticketKey, awsAccountId = ticket.awsAccountId,
            awsAccountName = ticket.awsAccountName, title = ticket.title, severity = ticket.severity.name,
            status = ticket.status.name, dueAt = ticket.dueAt,
            overdue = ticket.status.isOpen && ticket.dueAt?.isBefore(now) == true,
            createdAt = ticket.createdAt, updatedAt = ticket.updatedAt, createdByUsername = ticket.createdByUsername,
            sourceSystem = ticket.sourceSystem, externalReference = ticket.externalReference, version = ticket.version
        )
    }
}

@Serdeable
/** One affected resource as shown to callers. */
data class CyberdefTicketResourceDto(val type: String, val identifier: String, val region: String?) {
    companion object {
        /** Wire form of a stored resource row. */
        fun from(resource: CyberdefTicketResource) =
            CyberdefTicketResourceDto(resource.resourceType, resource.identifier, resource.region)
    }
}

@Serdeable
/** One activity-log entry; `id` is the cursor value of the update feed. */
data class CyberdefTicketEventDto(
    val id: Long,
    val ticketId: Long,
    val ticketKey: String?,
    val eventType: String,
    val actorUsername: String?,
    val actorKind: String,
    val fromStatus: String?,
    val toStatus: String?,
    val comment: String?,
    val createdAt: Instant
) {
    companion object {
        fun from(event: CyberdefTicketEvent, ticketKey: String?) = CyberdefTicketEventDto(
            id = event.id!!, ticketId = event.ticketId, ticketKey = ticketKey, eventType = event.eventType.name,
            actorUsername = event.actorUsername, actorKind = event.actorKind.name,
            fromStatus = event.fromStatus?.name, toStatus = event.toStatus?.name,
            comment = event.comment, createdAt = event.createdAt
        )
    }
}

@Serdeable
/** Everything the detail view needs, including what the caller may do next. */
data class CyberdefTicketDetailDto(
    val ticket: CyberdefTicketSummaryDto,
    val description: String,
    val recommendedAction: String?,
    val cves: List<String>,
    val resources: List<CyberdefTicketResourceDto>,
    val references: List<String>,
    val recipientCount: Int,
    val closedAt: Instant?,
    val events: List<CyberdefTicketEventDto>,
    /** Status targets the *calling* user may choose now. The server re-checks every one. */
    val allowedTransitions: List<String>,
    /** Subset of [allowedTransitions] that must carry a comment. */
    val commentRequiredFor: List<String>,
    val canComment: Boolean,
    val canManageDelivery: Boolean
)

@Serdeable
/** One page of ticket rows. */
data class CyberdefTicketPageDto(
    val content: List<CyberdefTicketSummaryDto>,
    val totalElements: Long,
    val totalPages: Int,
    val page: Int,
    val size: Int
)

@Serdeable
/** Figures for the summary tiles and the sidebar badge, within the caller's scope. */
data class CyberdefTicketCountsDto(
    val total: Long,
    val open: Long,
    val closed: Long,
    /** Open tickets past their due date. */
    val overdue: Long
)

@Serdeable
/** Outcome of one submitted ticket; `index` is its position in the request. */
data class CyberdefCreateResultDto(
    val index: Int,
    /**
     * CREATED, DUPLICATE (same externalReference already submitted by you), REJECTED, or for
     * a dry run WOULD_CREATE (nothing was stored or sent).
     */
    val outcome: String,
    val ticketId: Long? = null,
    val ticketKey: String? = null,
    val status: String? = null,
    val recipientCount: Int? = null,
    /** Who was (or, for a dry run, would be) mailed. */
    val recipients: List<String> = emptyList(),
    /** True when the recipients came from the request's `recipients` override. */
    val recipientsOverridden: Boolean = false,
    val dueAt: Instant? = null,
    val dryRun: Boolean = false,
    val errorCode: String? = null,
    /** Field-keyed problems for a REJECTED ticket; `field` is relative to the ticket or `delivery.*`. */
    val errors: List<CyberdefFieldError> = emptyList()
)

@Serdeable
/** Totals plus one [CyberdefCreateResultDto] per submitted item. */
data class CyberdefBatchResultDto(
    /** The batch's `delivery.dryRun`: nothing was stored or sent. */
    val dryRun: Boolean,
    /** For a dry run: how many would be created. */
    val created: Int,
    val duplicates: Int,
    val rejected: Int,
    val results: List<CyberdefCreateResultDto>
)

@Serdeable
/** Status change request; `expectedVersion` guards against overwriting a concurrent change. */
data class CyberdefTransitionRequest(
    val toStatus: String? = null,
    val comment: String? = null,
    val expectedVersion: Long? = null,
    /** Check the move and report who would be mailed; change nothing. */
    val dryRun: Boolean? = null
)

/** What a dry-run status change would do. Nothing was stored or sent. */
@Serdeable
data class CyberdefTransitionPreviewDto(
    val ticketId: Long,
    val ticketKey: String?,
    val fromStatus: String,
    val toStatus: String,
    val currentVersion: Long,
    /** Side the change would be recorded under: OWNER or DEFENDER. */
    val actingAs: String,
    val wouldNotify: List<String>,
    val dryRun: Boolean = true
)

@Serdeable
/** Plain-text comment, at most 4000 characters. */
data class CyberdefCommentRequest(val comment: String? = null)

@Serdeable
/** One page of the event feed for pollers. */
data class CyberdefUpdatesDto(
    val events: List<CyberdefTicketEventDto>,
    /** Pass as `sinceEventId` next time. Equal to the input when nothing new happened. */
    val nextCursor: Long,
    val hasMore: Boolean
)

@Serdeable
/** Delivery state of one queued mail, for the Cyber Defence delivery view. */
data class CyberdefNotificationDto(
    val id: Long,
    val recipientEmail: String,
    val kind: String,
    val status: String,
    val attempts: Int,
    val errorCode: String?,
    val createdAt: Instant,
    val sentAt: Instant?
) {
    companion object {
        fun from(row: CyberdefTicketNotification) = CyberdefNotificationDto(
            row.id!!, row.recipientEmail, row.kind, row.status, row.attempts, row.errorCode, row.createdAt, row.sentAt
        )
    }
}

@Serdeable
/** Result of retrying a ticket's failed mail. */
data class CyberdefResendResultDto(val attempted: Int, val sent: Int, val failed: Int)

/** One AWS account in the ticket form's picker. No email address ever appears here. */
@Serdeable
data class CyberdefAccountDto(
    val awsAccountId: String,
    val accountName: String?,
    /** Distinct people a ticket would reach (mappings plus enabled-workgroup members). */
    val recipientCount: Int,
    /** False: submitting needs `delivery.allowUnrouted` or `delivery.recipients`. */
    val routable: Boolean
)

/** A severity and its default due window. */
@Serdeable
data class CyberdefSeverityMetaDto(val name: String, val slaDays: Long?)

/**
 * Rules a submission client needs, served by `GET /api/security-tickets/meta`, so the form and
 * the scripts never hard-code limits or SLA days.
 */
@Serdeable
data class CyberdefTicketMetaDto(
    val severities: List<CyberdefSeverityMetaDto>,
    val limits: Map<String, Int>,
    /** Suggestions only; any A-Z 0-9 _ value up to 32 characters is accepted. */
    val resourceTypes: List<String>,
    val maxTicketsPerHour: Long,
    /** Domains `delivery.recipients` may name besides addresses already known to SecMan. */
    val recipientOverrideDomains: List<String>
)
