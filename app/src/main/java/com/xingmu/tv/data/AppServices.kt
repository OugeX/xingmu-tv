package com.xingmu.tv.data

import android.content.Context
import com.xingmu.tv.player.PlayerController
import com.xingmu.tv.source.SourceRepository

/**
 * 极简服务定位器（手写依赖图）。
 *
 * - t10 实现好完整 SourceRepository 后调用 [registerSources]；
 * - t9 实现好 PlayerController 后调用 [registerPlayer]；
 * - 未注册时 UI 仍可运行：源默认走 [LocalSourceRepository]，播放器显示“未接入”。
 *
 * 不做全局 mutable 状态滥用：只有两个进程级单例，且均在主线程注册。
 */
object AppServices {

    @Volatile
    private var sources: SourceRepository? = null

    @Volatile
    private var player: PlayerController? = null

    private val lock = Any()

    fun sources(context: Context): SourceRepository {
        sources?.let { return it }
        return synchronized(lock) {
            sources ?: LocalSourceRepository(context.applicationContext).also {
                sources = it
            }
        }
    }

    fun registerSources(repository: SourceRepository) {
        synchronized(lock) { sources = repository }
    }

    fun playerOrNull(): PlayerController? = player

    fun registerPlayer(controller: PlayerController?) {
        synchronized(lock) { player = controller }
    }
}
