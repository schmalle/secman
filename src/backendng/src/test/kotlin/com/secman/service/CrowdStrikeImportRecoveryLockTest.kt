package com.secman.service

import com.secman.crowdstrike.dto.CrowdStrikeDeviceRecord
import com.secman.crowdstrike.dto.CrowdStrikeDeviceSelection
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class CrowdStrikeImportRecoveryLockTest {
    @Test
    fun `same-host imports across accounts and legacy payloads wait for recovery lock`() {
        val service = CrowdStrikeDeviceReplacementService(mockk(), mockk(), mockk(), mockk(), mockk())
        val selected = CrowdStrikeDeviceSelection(CrowdStrikeDeviceRecord("aid-one", "ARCGIS", cloudAccountId = "111111111111"))
        val sibling = CrowdStrikeDeviceSelection(CrowdStrikeDeviceRecord("aid-two", "arcgis", cloudAccountId = "222222222222"))
        for (selection in listOf(sibling, null)) {
            val held = CountDownLatch(1)
            val release = CountDownLatch(1)
            val ready = CountDownLatch(1)
            val entered = CountDownLatch(1)
            val executor = Executors.newFixedThreadPool(2)
            try {
                val recovery = executor.submit {
                    service.withSelectionLocks(selected, "ARCGIS") {
                        held.countDown()
                        check(release.await(5, TimeUnit.SECONDS))
                    }
                }
                assertThat(held.await(5, TimeUnit.SECONDS)).isTrue()
                val concurrent = executor.submit {
                    ready.countDown()
                    service.withSelectionLocks(selection, "arcgis") { entered.countDown() }
                }
                assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue()
                assertThat(entered.await(100, TimeUnit.MILLISECONDS)).isFalse()
                release.countDown()
                recovery.get(5, TimeUnit.SECONDS)
                concurrent.get(5, TimeUnit.SECONDS)
                assertThat(entered.count).isZero()
            } finally {
                release.countDown()
                executor.shutdownNow()
            }
        }
    }
}
