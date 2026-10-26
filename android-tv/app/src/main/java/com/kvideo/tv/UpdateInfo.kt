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
         *
         * 注意: 不能用 `Regex("\\d+")` 直接搜 —— 它会把 "-1" 里的 "1" 匹配出来,
         * 导致服务器写错版本号时 App 把版本当成 1, 反复弹更新提示。
         * 这里要求整数前面不能紧邻 '-' 或数字, 且值必须为正。
         */
        fun parseVersionFile(content: String, apkUrl: String): UpdateInfo? {
            val code = content.trim().lines()
                .mapNotNull { line ->
                    Regex("(?<![\\d-])(\\d+)(?![\\d-])")
                        .find(line)?.groupValues?.get(1)?.toIntOrNull()
                }
                .firstOrNull { it > 0 } ?: return null
            return UpdateInfo(
                versionCode = code,
                versionName = "v$code",
                url = apkUrl
            )
        }
    }
}
