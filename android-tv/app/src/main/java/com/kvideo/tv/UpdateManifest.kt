package com.kvideo.tv

import org.json.JSONObject

/**
 * 统一的更新清单 —— 同时兼容两种协议:
 *
 * 1. **182 `/api/tv-update` (JSON, 首选)**
 *    ```json
 *    { "version": "4.10.1", "versionCode": 41001, "url": "https://.../latest.apk",
 *      "sha256": "fcb1...", "size": 1484055, "notes": "...", "mandatory": false }
 *    ```
 *
 * 2. **106 `version.txt` (纯数字, 兼容老版本)**
 *    ```
 *    41001
 *    ```
 *
 * 之所以要做成"双协议", 是因为生产上 182 和 106 都在托管 APK,
 * 而客户端可能因网络策略只能访问其中一个 —— 必须任意一个可用就能完成更新。
 */
data class UpdateManifest(
    val versionCode: Int,
    val versionName: String,
    val url: String,
    /** 小写十六进制 sha256; 空串表示服务端未提供 (不校验, 但仍应校验签名) */
    val sha256: String,
    /** 文件字节数; 0 表示未知 */
    val size: Long,
    val notes: String,
    val mandatory: Boolean
) {
    /** 是否提供了可用于完整性校验的 sha256 */
    val hasChecksum: Boolean get() = sha256.length == 64 && sha256.all { it in "0123456789abcdefABCDEF" }

    companion object {
        private const val SHA256_LEN = 64

        /**
         * 解析 182 `/api/tv-update` 的 JSON 响应。
         *
         * 严格要求 `versionCode` 为正整数, `url` 非空。
         * `version` 缺失时用 "v<versionCode>" 兜底。
         */
        fun parseJson(content: String): UpdateManifest? {
            val trimmed = content.trim()
            if (!trimmed.startsWith("{")) return null
            return try {
                val obj = JSONObject(trimmed)
                val code = obj.optInt("versionCode", 0)
                if (code <= 0) return null
                val url = obj.optString("url").trim()
                if (url.isEmpty()) return null
                UpdateManifest(
                    versionCode = code,
                    versionName = obj.optString("version").trim().ifEmpty { "v$code" },
                    url = url,
                    sha256 = obj.optString("sha256").trim().lowercase(),
                    size = obj.optLong("size", 0L).coerceAtLeast(0L),
                    notes = obj.optString("notes"),
                    mandatory = obj.optBoolean("mandatory", false)
                )
            } catch (e: Exception) {
                null
            }
        }

        /**
         * 解析 106 `version.txt` 的纯数字内容。
         *
         * 复用 [UpdateInfo.parseVersionFile] 的严格规则:
         * 整数前面不能紧邻 '-' 或数字, 且值必须为正。
         * 避免 "-1" 被当成 1 (那会让 App 反复弹更新提示)。
         */
        fun parsePlainVersion(content: String, apkUrl: String): UpdateManifest? {
            val info = UpdateInfo.parseVersionFile(content, apkUrl) ?: return null
            return UpdateManifest(
                versionCode = info.versionCode,
                versionName = info.versionName,
                url = info.url,
                sha256 = "",
                size = 0L,
                notes = "",
                mandatory = false
            )
        }

        /**
         * 自动识别格式: 先按 JSON 试, 再按纯数字试。
         *
         * 这样客户端对"服务端换协议"是透明的 —— 106 从纯数字升级成 JSON 后,
         * 老客户端仍能解析, 新客户端也能拿到 sha256。
         */
        fun parseAny(content: String, fallbackApkUrl: String): UpdateManifest? {
            val trimmed = content.trim()
            if (trimmed.startsWith("{")) return parseJson(trimmed)
            return parsePlainVersion(trimmed, fallbackApkUrl)
        }

        /**
         * 校验服务端给的 sha256 格式是否可用。
         * 长度必须是 64 位十六进制, 否则视为"未提供"而不是"校验失败"。
         */
        fun isUsableChecksum(value: String?): Boolean {
            val v = value?.trim()?.lowercase() ?: return false
            return v.length == SHA256_LEN && v.all { it in "0123456789abcdef" }
        }
    }
}
