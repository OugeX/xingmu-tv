package com.tvbox.app.data.casual

import android.content.Context

/** 随便模块的进程级单例（懒创建，避免每次进页面都 new）。 */
object CasualGraph {

    @Volatile
    private var repository: CasualRepository? = null

    @Volatile
    private var radioSession: RadioSession? = null

    fun repository(context: Context): CasualRepository {
        repository?.let { return it }
        return synchronized(this) {
            repository ?: CasualRepositoryImpl(context.applicationContext).also { repository = it }
        }
    }

    fun radioSession(context: Context): RadioSession {
        radioSession?.let { return it }
        return synchronized(this) {
            radioSession ?: RadioSession(context.applicationContext, repository(context)).also { radioSession = it }
        }
    }
}
