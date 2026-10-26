package com.kvideo.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [LoadTimeoutPolicy] 加载超时兜底判定测试。
 */
class LoadTimeoutPolicyTest {

    @Test
    fun `waits before timeout`() {
        assertEquals(
            LoadTimeoutPolicy.Decision.WAIT,
            LoadTimeoutPolicy.decide(pageFinished = false, elapsedMs = 3_000L)
        )
    }

    @Test
    fun `times out after threshold`() {
        assertEquals(
            LoadTimeoutPolicy.Decision.TIMEOUT,
            LoadTimeoutPolicy.decide(pageFinished = false, elapsedMs = 12_000L)
        )
    }

    @Test
    fun `done wins over timeout when page finished`() {
        assertEquals(
            LoadTimeoutPolicy.Decision.DONE,
            LoadTimeoutPolicy.decide(pageFinished = true, elapsedMs = 99_000L)
        )
    }

    @Test
    fun `custom timeout is honoured`() {
        assertEquals(
            LoadTimeoutPolicy.Decision.WAIT,
            LoadTimeoutPolicy.decide(false, 5_000L, timeoutMs = 8_000L)
        )
        assertEquals(
            LoadTimeoutPolicy.Decision.TIMEOUT,
            LoadTimeoutPolicy.decide(false, 8_000L, timeoutMs = 8_000L)
        )
    }

    @Test
    fun `default timeout is twelve seconds`() {
        assertEquals(12_000L, LoadTimeoutPolicy.DEFAULT_TIMEOUT_MS)
    }

    // ---------- 首次运行设置页 ----------

    @Test
    fun `first run with no saved server shows settings`() {
        assertTrue(LoadTimeoutPolicy.shouldShowSettingsOnFirstRun(savedServer = null, startupOk = false))
        assertTrue(LoadTimeoutPolicy.shouldShowSettingsOnFirstRun(savedServer = "", startupOk = false))
        assertTrue(LoadTimeoutPolicy.shouldShowSettingsOnFirstRun(savedServer = "   ", startupOk = false))
    }

    @Test
    fun `saved server means fallback to settings not first run`() {
        assertFalse(
            LoadTimeoutPolicy.shouldShowSettingsOnFirstRun(
                savedServer = "https://kvideo.djjk.cc",
                startupOk = false
            )
        )
    }

    @Test
    fun `successful startup never shows settings`() {
        assertFalse(
            LoadTimeoutPolicy.shouldShowSettingsOnFirstRun(savedServer = null, startupOk = true)
        )
        assertFalse(
            LoadTimeoutPolicy.shouldShowSettingsOnFirstRun(
                savedServer = "https://kvideo.djjk.cc",
                startupOk = true
            )
        )
    }
}
