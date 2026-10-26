package com.kvideo.tv

import org.json.JSONObject

/**
 * 豆瓣推荐 API 响应的解析 —— 从 HomeRepository 抽出, 使其可单元测试。
 *
 * 纯函数: 输入 JSON 字符串 + baseUrl, 输出条目列表。不触碰网络与 Android API。
 */
object DoubanParser {

    /**
     * 解析 /api/douban/recommend 响应。
     *
     * @param body 响应 JSON
     * @param baseUrl 站点地址, 用于把相对封面路径补成绝对地址
     * @return 条目列表; 结构不对或解析失败返回空列表 (调用方降级为空行)
     */
    fun parseSubjects(body: String, baseUrl: String): List<DoubanSubject> {
        return try {
            val obj = JSONObject(body)
            val arr = obj.optJSONArray("subjects") ?: return emptyList()
            val prefix = baseUrl.trimEnd('/')
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val cover = o.optString("cover")
                // 没有封面的条目在电视墙上是空白格子, 直接丢弃
                if (cover.isBlank()) return@mapNotNull null
                DoubanSubject(
                    id = o.optString("id"),
                    title = o.optString("title"),
                    rate = o.optString("rate"),
                    coverUrl = if (cover.startsWith("http")) cover else prefix + cover,
                    playable = o.optBoolean("playable", false),
                    url = o.optString("url")
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
