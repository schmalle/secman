package com.secman.domain

/**
 * Published when committed workgroup state changes which assets users may access.
 * Cache listeners run after commit so an old database view cannot repopulate the cache
 * between invalidation and transaction completion.
 */
data class WorkgroupAccessChangedEvent(val workgroupIds: Set<Long>)
