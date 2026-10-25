package com.kvideo.tv

/**
 * 服务端下发的更新信息。
 *
 * 服务端接口: GET {server}/api/tv-update
 * 响应 JSON:
 * {
 *   "version": "4.9.21",
 *   "versionCode": 21,
 *   "url": "http://106.15.153.201/ziqidonglai/latest.apk",
 *   "sha256": "5185ccda...",
 *   "size": 2271335,
 *   "publishedAt": "2026-10-25T10:00:00Z",
 *   "notes": "新增: 搜索提速 / 自动更新",
 *   "mandatory": false
 * }
 */
data class UpdateInfo(
    val version: String,
    val versionCode: Int,
    val url: String,
    val sha256: String,
    val size: Long,
    val publishedAt: String,
    val notes: String,
    val mandatory: Boolean
) {
    companion object {
        /**
         * 宽松解析: 缺字段时给出安全默认值, 避免服务端字段缺失导致崩溃。
         */
        fun fromJson(json: String): UpdateInfo? {
            return try {
                val obj = org.json.JSONObject(json)
                val url = obj.optString("url", "")
                if (url.isBlank()) {
                    null
                } else {
                    UpdateInfo(
                        version = obj.optString("version", ""),
                        versionCode = obj.optInt("versionCode", 0),
                        url = url,
                        sha256 = obj.optString("sha256", "").lowercase(),
                        size = obj.optLong("size", 0L),
                        publishedAt = obj.optString("publishedAt", ""),
                        notes = obj.optString("notes", ""),
                        mandatory = obj.optBoolean("mandatory", false)
                    )
                }
            } catch (e: Exception) {
                null
            }
        }
    }
}
