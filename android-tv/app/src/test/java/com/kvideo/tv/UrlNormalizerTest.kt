package com.kvideo.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 服务器地址规范化的单元测试。
 *
 * 电视上用户手动设置地址是唯一配置入口, 边界输入必须全部正确处理,
 * 否则 WebView 会去加载一个打不开的地址而用户看不到任何提示。
 */
class UrlNormalizerTest {

    // ---------- normalize ----------

    @Test
    fun `空输入返回空串`() {
        assertEquals("", UrlNormalizer.normalize(""))
        assertEquals("", UrlNormalizer.normalize("   "))
        assertEquals("", UrlNormalizer.normalize("\t\n "))
    }

    @Test
    fun `缺 scheme 时补 https`() {
        assertEquals("https://kvideo.djjk.cc", UrlNormalizer.normalize("kvideo.djjk.cc"))
        assertEquals("https://192.168.1.10", UrlNormalizer.normalize("192.168.1.10"))
    }

    @Test
    fun `保留显式的 http`() {
        assertEquals("http://192.168.1.10", UrlNormalizer.normalize("http://192.168.1.10"))
        assertEquals("http://192.168.1.10", UrlNormalizer.normalize("HTTP://192.168.1.10"))
    }

    @Test
    fun `保留显式的 https`() {
        assertEquals("https://kvideo.djjk.cc", UrlNormalizer.normalize("https://kvideo.djjk.cc"))
        assertEquals("https://kvideo.djjk.cc", UrlNormalizer.normalize("HTTPS://kvideo.djjk.cc"))
    }

    @Test
    fun `去掉末尾斜杠`() {
        assertEquals("https://kvideo.djjk.cc", UrlNormalizer.normalize("https://kvideo.djjk.cc/"))
        assertEquals("https://kvideo.djjk.cc", UrlNormalizer.normalize("https://kvideo.djjk.cc///"))
    }

    @Test
    fun `保留路径`() {
        assertEquals("https://kvideo.djjk.cc/app", UrlNormalizer.normalize("https://kvideo.djjk.cc/app"))
        assertEquals("https://kvideo.djjk.cc/app", UrlNormalizer.normalize("https://kvideo.djjk.cc/app/"))
    }

    @Test
    fun `去首尾空白`() {
        assertEquals("https://kvideo.djjk.cc", UrlNormalizer.normalize("  https://kvideo.djjk.cc  "))
    }

    @Test
    fun `非 http scheme 被拒绝`() {
        // file:// / javascript: 等不能让 WebView 去加载 —— 直接拒绝
        assertEquals("", UrlNormalizer.normalize("javascript:alert(1)"))
        assertEquals("", UrlNormalizer.normalize("file://"))
        assertEquals("", UrlNormalizer.normalize("content://media/external"))
        assertEquals("", UrlNormalizer.normalize("ftp://example.com"))
    }

    @Test
    fun `裸域名和裸 IP 被接受`() {
        assertEquals("https://kvideo.djjk.cc", UrlNormalizer.normalize("kvideo.djjk.cc"))
        assertEquals("https://192.168.1.10", UrlNormalizer.normalize("192.168.1.10"))
    }

    @Test
    fun `没有点也没有冒号的单词被拒绝`() {
        // 只有一个词, 不像主机名
        assertEquals("", UrlNormalizer.normalize("localhost"))
        assertEquals("", UrlNormalizer.normalize("abc"))
    }

    @Test
    fun `只有 scheme 没有 host 时返回空串`() {
        assertEquals("", UrlNormalizer.normalize("https://"))
        assertEquals("", UrlNormalizer.normalize("http://"))
    }

    // ---------- isValid ----------

    @Test
    fun `有效地址`() {
        assertTrue(UrlNormalizer.isValid("https://kvideo.djjk.cc"))
        assertTrue(UrlNormalizer.isValid("http://192.168.1.10"))
        assertTrue(UrlNormalizer.isValid("https://kvideo.djjk.cc/app"))
        assertTrue(UrlNormalizer.isValid("https://kvideo.djjk.cc:8443/app"))
        assertTrue(UrlNormalizer.isValid("https://sub.domain.example.co.uk"))
    }

    @Test
    fun `空白和空串无效`() {
        assertFalse(UrlNormalizer.isValid(""))
        assertFalse(UrlNormalizer.isValid("   "))
    }

    @Test
    fun `缺 scheme 无效`() {
        // normalize 会补上 scheme, 但 isValid 本身要求显式 scheme
        assertFalse(UrlNormalizer.isValid("kvideo.djjk.cc"))
    }

    @Test
    fun `非 http scheme 无效`() {
        assertFalse(UrlNormalizer.isValid("ftp://example.com"))
        assertFalse(UrlNormalizer.isValid("file:///etc/passwd"))
        assertFalse(UrlNormalizer.isValid("javascript:alert(1)"))
    }

    @Test
    fun `host 为空无效`() {
        assertFalse(UrlNormalizer.isValid("https://"))
        assertFalse(UrlNormalizer.isValid("https:///path"))
    }

    @Test
    fun `host 含空格无效`() {
        // Uri.parse 会接受 "https://a b", 这里必须拒绝
        assertFalse(UrlNormalizer.isValid("https://a b"))
        assertFalse(UrlNormalizer.isValid("https://example .com"))
    }

    @Test
    fun `IPv4 段必须在 0-255`() {
        assertTrue(UrlNormalizer.isValid("http://192.168.1.1"))
        assertTrue(UrlNormalizer.isValid("http://255.255.255.255"))
        assertFalse(UrlNormalizer.isValid("http://256.1.1.1"))
        assertFalse(UrlNormalizer.isValid("http://999.999.999.999"))
    }

    @Test
    fun `域名不能以连字符或点开头结尾`() {
        assertFalse(UrlNormalizer.isValid("https://-example.com"))
        assertFalse(UrlNormalizer.isValid("https://example-.com"))
        assertFalse(UrlNormalizer.isValid("https://.example.com"))
        assertFalse(UrlNormalizer.isValid("https://example.com."))
    }

    @Test
    fun `IPv6 字面量`() {
        assertTrue(UrlNormalizer.isValid("http://[::1]"))
        assertTrue(UrlNormalizer.isValid("http://[2001:db8::1]"))
        assertFalse(UrlNormalizer.isValid("http://[::1"))
    }

    // ---------- 组合: normalize 之后再 validate ----------

    @Test
    fun `normalize 后的结果都能通过校验`() {
        val inputs = listOf(
            "kvideo.djjk.cc",
            "  https://kvideo.djjk.cc  ",
            "http://192.168.1.10",
            "https://kvideo.djjk.cc/",
            "https://kvideo.djjk.cc/app"
        )
        for (raw in inputs) {
            val n = UrlNormalizer.normalize(raw)
            assertTrue("normalize('$raw') = '$n' 应该有效", UrlNormalizer.isValid(n))
        }
    }
}
