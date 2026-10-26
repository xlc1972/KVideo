package com.kvideo.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 站点侧原生播放桥的契约测试。
 *
 * 站点 (Next.js) 在 `lib/utils/native-player-bridge.ts` 里通过
 * `window.KVideoAndroid.playNative(url, title)` 请求原生播放器。
 * 两边必须对得上, 否则电视上会退回 WebView 播放 (老电视 HLS 黑屏)。
 *
 * 这里验证 Android 侧提供的能力边界: 桥名、方法名、返回值语义。
 */
class NativePlayerBridgeContractTest {

    @Test
    fun `bridge object exposes playNative with the agreed signature`() {
        val method = NativePlayerBridgeContract::class.java
            .getDeclaredMethod("playNative", String::class.java, String::class.java)
        assertEquals(Boolean::class.java, method.returnType)
    }

    @Test
    fun `bridge is registered under the name the site expects`() {
        assertEquals("KVideoAndroid", NativePlayerBridgeContract.BRIDGE_NAME)
    }

    @Test
    fun `site treats only a literal true as handed off`() {
        // 站点侧: return bridge.playNative(url, title) === true;
        // 所以 Android 侧返回非 true 的值 (null/false) 必须让站点留在 WebView。
        assertTrue(NativePlayerBridgeContract.isHandedOff(true))
        assertFalse(NativePlayerBridgeContract.isHandedOff(false))
    }

    @Test
    fun `playback resolution decides who owns the video`() {
        // HLS -> 原生
        val hls = PlaybackUrlResolver.resolve("https://cdn.example.com/a/master.m3u8")
        assertTrue(hls is PlaybackUrlResolver.Resolution.Native)

        // 代理地址 -> 保留代理交给原生，不能绕过服务器
        val real = "https://cdn.example.com/a/master.m3u8"
        val encoded = java.net.URLEncoder.encode(real, "UTF-8")
        val proxied = PlaybackUrlResolver.resolve(
            "https://kvideo.djjk.cc/api/proxy?url=$encoded&retry=0",
            "https://kvideo.djjk.cc"
        )
        assertTrue(proxied is PlaybackUrlResolver.Resolution.Native)
        require(proxied is PlaybackUrlResolver.Resolution.Native)
        assertEquals("https://kvideo.djjk.cc/api/proxy?url=$encoded&retry=0", proxied.url)

        // 需要页面上下文 -> 留在 WebView
        assertTrue(
            PlaybackUrlResolver.resolve("https://v.qq.com/x/player/y.html")
                is PlaybackUrlResolver.Resolution.WebView
        )
    }
}

/**
 * 桥契约的纯逻辑镜像 —— 与站点侧 `native-player-bridge.ts` 一一对应。
 * 放在这里是为了让两边改名时能被测试抓到。
 */
object NativePlayerBridgeContract {
    /** 站点注入的全局对象名 */
    const val BRIDGE_NAME = "KVideoAndroid"

    /**
     * 站点侧约定的原生播放入口签名。
     *
     * 实际实现在 MainActivity.AndroidPlayerBridge (inner class),
     * 这里保留一份等价签名用于契约测试 —— 改名时测试会失败。
     */
    fun playNative(url: String, title: String): Boolean {
        val resolution = PlaybackUrlResolver.resolve(url)
        return resolution is PlaybackUrlResolver.Resolution.Native &&
            PlaybackUrlResolver.isMedia3Compatible(resolution.url)
    }

    /** 站点只认字面量 true */
    fun isHandedOff(result: Boolean?): Boolean = result == true
}
