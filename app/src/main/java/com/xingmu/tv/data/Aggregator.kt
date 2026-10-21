package com.xingmu.tv.data

import com.xingmu.tv.core.Result
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.Dispatchers

/**
 * 跨源聚合执行器（docs/PLAN_ARCHITECTURE.md / PLAN_SOURCES.md 的统一参数）：
 * - 并发上限 3；
 * - 单源 3.5s 超时；
 * - 整体 4.5s 硬截止，到点返回已完成的部分结果；
 * - 某个源失败不影响其它源（结果里只收 Ok）。
 */
suspend fun <T> aggregateAcrossSources(
    sources: List<SourceConfig>,
    perSourceTimeoutMs: Long = 3_500L,
    hardDeadlineMs: Long = 4_500L,
    worker: suspend (SourceConfig) -> Result<T>,
): List<Pair<SourceConfig, T>> {
    if (sources.isEmpty()) return emptyList()

    val channel = Channel<Pair<SourceConfig, T>>(Channel.UNLIMITED)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("aggregate"))
    val semaphore = Semaphore(3)
    val startedAt = System.currentTimeMillis()

    val jobs = mutableListOf<Job>()
    for (source in sources) {
        jobs += scope.launch {
            semaphore.withPermit {
                val result = withTimeoutOrNull(perSourceTimeoutMs) {
                    runCatching { worker(source) }.getOrNull()
                }
                if (result is Result.Ok) {
                    channel.send(source to result.value)
                }
            }
        }
    }
    scope.launch {
        jobs.joinAll()
        channel.close()
    }

    val collected = mutableListOf<Pair<SourceConfig, T>>()
    while (true) {
        val remaining = hardDeadlineMs - (System.currentTimeMillis() - startedAt)
        if (remaining <= 0) break
        val received = withTimeoutOrNull(remaining) { channel.receiveCatching() } ?: break
        val value = received.getOrNull() ?: break
        collected += value
    }
    scope.coroutineContext[Job]?.cancel()
    return collected
}
