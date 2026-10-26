package com.kvideo.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [NativePlayDedup] 原生播放交接幂等判定测试。
 */
class NativePlayDedupTest {

    @Test
    fun `same url within window is skipped`() {
        assertTrue(NativePlayDedup.shouldSkip("u", 1000L, 2000L, "u"))
    }

    @Test
    fun `same url after window is not skipped`() {
        assertFalse(NativePlayDedup.shouldSkip("u", 1000L, 1000L + 5000L, "u"))
    }

    @Test
    fun `different url is never skipped`() {
        assertFalse(NativePlayDedup.shouldSkip("a", 1000L, 1500L, "b"))
    }

    @Test
    fun `first call is not skipped`() {
        assertFalse(NativePlayDedup.shouldSkip("", 0L, 500L, "u"))
    }

    @Test
    fun `blank url is never skipped`() {
        assertFalse(NativePlayDedup.shouldSkip("u", 1000L, 1500L, ""))
    }

    @Test
    fun `clock going backwards is not skipped`() {
        assertFalse(NativePlayDedup.shouldSkip("u", 5000L, 1000L, "u"))
    }

    @Test
    fun `dedup window is three seconds`() {
        assertEquals(3000L, NativePlayDedup.DEDUP_WINDOW_MS)
    }

    @Test
    fun `absolute url detection`() {
        assertTrue(PlaybackUrlResolver.isAbsolute("https://x/a.m3u8"))
        assertTrue(PlaybackUrlResolver.isAbsolute("http://x/a.m3u8"))
        assertFalse(PlaybackUrlResolver.isAbsolute("/api/proxy?url=x"))
        assertFalse(PlaybackUrlResolver.isAbsolute("a/b.m3u8"))
    }

    @Test
    fun `relative proxy url is resolved against site base`() {
        val real = "https://vip.example.com/x/index.m3u8"
        val encoded = java.net.URLEncoder.encode(real, "UTF-8")
        val r = PlaybackUrlResolver.resolve(
            "/api/proxy?url=$encoded&retry=0",
            "https://kvideo.djjk.cc"
        )
        require(r is PlaybackUrlResolver.Resolution.Native)
        assertEquals(real, r.url)
        assertTrue(r.isHls)
    }
}
