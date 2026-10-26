package com.kvideo.tv

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 私有网络识别的单元测试。
 *
 * 这个判断直接决定 SSL 证书错误是"放行"还是"拒绝":
 *  - 私有网络 (家里路由器的本地调试服务器) 放行 —— 电视上白屏更糟
 *  - 公网域名拒绝 —— 否则中间人可以完全劫持电视流量
 *
 * 判错的方向会直接造成安全漏洞, 所以要覆盖到边界。
 */
class PrivateNetworkTest {

    // ---------- 私有 IPv4 ----------

    @Test
    fun `10 段是私有`() {
        assertTrue(UrlNormalizer.isPrivateNetworkHost("10.0.0.1"))
        assertTrue(UrlNormalizer.isPrivateNetworkHost("10.255.255.254"))
    }

    @Test
    fun `172 私有段是 16 到 31`() {
        assertTrue(UrlNormalizer.isPrivateNetworkHost("172.16.0.1"))
        assertTrue(UrlNormalizer.isPrivateNetworkHost("172.31.255.254"))
        assertFalse("172.15 不在私有段", UrlNormalizer.isPrivateNetworkHost("172.15.0.1"))
        assertFalse("172.32 不在私有段", UrlNormalizer.isPrivateNetworkHost("172.32.0.1"))
    }

    @Test
    fun `192 段 168 是私有`() {
        assertTrue(UrlNormalizer.isPrivateNetworkHost("192.168.1.1"))
        assertTrue(UrlNormalizer.isPrivateNetworkHost("192.168.0.100"))
        assertFalse("192.169 不是私有", UrlNormalizer.isPrivateNetworkHost("192.169.1.1"))
        assertFalse("192.167 不是私有", UrlNormalizer.isPrivateNetworkHost("192.167.1.1"))
    }

    @Test
    fun `127 和 169 段 254 是私有`() {
        assertTrue(UrlNormalizer.isPrivateNetworkHost("127.0.0.1"))
        assertTrue(UrlNormalizer.isPrivateNetworkHost("169.254.1.1"))
    }

    @Test
    fun `公网 IPv4 不是私有`() {
        assertFalse(UrlNormalizer.isPrivateNetworkHost("8.8.8.8"))
        assertFalse(UrlNormalizer.isPrivateNetworkHost("1.1.1.1"))
        assertFalse(UrlNormalizer.isPrivateNetworkHost("182.92.222.30"))
        assertFalse(UrlNormalizer.isPrivateNetworkHost("106.15.153.201"))
    }

    // ---------- localhost ----------

    @Test
    fun `localhost 是私有`() {
        assertTrue(UrlNormalizer.isPrivateNetworkHost("localhost"))
        assertTrue(UrlNormalizer.isPrivateNetworkHost("LOCALHOST"))
        assertTrue(UrlNormalizer.isPrivateNetworkHost("foo.localhost"))
    }

    // ---------- 内网域名 ----------

    @Test
    fun `内网域名后缀是私有`() {
        assertTrue(UrlNormalizer.isPrivateNetworkHost("nas.local"))
        assertTrue(UrlNormalizer.isPrivateNetworkHost("router.lan"))
        assertTrue(UrlNormalizer.isPrivateNetworkHost("box.home.arpa"))
    }

    // ---------- 公网域名 ----------

    @Test
    fun `公网域名不是私有`() {
        assertFalse(UrlNormalizer.isPrivateNetworkHost("kvideo.djjk.cc"))
        assertFalse(UrlNormalizer.isPrivateNetworkHost("example.com"))
        assertFalse(UrlNormalizer.isPrivateNetworkHost("localhost.evil.com"))
    }

    // ---------- IPv6 ----------

    @Test
    fun `IPv6 本地地址是私有`() {
        assertTrue(UrlNormalizer.isPrivateNetworkHost("::1"))
        assertTrue(UrlNormalizer.isPrivateNetworkHost("[::1]"))
        assertTrue(UrlNormalizer.isPrivateNetworkHost("fd00::1"))
        assertTrue(UrlNormalizer.isPrivateNetworkHost("fe80::1"))
    }

    @Test
    fun `公网 IPv6 不是私有`() {
        assertFalse(UrlNormalizer.isPrivateNetworkHost("2001:db8::1"))
    }

    // ---------- 完整 URL ----------

    @Test
    fun `URL 级别的判断`() {
        assertTrue(UrlNormalizer.isPrivateNetworkUrl("https://192.168.1.10"))
        assertTrue(UrlNormalizer.isPrivateNetworkUrl("https://192.168.1.10:8443/app"))
        assertTrue(UrlNormalizer.isPrivateNetworkUrl("http://nas.local/x"))
        assertFalse(UrlNormalizer.isPrivateNetworkUrl("https://kvideo.djjk.cc"))
        assertFalse(UrlNormalizer.isPrivateNetworkUrl("https://kvideo.djjk.cc/app"))
        assertFalse("无 scheme 不该被当成局域网", UrlNormalizer.isPrivateNetworkUrl("192.168.1.10"))
        assertFalse(UrlNormalizer.isPrivateNetworkUrl(""))
    }

    @Test
    fun `主站域名必须被判定为公网`() {
        // 关键安全断言: 生产站点证书异常时必须拒绝连接
        assertFalse(UrlNormalizer.isPrivateNetworkUrl("https://kvideo.djjk.cc/"))
        assertFalse(UrlNormalizer.isPrivateNetworkUrl("https://kvideo.djjk.cc/api/proxy?u=x"))
    }
}
