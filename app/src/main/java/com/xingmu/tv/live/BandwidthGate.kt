package com.xingmu.tv.live

/**
 * 切台带宽闸门：切台开始置 true，首帧/失败后置 false。
 *
 * t4 的图片加载与列表分页应读取它：切台瞬间暂停 Coil 请求与下一页请求（500ms 后恢复），
 * 让首个分片独占带宽（PLAYER_PERF §3）。播放层不做全局 IO 拦截，避免误伤其它页面。
 */
object BandwidthGate {
    @Volatile
    var zapping: Boolean = false
}
