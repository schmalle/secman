package com.secman.controller

import io.micronaut.scheduling.TaskExecutors
import io.micronaut.scheduling.annotation.ExecuteOn
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * `POST /bulk` once ran a 2,211-account import (~80 s of JDBC) on a Netty event-loop
 * thread, stalling every request that shared it. Pins the controller to the blocking pool.
 */
class UserMappingControllerExecutorTest {

    @Test
    fun `user mapping endpoints run on the blocking executor`() {
        val executeOn = UserMappingController::class.java.getAnnotation(ExecuteOn::class.java)
        assertThat(executeOn).isNotNull
        assertThat(executeOn.value).isEqualTo(TaskExecutors.BLOCKING)
    }
}
