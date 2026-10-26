package com.kvideo.tv

/**
 * 页面加载超时兜底 —— 判定逻辑。
 *
 * 为什么需要: 电视上的 WebView 版本参差, 站点又是 Next.js SPA,
 * 遇到慢网络 / CDN 抖动 / JS 报错时, 页面会一直停在白屏或黑屏,
 * 用户按遥控器毫无反应, 只能拔电重启。
 *
 * 做法: 记录页面开始加载的时间, 超时 (默认 12s) 仍未 onPageFinished
 * 就显示一个可操作的错误页 (重试 / 返回 / 打开设置), 而不是干等。
 *
 * 纯逻辑, 无 Android 依赖, 可单测。
 */
object LoadTimeoutPolicy {

    /** 默认超时: 12 秒 —— 站点首屏实测 2~4s, 12s 足够宽松 */
    const val DEFAULT_TIMEOUT_MS = 12_000L

    /** 判定结果 */
    enum class Decision {
        /** 继续等 */
        WAIT,
        /** 加载成功, 取消计时 */
        DONE,
        /** 超时, 显示兜底页 */
        TIMEOUT
    }

    /**
     * 根据当前状态决定下一步。
     *
     * @param pageFinished 页面是否已 onPageFinished
     * @param elapsedMs 从 onPageStarted 起经过的毫秒数
     * @param timeoutMs 超时阈值
     */
    fun decide(
        pageFinished: Boolean,
        elapsedMs: Long,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS
    ): Decision {
        if (pageFinished) return Decision.DONE
        if (elapsedMs >= timeoutMs) return Decision.TIMEOUT
        return Decision.WAIT
    }

    /**
     * 首次启动是否应该先显示"设置页"而不是直接进站点。
     *
     * 场景: 电视上首次安装, 站点域名还没配好 / 网络不通,
     * 直接进站点只会白屏。此时先给一个设置页让用户填服务器地址。
     *
     * 规则:
     *  - 已经保存过服务器地址 -> 不显示设置页 (直接进站点)
     *  - 从未保存过            -> 显示设置页
     *
     * @param savedServer 之前保存的服务器地址 (空 = 从未设置)
     * @param startupOk    本次启动是否成功加载过站点
     */
    fun shouldShowSettingsOnFirstRun(savedServer: String?, startupOk: Boolean): Boolean {
        // 已经能正常加载 -> 永远不进设置页
        if (startupOk) return false
        // 有保存过的地址, 说明是"回退到设置页"而不是首次
        return savedServer.isNullOrBlank()
    }
}
