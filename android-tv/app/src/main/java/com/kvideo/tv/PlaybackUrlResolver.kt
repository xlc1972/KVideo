package com.kvideo.tv

import java.net.URI

/**
 * 播放地址判定 —— 决定一个 URL 该用原生 Media3 播, 还是留在 WebView 里。
 *
 * 背景: 站点的播放页 (WebView) 里有多个源, 每个源给出不同的播放地址:
 *  - HLS 主清单 (.m3u8)          -> 原生 Media3 直连, 兼容性最好
 *  - HLS 带代理重写 (…/api/proxy?url=…) -> 也交给 Media3, 但需要先解出真实地址
 *  - 直链 mp4 / flv 等           -> Media3 渐进式源
 *  - 需要页面上下文才能播的地址   -> 留在 WebView
 *
 * 纯函数, 无 Android 依赖, 可单测。
 */
object PlaybackUrlResolver {

    /** 判定结果 */
    sealed class Resolution {
        /** 用原生 Media3 播, 真实播放地址为 [url] */
        data class Native(val url: String, val isHls: Boolean) : Resolution()

        /** 留在 WebView 里播 */
        object WebView : Resolution()
    }

    /** 已知需要页面上下文的域名特征 (需要 Cookie / Referer / JS 生成) */
    private val WEBVIEW_ONLY_MARKERS = listOf(
        "player.",
        "/embed",
        "/embed/",
        "embed.html",
        "v.qq.com",
        "youku.com",
        "iqiyi.com",
        "mgtv.com",
        "letv.com"
    )

    /**
     * 解析站点的播放地址。
     *
     * @param rawUrl 站点给出的播放地址
     * @param siteBase 站点根地址 (用于把相对路径补成绝对地址)
     */
    fun resolve(rawUrl: String, siteBase: String = ""): Resolution {
        val url = rawUrl.trim()
        if (url.isEmpty()) return Resolution.WebView

        val absolute = toAbsolute(url, siteBase)

        // 需要页面上下文的, 直接留 WebView
        if (WEBVIEW_ONLY_MARKERS.any { absolute.contains(it, ignoreCase = true) }) {
            return Resolution.WebView
        }

        // 代理重写地址: /api/proxy?url=<encoded real url>
        val proxied = extractProxiedTarget(absolute)
        if (proxied != null) {
            return resolve(proxied, siteBase)
        }

        val path = absolute.substringBefore('?').lowercase()
        return when {
            path.endsWith(".m3u8") -> Resolution.Native(absolute, isHls = true)
            path.endsWith(".mp4") -> Resolution.Native(absolute, isHls = false)
            path.endsWith(".webm") -> Resolution.Native(absolute, isHls = false)
            path.endsWith(".mpd") -> Resolution.Native(absolute, isHls = true)  // DASH 走 HLS 工厂会失败, 交由渐进式兜底
            path.endsWith(".flv") -> Resolution.Native(absolute, isHls = false)
            path.endsWith(".ts") -> Resolution.Native(absolute, isHls = false)
            else -> Resolution.WebView
        }
    }

    /**
     * 从 /api/proxy?url=... 里解出被代理的真实地址。
     * 解不出返回 null。
     */
    fun extractProxiedTarget(url: String): String? {
        val qIndex = url.indexOf("?")
        if (qIndex < 0) return null
        if (!url.substring(0, qIndex).contains("/api/proxy")) return null
        val query = url.substring(qIndex + 1)
        for (pair in query.split("&")) {
            val idx = pair.indexOf('=')
            if (idx <= 0) continue
            val key = pair.substring(0, idx)
            if (key == "url" || key == "u" || key == "target") {
                val raw = pair.substring(idx + 1)
                return java.net.URLDecoder.decode(raw, "UTF-8").trim().ifEmpty { null }
            }
        }
        return null
    }

    /** 是否已经是绝对地址 (带 scheme) */
    fun isAbsolute(url: String): Boolean {
        val u = url.trim()
        return u.startsWith("http://") || u.startsWith("https://")
    }

    /** 相对地址补成绝对地址 */
    fun toAbsolute(url: String, siteBase: String): String {
        if (url.startsWith("http://") || url.startsWith("https://")) return url
        if (siteBase.isBlank()) return url
        val base = siteBase.trimEnd('/')
        if (url.startsWith("/")) return "$base$url"
        return "$base/$url"
    }

    /**
     * 判断 Media3 是否能直接吃这个 URL。
     * Media3 的 HLS 支持 http(s) 直链, 但不支持带自定义 header 的场景
     * (站点代理里可能需要 Referer)。
     */
    fun isMedia3Compatible(url: String): Boolean {
        return try {
            val uri = URI(url)
            val scheme = uri.scheme?.lowercase()
            scheme == "http" || scheme == "https"
        } catch (e: Exception) {
            false
        }
    }
}
