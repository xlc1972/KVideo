package com.kvideo.tv

import android.util.Log
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * 更新检查器 —— 生产只使用 182 上的 HTTPS 更新清单。
 * 网站、播放代理、APK 和更新清单由同一服务器承载。
 * 更新服务不可用时保留已安装版本，不依赖第二台服务器。
 * 注入多源与旧协议解析仍保留用于兼容测试，不是生产部署依赖。
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

        /** 唯一生产更新源，清单包含 sha256。 */
        val DEFAULT_SOURCES = listOf(
            UpdateSource(
                name = "182-api",
                manifestUrl = "https://kvideo.djjk.cc/api/tv-update",
                fallbackApkUrl = null
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
