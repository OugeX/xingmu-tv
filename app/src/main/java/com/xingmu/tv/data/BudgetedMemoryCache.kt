package com.xingmu.tv.data

import coil.memory.MemoryCache

/**
 * t25 / PERF_ROADMAP P1-7「播放态资源让位」的落地件：
 * Coil 2.7 的 `MemoryCache.maxSize` 只能在构建时设定，运行期压不住。这里包一层委托缓存，
 * 用 [capBytes] 做**运行期软上限**：播放时压到 8MB，退出播放恢复 [Int.MAX_VALUE]（= 原始 24MB）。
 *
 * 只做「写入时按 LRU 头淘汰 + 主动 trim」两件事，不改变 Coil 的命中逻辑；
 * `keys` 是 LinkedHashMap 的视图，淘汰时先取快照，避免 ConcurrentModificationException。
 */
class BudgetedMemoryCache(private val delegate: MemoryCache) : MemoryCache {

    /** Int.MAX_VALUE = 不额外限制（沿用 [delegate] 的 24MB）。 */
    @Volatile
    var capBytes: Int = Int.MAX_VALUE

    override val size: Int get() = delegate.size

    override val maxSize: Int get() = minOf(delegate.maxSize, capBytes)

    override val keys: Set<MemoryCache.Key> get() = delegate.keys

    override fun get(key: MemoryCache.Key): MemoryCache.Value? = delegate.get(key)

    override fun set(key: MemoryCache.Key, value: MemoryCache.Value) {
        val cap = capBytes
        if (cap < delegate.maxSize) {
            val incoming = runCatching { value.bitmap.allocationByteCount }.getOrDefault(0)
            while (delegate.size + incoming > cap) {
                if (!evictOldest()) break
            }
        }
        delegate.set(key, value)
    }

    override fun remove(key: MemoryCache.Key): Boolean = delegate.remove(key)

    override fun clear() = delegate.clear()

    override fun trimMemory(level: Int) = delegate.trimMemory(level)

    /** 上限下调时立刻把已有缓存裁到 cap（播放开始那一帧就释放详情页大图）。 */
    fun trimToCap() {
        while (delegate.size > capBytes) {
            if (!evictOldest()) break
        }
    }

    private fun evictOldest(): Boolean {
        val oldest = runCatching { delegate.keys.toList().firstOrNull() }.getOrNull() ?: return false
        return runCatching { delegate.remove(oldest) }.getOrDefault(false)
    }
}
