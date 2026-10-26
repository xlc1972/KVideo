package com.kvideo.tv

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * 豆瓣推荐数据源 (走 kvideo.djjk.cc 的 /api/douban/recommend 代理)。
 *
 * 只取首页推荐墙需要的最少字段: 片名 / 评分 / 封面 / 是否可播放 / 详情链接。
 */
data class DoubanSubject(
    val id: String,
    val title: String,
    val rate: String,
    val coverUrl: String,
    val playable: Boolean,
    val url: String
)

/**
 * 首页数据仓库。
 *
 * @param baseUrl 站点地址 (如 https://kvideo.djjk.cc)
 */
class HomeRepository(private val baseUrl: String) {

    companion object {
        private const val TAG = "HomeRepository"

        /** 首页的推荐行定义 —— 与网站保持一致的行结构 */
        val ROWS = listOf(
            RowSpec("热门", "热门", "movie"),
            RowSpec("最新", "最新", "movie"),
            RowSpec("豆瓣高分", "豆瓣高分", "movie"),
            RowSpec("值得一看", "值得一看", "movie"),
            RowSpec("近期热门剧集", "热门", "tv"),
            RowSpec("口碑佳作", "经典", "movie")
        )
    }

    data class RowSpec(val label: String, val tag: String, val type: String)

    data class Row(val spec: RowSpec, val items: List<DoubanSubject>)

    /**
     * 并行拉取所有推荐行。
     */
    suspend fun loadRows(): List<Row> = withContext(Dispatchers.IO) {
        ROWS.map { spec ->
            Row(spec, fetchRow(spec))
        }
    }

    private fun fetchRow(spec: RowSpec): List<DoubanSubject> {
        val url = buildString {
            append(baseUrl.trimEnd('/'))
            append("/api/douban/recommend?tag=")
            append(java.net.URLEncoder.encode(spec.tag, "UTF-8"))
            append("&type=")
            append(spec.type)
            append("&page_limit=18")
            append("&page_start=0")
        }
        return try {
            val body = httpGet(url)
            DoubanParser.parseSubjects(body, baseUrl)
        } catch (e: Exception) {
            Log.w(TAG, "row '${spec.label}' failed: ${e.message}")
            emptyList()
        }
    }

    private fun httpGet(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 12000
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 9) AppleWebKit/537.36 KVideo-AndroidTV")
            setRequestProperty("Accept", "application/json")
        }
        try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                throw RuntimeException("HTTP ${conn.responseCode}")
            }
            return conn.inputStream.bufferedReader().use(BufferedReader::readText)
        } finally {
            conn.disconnect()
        }
    }
}
