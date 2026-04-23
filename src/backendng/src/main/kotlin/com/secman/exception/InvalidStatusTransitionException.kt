package com.secman.exception

/**
 * Thrown when a caller attempts an exception-request lifecycle transition
 * that isn't permitted (e.g. approving a non-PENDING request).
 *
 * Deliberately a RuntimeException and NOT an IllegalStateException — the
 * latter is thrown by Hibernate, the JDK collections API, and many other
 * libraries, so catching IllegalStateException in a controller silently
 * re-labels unrelated infrastructure errors as validation errors.
 */
class InvalidStatusTransitionException(message: String) : RuntimeException(message)
