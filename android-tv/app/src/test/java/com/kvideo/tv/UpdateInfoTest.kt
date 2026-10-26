package com.kvideo.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 更新版本文件解析的单元测试。
 *
 * version.txt 是应用内自动更新的唯一依据 —— 解析错了会导致:
 *  - 漏掉更新 (版本号被误读成更小的数)
 *  - 误报更新 (版本号被误读成更大的数, 反复弹窗)
 */
class UpdateInfoTest {

    private val apkUrl = "http://106.15.153.201/ziqidonglai/latest.apk"

    @Test
    fun `解析纯数字`() {
        val info = UpdateInfo.parseVersionFile("24", apkUrl)
        assertNotNull(info)
        assertEquals(24, info!!.versionCode)
        assertEquals("v24", info.versionName)
        assertEquals(apkUrl, info.url)
    }

    @Test
    fun `解析带换行`() {
        assertEquals(24, UpdateInfo.parseVersionFile("24\n", apkUrl)?.versionCode)
        assertEquals(24, UpdateInfo.parseVersionFile("24\r\n", apkUrl)?.versionCode)
        assertEquals(24, UpdateInfo.parseVersionFile("\n24\n", apkUrl)?.versionCode)
    }

    @Test
    fun `解析带空白`() {
        assertEquals(24, UpdateInfo.parseVersionFile("  24  ", apkUrl)?.versionCode)
    }

    @Test
    fun `解析多行时取第一个整数`() {
        // 服务器如果写了说明文字, 不应崩溃
        assertEquals(24, UpdateInfo.parseVersionFile("version 24\nbuilt 2026-10-25", apkUrl)?.versionCode)
    }

    @Test
    fun `空内容返回 null`() {
        assertNull(UpdateInfo.parseVersionFile("", apkUrl))
        assertNull(UpdateInfo.parseVersionFile("   ", apkUrl))
        assertNull(UpdateInfo.parseVersionFile("\n\n", apkUrl))
    }

    @Test
    fun `非数字返回 null`() {
        assertNull(UpdateInfo.parseVersionFile("abc", apkUrl))
        assertNull(UpdateInfo.parseVersionFile("latest", apkUrl))
    }

    @Test
    fun `零和负数返回 null`() {
        // versionCode 必须是正整数, 0 或负数说明服务器写错了
        assertNull(UpdateInfo.parseVersionFile("0", apkUrl))
        assertNull(UpdateInfo.parseVersionFile("-1", apkUrl))
    }

    @Test
    fun `负数不会被当成正数解析`() {
        // 回归: 旧实现用 Regex("\\d+") 会把 "-1" 里的 "1" 匹配出来,
        // 导致服务器写错版本号时 App 反复弹更新提示。
        assertNull(UpdateInfo.parseVersionFile("-1", apkUrl))
        assertNull(UpdateInfo.parseVersionFile("-24", apkUrl))
        assertNull(UpdateInfo.parseVersionFile("v-1", apkUrl))
        assertNull(UpdateInfo.parseVersionFile("-0", apkUrl))
    }

    @Test
    fun `超大数字不崩溃`() {
        // 超出 Int 范围时 toIntOrNull 返回 null, 应该整体返回 null 而不是抛异常
        assertNull(UpdateInfo.parseVersionFile("99999999999999", apkUrl))
    }

    @Test
    fun `版本号单调递增时能识别为新版本`() {
        val current = 24
        val latest = UpdateInfo.parseVersionFile("25", apkUrl)
        assertNotNull(latest)
        assertTrue("25 应该比 24 新", latest!!.versionCode > current)
    }

    @Test
    fun `版本号相同时不应触发更新`() {
        val current = 24
        val latest = UpdateInfo.parseVersionFile("24", apkUrl)
        assertNotNull(latest)
        assertFalse("相同版本不应触发更新", latest!!.versionCode > current)
    }
}
