package com.kvideo.tv

/**
 * 原生播放交接的幂等判定。
 *
 * 站点的 React effect 会重跑 (StrictMode / 依赖变化), 同一个播放地址
 * 可能在一秒内被问好几次。Android 侧必须做去重, 否则会重复拉起
 * PlayerActivity, 用户看到播放器闪一下又重来。
 */
object NativePlayDedup {

    /** 去重窗口 (毫秒) */
    const val DEDUP_WINDOW_MS = 3000L

    /**
     * 判断这次是否应该跳过 (已经刚刚处理过同一个地址)。
     *
     * @param lastUrl 上次处理的地址
     * @param lastAt 上次处理的时间戳 (elapsedRealtime 基准, 毫秒)
     * @param now 当前时间戳
     * @param url 本次地址
     */
    fun shouldSkip(lastUrl: String, lastAt: Long, now: Long, url: String): Boolean {
        if (url.isBlank()) return false
        if (url != lastUrl) return false
        val delta = now - lastAt
        // 时钟回拨时 delta 为负, 视为不跳过
        if (delta < 0) return false
        return delta < DEDUP_WINDOW_MS
    }
}
