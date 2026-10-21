package com.tvbox.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 低频带宽门控（直播切台期间）：暂停图片请求与列表分页，首帧后 500ms 恢复。
 * Coil 2.7 没有全局 pauseRequests，故在请求入口（PosterImage/分页）读这个开关。
 */
object ContentGate {
    var zapping by mutableStateOf(false)
        private set

    fun update(value: Boolean) {
        zapping = value
    }
}
