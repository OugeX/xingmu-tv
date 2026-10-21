package com.xingmu.tv.source

import android.content.Context
import com.xingmu.tv.data.AppServices

/**
 * 源仓库的进程级单例 + 与 UI 的接线点。
 *
 * t4 只需在 `Application.onCreate()`（或首个 Activity 启动前）调用一次：
 * ```
 * SourceGraph.install(this)
 * ```
 * 之后 `AppServices.sources(context)` 返回本实现（覆盖默认 LocalSourceRepository）。
 * 未调用时 UI 仍可用默认实现，导入能力通过 [com.xingmu.tv.import.ImportService] 照常工作。
 */
object SourceGraph {

    @Volatile
    private var instance: SourceRepositoryImpl? = null

    fun repository(context: Context): SourceRepositoryImpl {
        instance?.let { return it }
        return synchronized(this) {
            instance ?: SourceRepositoryImpl(context.applicationContext).also { instance = it }
        }
    }

    /** 幂等注册；返回共享实例。 */
    fun install(context: Context): SourceRepositoryImpl {
        val repo = repository(context)
        AppServices.registerSources(repo)
        return repo
    }

    fun installedOrNull(): SourceRepositoryImpl? = instance
}
