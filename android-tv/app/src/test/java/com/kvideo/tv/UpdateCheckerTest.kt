package com.kvideo.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [UpdateChecker] 双源检查逻辑测试。
 *
 * 用假 fetcher 注入响应, 不碰网络。
 */
class UpdateCheckerTest {

    private fun checker(vararg sources: UpdateChecker.UpdateSource) =
        UpdateChecker(sources.toList())

    private val src182 = UpdateChecker.UpdateSource(
        "182-api", "https://kvideo.djjk.cc/api/tv-update", null
    )
    private val src106 = UpdateChecker.UpdateSource(
        "106-version", "http://106.15.153.201/ziqidonglai/version.txt",
        "http://106.15.153.201/ziqidonglai/latest.apk"
    )

    /** 返回固定内容或抛异常的 fetcher */
    private fun fetcher(vararg results: Any): UpdateChecker.Fetcher {
        var i = 0
        return object : UpdateChecker.Fetcher {
            override fun fetch(url: String): String {
                val r = results[minOf(i, results.size - 1)]
                i++
                if (r is Exception) throw r
                return r as String
            }
        }
    }

    @Test
    fun `returns update when remote version is newer`() {
        val c = checker(src182)
        val r = c.check(25, fetcher("""{"versionCode":41001,"url":"https://x/a.apk","sha256":"${"a".repeat(64)}"}"""))
        assertTrue(r is UpdateChecker.Result.UpdateAvailable)
        require(r is UpdateChecker.Result.UpdateAvailable)
        assertEquals(41001, r.manifest.versionCode)
        assertEquals("182-api", r.sourceName)
    }

    @Test
    fun `returns up to date when remote version is not newer`() {
        val c = checker(src182)
        val r = c.check(41001, fetcher("""{"versionCode":41001,"url":"https://x/a.apk"}"""))
        assertTrue(r is UpdateChecker.Result.UpToDate)
    }

    @Test
    fun `remote version lower than current is not an update`() {
        val c = checker(src182)
        // 服务端是 25, 客户端已是 41001 -> 不是更新
        val r = c.check(41001, fetcher("""{"versionCode":25,"url":"https://x/a.apk"}"""))
        assertTrue(r is UpdateChecker.Result.UpToDate)
    }

    @Test
    fun `falls back to second source when first fails`() {
        val c = checker(src182, src106)
        val r = c.check(
            25,
            fetcher(
                java.io.IOException("connect timeout"),
                "41001"
            )
        )
        assertTrue(r is UpdateChecker.Result.UpdateAvailable)
        require(r is UpdateChecker.Result.UpdateAvailable)
        assertEquals(41001, r.manifest.versionCode)
        assertEquals("106-version", r.sourceName)
        // 106 纯数字协议没有 sha256
        assertTrue(!r.manifest.hasChecksum)
    }

    @Test
    fun `falls back when first source returns unparsable body`() {
        val c = checker(src182, src106)
        val r = c.check(25, fetcher("<html>502</html>", "41001"))
        assertTrue(r is UpdateChecker.Result.UpdateAvailable)
        require(r is UpdateChecker.Result.UpdateAvailable)
        assertEquals("106-version", r.sourceName)
    }

    @Test
    fun `picks the higher version among reachable sources`() {
        val c = checker(src182, src106)
        val r = c.check(
            25,
            fetcher(
                """{"versionCode":41001,"url":"https://x/a.apk"}""",
                "41002"
            )
        )
        assertTrue(r is UpdateChecker.Result.UpdateAvailable)
        require(r is UpdateChecker.Result.UpdateAvailable)
        // 106 的 41002 更高 -> 应该选它
        assertEquals(41002, r.manifest.versionCode)
        assertEquals("106-version", r.sourceName)
    }

    @Test
    fun `unavailable when all sources fail`() {
        val c = checker(src182, src106)
        val r = c.check(
            25,
            fetcher(
                java.io.IOException("boom"),
                java.io.IOException("bang")
            )
        )
        assertTrue(r is UpdateChecker.Result.Unavailable)
        require(r is UpdateChecker.Result.Unavailable)
        assertEquals(2, r.reasons.size)
    }

    @Test
    fun `up to date when sources reachable but no newer version`() {
        val c = checker(src182, src106)
        val r = c.check(
            41001,
            fetcher(
                """{"versionCode":41001,"url":"https://x/a.apk"}""",
                "41001"
            )
        )
        assertTrue(r is UpdateChecker.Result.UpToDate)
    }

    @Test
    fun `default update source uses only the primary server`() {
        assertEquals(1, UpdateChecker.DEFAULT_SOURCES.size)
        assertEquals(src182, UpdateChecker.DEFAULT_SOURCES.single())
    }
}
