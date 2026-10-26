package com.kvideo.tv

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.foundation.focusable
import androidx.compose.runtime.remember

/**
 * Compose 修饰符小工具。
 *
 * TV 上按钮需要同时满足:
 *   1. focusable —— 能被方向键选中
 *   2. clickable —— 能响应 OK/ENTER 键
 * 只做 clickable 会在遥控器上完全无反应。
 */

/** 可聚焦 (无障碍服务关闭时也允许获取焦点, 电视上必须开)。 */
fun Modifier.focusableCompat(enabled: Boolean = true): Modifier = this.then(
    Modifier.composed {
        if (enabled) {
            focusable()
        } else {
            this
        }
    }
)

/** 无涟漪点击 (TV 上涟漪动画很难看)。 */
fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier = this.then(
    Modifier.composed {
        clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = onClick
        )
    }
)
