package com.secman.domain

/**
 * Domain events fired when a `VulnerabilityExceptionRequest` transitions through its
 * lifecycle. Listeners use `@TransactionalEventListener(AFTER_COMMIT)` so side-effects
 * (email notifications, external systems) only fire once the DB write has committed.
 *
 * Rationale: prior to splitting this out, notifications were invoked inline inside the
 * `@Transactional` approve/reject methods. A failure on the async notification path
 * could corrupt the Hibernate session and silently roll back the entire approval.
 * By moving the trigger to AFTER_COMMIT we guarantee:
 *   - notification never runs if the DB didn't commit
 *   - notification failure can never roll the DB back
 */
class ExceptionRequestApprovedEvent(val request: VulnerabilityExceptionRequest)

class ExceptionRequestRejectedEvent(val request: VulnerabilityExceptionRequest)

class ExceptionRequestCreatedPendingEvent(val request: VulnerabilityExceptionRequest)
