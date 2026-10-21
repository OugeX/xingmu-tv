package com.xingmu.tv.source

import com.xingmu.tv.core.Result
import com.xingmu.tv.data.Channel
import com.xingmu.tv.data.SourceConfig
import com.xingmu.tv.data.VodDetail
import com.xingmu.tv.data.VodSummary

/**
 * 源仓库契约（t10 实现，t4/t9 只调用）。
 *
 * 实现见 docs/PLAN_SOURCES.md（四级去重、重试矩阵、多端点降级、聚合搜索）与 docs/SOURCE_IMPORT_SPEC.md。
 */
interface SourceRepository {

    /** 当前启用的源，按 priority 升序（数值小优先） */
    suspend fun listSources(): Result<List<SourceConfig>>

    /** 聚合列表：typeId 对应各源 categories 映射后的真实分类；page 从 1 开始 */
    suspend fun list(typeId: Int, page: Int, sort: Sort = Sort.LATEST): Result<List<VodSummary>>

    /** 跨源聚合搜索（并发 3、单源 3.5s、硬截止 4.5s） */
    suspend fun search(keyword: String, page: Int = 1): Result<List<VodSummary>>

    suspend fun getDetail(vodId: String, sourceId: String): Result<VodDetail>

    /** 同片跨源合并后的候选源（用于换源面板；缺集信息由 UI 侧比对） */
    suspend fun findAlternateSources(vodId: String, name: String, year: String): Result<List<VodSummary>>

    /** 直播频道（来自 assets/live_seed.json 与用户导入 m3u） */
    suspend fun listChannels(): Result<List<Channel>>

    /** 源管理 */
    suspend fun setEnabled(sourceId: String, enabled: Boolean): Result<Unit>
    suspend fun updatePriority(sourceId: String, priority: Int): Result<Unit>
    suspend fun removeSource(sourceId: String): Result<Unit>
    suspend fun testSource(sourceId: String): Result<Int>   // 返回延迟 ms

    enum class Sort { LATEST, HOT }
}
