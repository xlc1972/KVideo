package com.kvideo.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PlaybackUrlResolver] 播放地址判定测试。
 */
class PlaybackUrlResolverTest {

    private val base = "https://kvideo.djjk.cc"

    @Test
    fun `m3u8 resolves to native hls`() {
        val r = PlaybackUrlResolver.resolve("https://cdn.example.com/a/master.m3u8")
        assertTrue(r is PlaybackUrlResolver.Resolution.Native)
        require(r is PlaybackUrlResolver.Resolution.Native)
        assertTrue(r.isHls)
        assertEquals("https://cdn.example.com/a/master.m3u8", r.url)
    }

    @Test
    fun `m3u8 with query still resolves as hls`() {
        val r = PlaybackUrlResolver.resolve("https://cdn.example.com/a/master.m3u8?token=xyz")
        require(r is PlaybackUrlResolver.Resolution.Native)
        assertTrue(r.isHls)
    }

    @Test
    fun `mp4 resolves to native progressive`() {
        val r = PlaybackUrlResolver.resolve("https://cdn.example.com/a/movie.mp4")
        require(r is PlaybackUrlResolver.Resolution.Native)
        assertFalse(r.isHls)
    }

    @Test
    fun `relative path is made absolute against site base`() {
        val r = PlaybackUrlResolver.resolve("/api/proxy?url=x", base)
        // 不是代理目标, 后缀也不匹配 -> WebView
        assertTrue(r is PlaybackUrlResolver.Resolution.WebView)

        val r2 = PlaybackUrlResolver.resolve("/media/master.m3u8", base)
        require(r2 is PlaybackUrlResolver.Resolution.Native)
        assertEquals("https://kvideo.djjk.cc/media/master.m3u8", r2.url)
    }

    @Test
    fun `proxy url is unwrapped to the real target`() {
        val real = "https://cdn.example.com/x/master.m3u8"
        val encoded = java.net.URLEncoder.encode(real, "UTF-8")
        val proxied = "$base/api/proxy?url=$encoded&retry=0"

        val r = PlaybackUrlResolver.resolve(proxied, base)
        require(r is PlaybackUrlResolver.Resolution.Native)
        assertEquals(real, r.url)
        assertTrue(r.isHls)
    }

    @Test
    fun `extractProxiedTarget returns null for non proxy url`() {
        assertNull(PlaybackUrlResolver.extractProxiedTarget("https://x/a.m3u8?token=1"))
        assertNull(PlaybackUrlResolver.extractProxiedTarget("https://x/noquery"))
    }

    @Test
    fun `embed players stay in webview`() {
        assertTrue(PlaybackUrlResolver.resolve("https://v.qq.com/x/player/y.html") is PlaybackUrlResolver.Resolution.WebView)
        assertTrue(PlaybackUrlResolver.resolve("https://www.iqiyi.com/v_x.html") is PlaybackUrlResolver.Resolution.WebView)
        assertTrue(PlaybackUrlResolver.resolve("https://host/embed/abc") is PlaybackUrlResolver.Resolution.WebView)
    }

    @Test
    fun `unknown extension stays in webview`() {
        assertTrue(PlaybackUrlResolver.resolve("https://host/play?id=1") is PlaybackUrlResolver.Resolution.WebView)
        assertTrue(PlaybackUrlResolver.resolve("https://host/stream") is PlaybackUrlResolver.Resolution.WebView)
    }

    @Test
    fun `empty url stays in webview`() {
        assertTrue(PlaybackUrlResolver.resolve("") is PlaybackUrlResolver.Resolution.WebView)
        assertTrue(PlaybackUrlResolver.resolve("   ") is PlaybackUrlResolver.Resolution.WebView)
    }

    @Test
    fun `toAbsolute leaves absolute urls untouched`() {
        assertEquals("https://x/a.m3u8", PlaybackUrlResolver.toAbsolute("https://x/a.m3u8", base))
        assertEquals("http://x/a.m3u8", PlaybackUrlResolver.toAbsolute("http://x/a.m3u8", base))
    }

    @Test
    fun `isMedia3Compatible accepts http and https only`() {
        assertTrue(PlaybackUrlResolver.isMedia3Compatible("https://x/a.m3u8"))
        assertTrue(PlaybackUrlResolver.isMedia3Compatible("http://x/a.m3u8"))
        assertFalse(PlaybackUrlResolver.isMedia3Compatible("file:///sdcard/a.mp4"))
        assertFalse(PlaybackUrlResolver.isMedia3Compatible("rtmp://x/live"))
        assertFalse(PlaybackUrlResolver.isMedia3Compatible("not a url"))
    }
}
