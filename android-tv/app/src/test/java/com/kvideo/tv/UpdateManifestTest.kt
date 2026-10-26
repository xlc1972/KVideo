package com.kvideo.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [UpdateManifest] 双协议解析测试。
 */
class UpdateManifestTest {

    // ---------- JSON 协议 (182 /api/tv-update) ----------

    @Test
    fun `parseJson reads all fields`() {
        val json = """
            {"version":"4.10.1","versionCode":41001,"url":"https://kvideo.djjk.cc/ziqidonglai/latest.apk",
             "sha256":"FCB1EEB6C536B8C19DCEEB78EFEA111A1867936149EEF811DBF4B299F6ABFE98",
             "size":1484055,"notes":"测试","mandatory":true}
        """.trimIndent()

        val m = UpdateManifest.parseJson(json)
        assertNotNull(m)
        requireNotNull(m)
        assertEquals(41001, m.versionCode)
        assertEquals("4.10.1", m.versionName)
        assertEquals("https://kvideo.djjk.cc/ziqidonglai/latest.apk", m.url)
        // sha256 必须转小写
        assertEquals("fcb1eeb6c536b8c19dceeb78efea111a1867936149eef811dbf4b299f6abfe98", m.sha256)
        assertEquals(1484055L, m.size)
        assertEquals("测试", m.notes)
        assertTrue(m.mandatory)
        assertTrue(m.hasChecksum)
    }

    @Test
    fun `parseJson rejects zero or negative versionCode`() {
        assertNull(UpdateManifest.parseJson("""{"versionCode":0,"url":"https://x/a.apk"}"""))
        assertNull(UpdateManifest.parseJson("""{"versionCode":-5,"url":"https://x/a.apk"}"""))
    }

    @Test
    fun `parseJson rejects missing or empty url`() {
        assertNull(UpdateManifest.parseJson("""{"versionCode":41001}"""))
        assertNull(UpdateManifest.parseJson("""{"versionCode":41001,"url":"   "}"""))
    }

    @Test
    fun `parseJson rejects malformed json`() {
        assertNull(UpdateManifest.parseJson("""{"versionCode":41001,"url":"""))
        assertNull(UpdateManifest.parseJson("not json at all"))
        assertNull(UpdateManifest.parseJson(""))
    }

    @Test
    fun `parseJson falls back to v-prefixed version name`() {
        val m = UpdateManifest.parseJson("""{"versionCode":41001,"url":"https://x/a.apk"}""")
        requireNotNull(m)
        assertEquals("v41001", m.versionName)
    }

    @Test
    fun `parseJson without sha256 is still usable but hasChecksum false`() {
        val m = UpdateManifest.parseJson("""{"versionCode":41001,"url":"https://x/a.apk"}""")
        requireNotNull(m)
        assertEquals("", m.sha256)
        assertFalse(m.hasChecksum)
    }

    // ---------- 纯数字协议 (106 version.txt) ----------

    @Test
    fun `parsePlainVersion reads version code`() {
        val m = UpdateManifest.parsePlainVersion("41001\n", "http://106/a.apk")
        requireNotNull(m)
        assertEquals(41001, m.versionCode)
        assertEquals("v41001", m.versionName)
        assertEquals("http://106/a.apk", m.url)
        assertFalse(m.hasChecksum)
    }

    @Test
    fun `parsePlainVersion rejects negative version`() {
        // 关键回归: "-1" 绝不能被当成 1
        assertNull(UpdateManifest.parsePlainVersion("-1", "http://106/a.apk"))
        assertNull(UpdateManifest.parsePlainVersion("0", "http://106/a.apk"))
    }

    // ---------- 自动识别 ----------

    @Test
    fun `parseAny detects json`() {
        val m = UpdateManifest.parseAny("""{"versionCode":41001,"url":"https://x/a.apk"}""", "http://106/a.apk")
        requireNotNull(m)
        assertEquals(41001, m.versionCode)
        assertEquals("https://x/a.apk", m.url)
    }

    @Test
    fun `parseAny detects plain version`() {
        val m = UpdateManifest.parseAny("  41001  ", "http://106/a.apk")
        requireNotNull(m)
        assertEquals(41001, m.versionCode)
        assertEquals("http://106/a.apk", m.url)
    }

    @Test
    fun `parseAny returns null for garbage`() {
        assertNull(UpdateManifest.parseAny("hello world", "http://106/a.apk"))
        assertNull(UpdateManifest.parseAny("", "http://106/a.apk"))
    }

    // ---------- sha256 格式校验 ----------

    @Test
    fun `isUsableChecksum accepts exactly 64 hex chars`() {
        val good = "a".repeat(64)
        assertTrue(UpdateManifest.isUsableChecksum(good))
        assertTrue(UpdateManifest.isUsableChecksum(good.uppercase()))
    }

    @Test
    fun `isUsableChecksum rejects wrong length or non hex`() {
        assertFalse(UpdateManifest.isUsableChecksum("a".repeat(63)))
        assertFalse(UpdateManifest.isUsableChecksum("a".repeat(65)))
        assertFalse(UpdateManifest.isUsableChecksum("z".repeat(64)))
        assertFalse(UpdateManifest.isUsableChecksum(""))
        assertFalse(UpdateManifest.isUsableChecksum(null))
    }
}
