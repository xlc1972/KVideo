package com.kvideo.tv

/**
 * 服务器地址规范化 —— 从 MainActivity 抽出, 使其可单元测试。
 *
 * 电视上用户手动输入服务器地址是唯一的配置入口 (设置页),
 * 各种边界输入 (无 scheme / 带空格 / 带尾斜杠 / 全角字符) 都要正确处理,
 * 否则 WebView 会加载一个打不开的地址且用户没有任何提示。
 */
object UrlNormalizer {

    /**
     * 规范化用户输入的地址:
     *  - 去首尾空白
     *  - 缺 scheme 时补 https://
     *  - 去掉末尾的斜杠 (全部, 而非只去一个)
     *  - 空输入返回空串
     */
    fun normalize(rawUrl: String): String {
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty()) return ""

        val lower = trimmed.lowercase()
        val hasExplicitScheme = lower.startsWith("https://") || lower.startsWith("http://")

        val scheme: String
        val withoutScheme: String
        if (hasExplicitScheme) {
            scheme = if (lower.startsWith("https://")) "https" else "http"
            withoutScheme = trimmed.substring(scheme.length + 3)
        } else {
            // 其它 scheme (file://, javascript:, content:// 等) 不允许 ——
            // WebView 加载非网页协议会带来安全风险, 直接拒绝 (返回空串)。
            // 但允许"裸域名/IP"这种用户常见的简写。
            if (!looksLikeBareHost(trimmed)) return ""
            scheme = "https"
            withoutScheme = trimmed
        }

        val hostPart = withoutScheme.trim().trimEnd('/')
        if (hostPart.isEmpty()) return ""

        return "$scheme://$hostPart"
    }

    /**
     * 判断输入是否像"省略了 scheme 的主机名"。
     * 规则: 不含 "://"、不含空白、不含路径/查询/片段分隔符, 且含一个 "." (像域名/IP)。
     *
     * 冒号不允许 —— "javascript:alert(1)" 这类伪 scheme 会被误判成裸主机。
     */
    private fun looksLikeBareHost(raw: String): Boolean {
        if (raw.contains("://")) return false
        if (raw.contains(":")) return false
        if (raw.any { it.isWhitespace() }) return false
        if (raw.contains('/') || raw.contains('?') || raw.contains('#')) return false
        return raw.contains('.')
    }

    /**
     * 校验地址是否可用于加载网页。
     *
     * 不用 android.net.Uri —— 它对畸形输入过于宽松 (例如 "https://a b" 会被接受)。
     * 这里用严格解析: scheme 必须是 http/https, host 必须是合法主机名或 IP。
     */
    fun isValid(url: String): Boolean {
        if (url.isBlank()) return false

        val lower = url.trim().lowercase()
        val scheme = when {
            lower.startsWith("https://") -> "https"
            lower.startsWith("http://") -> "http"
            else -> return false
        }
        val rest = url.trim().substring(scheme.length + 3)
        if (rest.isEmpty()) return false

        // 去掉 path / query / fragment, 只留 authority
        val authority = rest.substringBefore('/').substringBefore('?').substringBefore('#')
        if (authority.isEmpty()) return false

        // 去掉 userinfo 和端口
        val hostPort = authority.substringAfterLast('@')

        // IPv6 字面量: [::1] / [2001:db8::1]:8080
        if (hostPort.startsWith("[")) {
            val close = hostPort.indexOf(']')
            if (close < 0) return false
            val inner = hostPort.substring(1, close)
            if (inner.isEmpty()) return false
            // 方括号后只能是端口或结尾
            val after = hostPort.substring(close + 1)
            if (after.isNotEmpty() && !after.startsWith(":")) return false
            if (after.startsWith(":") && after.length > 1 && after.substring(1).toIntOrNull() == null) return false
            // 只允许十六进制数字和冒号
            if (!inner.matches(Regex("^[0-9A-Fa-f:]+$"))) return false
            return inner.contains(":")
        }

        val host = hostPort.substringBefore(':')
        if (host.isBlank()) return false

        // host 不允许含空白
        if (host.any { it.isWhitespace() }) return false

        // 允许: 域名 (含子域/点) / IPv4  (IPv6 已在上面单独处理)
        if (host.matches(Regex("^\\d{1,3}(\\.\\d{1,3}){3}$"))) {
            return host.split('.').all { part ->
                val n = part.toIntOrNull()
                n != null && n in 0..255
            }
        }
        // 域名: 字母数字 + '-' + '.', 不允许以 '-' 或 '.' 开头结尾
        if (host.startsWith('-') || host.startsWith('.') || host.endsWith('-') || host.endsWith('.')) return false
        return host.matches(Regex("^[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?(\\.[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?)*$"))
    }

    /**
     * 判断 host 是否属于私有网络 (局域网)。
     *
     * 用于 SSL 错误处理: 家庭路由器上的本地调试服务器常带自签名/过期证书,
     * 电视上直接报错等于白屏, 所以对私有地址放行; 但公网域名的证书错误
     * 必须拒绝, 否则中间人可以完全劫持电视流量。
     */
    fun isPrivateNetworkHost(host: String): Boolean {
        val h = host.trim().lowercase().removePrefix("[").removeSuffix("]")
        if (h.isEmpty()) return false

        // localhost
        if (h == "localhost" || h.endsWith(".localhost")) return true

        // IPv4 私有段: 10.x / 172.16-31.x / 192.168.x / 127.x / 169.254.x
        if (h.matches(Regex("^\\d{1,3}(\\.\\d{1,3}){3}$"))) {
            val parts = h.split('.').mapNotNull { it.toIntOrNull() }
            if (parts.size != 4 || parts.any { it !in 0..255 }) return false
            val (a, b) = parts[0] to parts[1]
            return a == 10 ||
                a == 127 ||
                (a == 172 && b in 16..31) ||
                (a == 192 && b == 168) ||
                (a == 169 && b == 254)
        }

        // IPv6 本地: ::1 / fc00::/7 (ULA) / fe80::/10 (link-local)
        if (h.contains(":")) {
            if (h == "::1") return true
            if (h.startsWith("fc") || h.startsWith("fd")) return true
            if (h.startsWith("fe8") || h.startsWith("fe9") ||
                h.startsWith("fea") || h.startsWith("feb")) return true
            return false
        }

        // 内网域名后缀 (家庭路由器常见的 local 域)
        if (h.endsWith(".local") || h.endsWith(".lan") || h.endsWith(".home.arpa")) return true

        // 明确的公网域名
        return false
    }

    /**
     * 判断完整 URL 是否指向私有网络主机。
     */
    fun isPrivateNetworkUrl(url: String): Boolean {
        if (url.isBlank()) return false
        val lower = url.trim().lowercase()
        val scheme = when {
            lower.startsWith("https://") -> "https"
            lower.startsWith("http://") -> "http"
            else -> return false
        }
        val rest = url.trim().substring(scheme.length + 3)
        val authority = rest.substringBefore('/').substringBefore('?').substringBefore('#')
        val hostPort = authority.substringAfterLast('@')
        val host = if (hostPort.startsWith("[")) {
            val close = hostPort.indexOf(']')
            if (close < 0) return false
            hostPort.substring(1, close)
        } else {
            hostPort.substringBefore(':')
        }
        return isPrivateNetworkHost(host)
    }
}
