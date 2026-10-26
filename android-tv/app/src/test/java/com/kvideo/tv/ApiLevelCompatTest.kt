package com.kvideo.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * minSdk 从 26 降到 21 之后, 有几处行为按 Android 版本分支。
 *
 * 这些分支不能只靠 lint —— lint 只保证 API 级别合法, 不保证分支逻辑正确。
 * 这里把纯逻辑部分抽出来测, 保证降级后老电视上的行为可预期。
 */
class ApiLevelCompatTest {


    @Test
    fun `api 23 及以上能拿到具体错误码`() {
        val (code, desc) = ApiLevelCompat.describeWebError(23, -2, "net::ERR_HOST_UNREACHABLE")
        assertEquals(-2, code)
        assertEquals("net::ERR_HOST_UNREACHABLE", desc)
    }

    @Test
    fun `api 22 及以下没有具体错误信息`() {
        val (code, desc) = ApiLevelCompat.describeWebError(22, -2, "net::ERR_HOST_UNREACHABLE")
        assertEquals(-1, code)
        assertTrue(desc.contains("版本过低"))
    }

    @Test
    fun `错误描述为 null 时有兜底文案`() {
        val (code, desc) = ApiLevelCompat.describeWebError(30, null, null)
        assertEquals(-1, code)
        assertEquals("未知错误", desc)
    }

    @Test
    fun `画中画在 api 26 起可用`() {
        assertFalse(ApiLevelCompat.isPipSupported(21))
        assertFalse(ApiLevelCompat.isPipSupported(25))
        assertTrue(ApiLevelCompat.isPipSupported(26))
        assertTrue(ApiLevelCompat.isPipSupported(34))
    }

    @Test
    fun `内容长度在 api 24 以下用 int 版本`() {
        assertEquals(4010073L, ApiLevelCompat.resolveContentLength(24, 4010073L, 4010073))
        assertEquals(4010073L, ApiLevelCompat.resolveContentLength(21, 4010073L, 4010073))
        // int 版本溢出为负时不应该传出负的长度
        assertEquals(-1L, ApiLevelCompat.resolveContentLength(21, 4010073L, -1))
    }

    @Test
    fun `未知内容长度保持原值`() {
        assertEquals(-1L, ApiLevelCompat.resolveContentLength(34, -1L, -1))
    }
}
