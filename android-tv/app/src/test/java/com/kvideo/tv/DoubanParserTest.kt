package com.kvideo.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 豆瓣推荐 API 响应解析的单元测试。
 *
 * 电视首页整屏都是这些条目 —— 解析错了会出现空白海报、错误标题或整行空掉。
 */
class DoubanParserTest {

    private val base = "https://kvideo.djjk.cc"

    private fun json(vararg items: String) = """{"subjects":[${items.joinToString(",")}]}"""

    @Test
    fun `解析正常条目`() {
        val body = json(
            """{"id":"1","title":"琅琊榜","rate":"9.3","cover":"/api/douban/image?u=abc","playable":true,"url":"/movie/1"}"""
        )
        val list = DoubanParser.parseSubjects(body, base)
        assertEquals(1, list.size)
        val s = list[0]
        assertEquals("1", s.id)
        assertEquals("琅琊榜", s.title)
        assertEquals("9.3", s.rate)
        assertTrue(s.playable)
        assertEquals("/movie/1", s.url)
    }

    @Test
    fun `相对封面补成绝对地址`() {
        val body = json("""{"id":"1","title":"x","cover":"/api/douban/image?u=abc"}""")
        val s = DoubanParser.parseSubjects(body, base).first()
        assertEquals("https://kvideo.djjk.cc/api/douban/image?u=abc", s.coverUrl)
    }

    @Test
    fun `绝对封面原样保留`() {
        val body = json("""{"id":"1","title":"x","cover":"https://img.doubanio.com/x.jpg"}""")
        val s = DoubanParser.parseSubjects(body, base).first()
        assertEquals("https://img.doubanio.com/x.jpg", s.coverUrl)
    }

    @Test
    fun `base 末尾斜杠不会产生双斜杠`() {
        val body = json("""{"id":"1","title":"x","cover":"/img.jpg"}""")
        val s = DoubanParser.parseSubjects(body, "$base/").first()
        assertEquals("https://kvideo.djjk.cc/img.jpg", s.coverUrl)
    }

    @Test
    fun `无封面的条目被丢弃`() {
        // 电视墙上空白格子比少一张卡更糟
        val body = json(
            """{"id":"1","title":"有封面","cover":"/a.jpg"}""",
            """{"id":"2","title":"无封面","cover":""}""",
            """{"id":"3","title":"缺字段"}"""
        )
        val list = DoubanParser.parseSubjects(body, base)
        assertEquals(1, list.size)
        assertEquals("有封面", list[0].title)
    }

    @Test
    fun `playable 缺省为 false`() {
        val body = json("""{"id":"1","title":"x","cover":"/a.jpg"}""")
        val s = DoubanParser.parseSubjects(body, base).first()
        assertEquals(false, s.playable)
    }

    @Test
    fun `subjects 缺失返回空列表`() {
        assertEquals(0, DoubanParser.parseSubjects("""{"data":[]}""", base).size)
    }

    @Test
    fun `subjects 为空数组返回空列表`() {
        assertEquals(0, DoubanParser.parseSubjects("""{"subjects":[]}""", base).size)
    }

    @Test
    fun `非法 JSON 返回空列表而不是抛异常`() {
        // 网络截断/HTML 错误页都会走到这里, 不能崩
        assertEquals(0, DoubanParser.parseSubjects("not json at all", base).size)
        assertEquals(0, DoubanParser.parseSubjects("", base).size)
        assertEquals(0, DoubanParser.parseSubjects("<html>502</html>", base).size)
        assertEquals(0, DoubanParser.parseSubjects("{", base).size)
    }

    @Test
    fun `多个条目顺序保持`() {
        val body = json(
            """{"id":"1","title":"A","cover":"/a.jpg"}""",
            """{"id":"2","title":"B","cover":"/b.jpg"}""",
            """{"id":"3","title":"C","cover":"/c.jpg"}"""
        )
        val list = DoubanParser.parseSubjects(body, base)
        assertEquals(3, list.size)
        assertEquals(listOf("A", "B", "C"), list.map { it.title })
    }
}
