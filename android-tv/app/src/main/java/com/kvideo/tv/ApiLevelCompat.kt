package com.kvideo.tv

import android.os.Build

/**
 * Android 版本差异的统一处理。
 *
 * 本项目 minSdk 是 21, 但有几个能力是后续版本才有的:
 * - WebView 的 `WebResourceError.getErrorCode()` / `getDescription()`  (API 23)
 * - 画中画 Picture-in-Picture                              (API 26)
 * - `URLConnection.getContentLengthLong()`                  (API 24)
 *
 * 这些分支集中放在这里, **MainActivity 和单元测试调用同一份实现** ——
 * 否则测试只是复制了一份逻辑, 实现改了测试也不会失败。
 */
object ApiLevelCompat {

    /** WebView 错误信息。API 23 以下拿不到具体原因, 返回笼统提示。 */
    fun describeWebError(
        sdkInt: Int = Build.VERSION.SDK_INT,
        errorCode: Int?,
        description: String?
    ): Pair<Int, String> {
        return if (sdkInt >= Build.VERSION_CODES.M) {
            (errorCode ?: -1) to (description ?: "未知错误")
        } else {
            -1 to "WebView 加载失败 (Android 版本过低, 无详细错误信息)"
        }
    }

    /** 画中画是否可用。 */
    fun isPipSupported(sdkInt: Int = Build.VERSION.SDK_INT): Boolean =
        sdkInt >= Build.VERSION_CODES.O

    /**
     * 内容长度。
     *
     * API 24 以下只有 int 版本, 对 APK 体积足够 (最大 2GB)。
     * int 为 -1 表示服务端没给长度, 原样传出。
     */
    fun resolveContentLength(
        sdkInt: Int = Build.VERSION.SDK_INT,
        longValue: Long,
        intValue: Int
    ): Long = if (sdkInt >= Build.VERSION_CODES.N) longValue else intValue.toLong()
}
