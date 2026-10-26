package com.kvideo.tv

import android.util.Log
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * 更新检查器 —— 双源策略。
 *
 * 生产上 APK 同时托管在两个地方:
 * - **182** `https://kvideo.djjk.cc/api/tv-update` (JSON, 带 sha256/size)  ← 首选
 * - **106** `http://106.15.153.201/ziqidonglai/version.txt` (纯数字)        ← 兜底
 *
 * 为什么要双源: 家庭宽带下 106 可能被运营商策略挡住, 或反之;
 * 任意一个可用就能完成更新, 两者都不可用时保持当前版本继续用。
 *
 * 纯网络 I/O, 不含状态; 便于单测 (通过注入 fetcher)。
 */
class UpdateChecker(
    private val sources: List<UpdateSource> = DEFAULT_SOURCES
) {

    /** 一个更新检查源 */
    data class UpdateSource(
        val name: String,
        val manifestUrl: String,
        /** 纯数字协议 (106) 需要用这个 URL 兜底拼出 APK 地址 */
        val fallbackApkUrl: String?
    )

    interface Fetcher {
        /** 返回响应体; 失败抛异常 */
        fun fetch(url: String): String
    }

    sealed class Result {
        /** 找到可用更新 */
        data class UpdateAvailable(val manifest: UpdateManifest, val sourceName: String) : Result()

        /** 已是最新 */
        data class UpToDate(val currentVersionCode: Int) : Result()

        /** 所有源都不可用 (网络失败或响应无法解析) */
        data class Unavailable(val reasons: List<String>) : Result()
    }

    /**
     * 检查是否有比 [currentVersionCode] 更新的版本。
     *
     * 依次尝试每个源, 第一个成功解析且版本号更高的清单即返回;
     * 若所有源都成功解析但都没更新, 返回 [Result.UpToDate];
     * 若所有源都失败, 返回 [Result.Unavailable]。
     */
    fun check(currentVersionCode: Int, fetcher: Fetcher): Result {
        val reasons = mutableListOf<String>()
        var sawValidManifest = false
        var best: UpdateManifest? = null
        var bestSource = ""

        for (source in sources) {
            try {
                val body = fetcher.fetch(source.manifestUrl)
                val manifest = UpdateManifest.parseAny(body, source.fallbackApkUrl ?: "")
                if (manifest == null) {
                    reasons += "${source.name}: 无法解析响应"
                    continue
                }
                sawValidManifest = true
                if (manifest.versionCode > currentVersionCode) {
                    // 取版本号最高的那个
                    if (best == null || manifest.versionCode > best!!.versionCode) {
                        best = manifest
                        bestSource = source.name
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "update source ${source.name} failed: ${e.message}")
                reasons += "${source.name}: ${e.message ?: e.javaClass.simpleName}"
            }
        }

        val chosen = best
        if (chosen != null) {
            return Result.UpdateAvailable(chosen, bestSource)
        }
        if (sawValidManifest) {
            return Result.UpToDate(currentVersionCode)
        }
        return Result.Unavailable(reasons)
    }

    companion object {
        private const val TAG = "KVideoUpdate"

        /** 生产默认源: 182 优先 (有 sha256), 106 兜底 */
        val DEFAULT_SOURCES = listOf(
            UpdateSource(
                name = "182-api",
                manifestUrl = "https://kvideo.djjk.cc/api/tv-update",
                fallbackApkUrl = null
            ),
            UpdateSource(
                name = "106-version",
                manifestUrl = "http://106.15.153.201/ziqidonglai/version.txt",
                fallbackApkUrl = "http://106.15.153.201/ziqidonglai/latest.apk"
            )
        )

        /** 真实 HTTP fetcher */
        val HTTP_FETCHER = object : Fetcher {
            private val connectTimeoutMs = 12000
            private val readTimeoutMs = 20000

            override fun fetch(url: String): String {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = connectTimeoutMs
                    readTimeout = readTimeoutMs
                    instanceFollowRedirects = true
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", "KVideo-AndroidTV-Updater")
                    setRequestProperty("Accept", "application/json, text/plain, */*")
                    setRequestProperty("Cache-Control", "no-cache")
                }
                try {
                    val code = conn.responseCode
                    if (code != HttpURLConnection.HTTP_OK) {
                        throw IllegalStateException("HTTP $code")
                    }
                    return conn.inputStream.bufferedReader().use(BufferedReader::readText)
                } finally {
                    conn.disconnect()
                }
            }
        }
    }
}
