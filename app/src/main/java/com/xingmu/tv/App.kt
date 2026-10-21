package com.xingmu.tv

import android.app.Application
import com.xingmu.tv.core.TvLog

/**
 * 进程入口：当前只做日志初始化。
 *
 * 依赖图（OkHttp / Coil / 缓存）由 engineer 在后续任务里注入到 [App]，其它实现线只通过
 * `com.xingmu.tv.source.SourceRepository` / `player.PlayerController` 等接口消费，
 * 不要在这里直接 new 播放器或网络客户端。
 */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        TvLog.i("App", "onCreate pid=${android.os.Process.myPid()}")
    }
}
