package com.tvbox.app.data

import com.tvbox.app.core.Result

/**
 * 源管理页需要“包含停用源”的列表，而冻结的 SourceRepository.listSources() 只返回启用源。
 * t10 的 SourceRepositoryImpl 与内置 LocalSourceRepository 都实现本接口；UI 只在管理页使用。
 */
interface SourceAdminDataSource {
    suspend fun listAllSources(): Result<List<SourceConfig>>
}
