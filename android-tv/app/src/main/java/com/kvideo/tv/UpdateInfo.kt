package com.kvideo.tv

/**
 * 更新信息 —— 极简版。
 *
 * 来源: {APK_HOST}/ziqidonglai/version.txt
 * 格式: 一个整数 versionCode (例如 "21"), 可带换行。
 *
 * 下载地址固定指向同一目录下的 latest.apk —— 版本号和包放在一起, 换包即换版本号。
 */
data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val url: String
) {
    companion object {
        /**
         * 宽松解析 version.txt: 取第一个整数, 失败返回 null。
         */
        fun parseVersionFile(content: String, apkUrl: String): UpdateInfo? {
            val code = content.trim().lines()
                .mapNotNull { line ->
                    Regex("\\d+").find(line)?.value?.toIntOrNull()
                }
                .firstOrNull() ?: return null
            if (code <= 0) return null
            return UpdateInfo(
                versionCode = code,
                versionName = "v$code",
                url = apkUrl
            )
        }
    }
}
