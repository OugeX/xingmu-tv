package com.xingmu.tv.data

import android.content.Context

/**
 * 分类筛选记忆（t34 §5）：记住「一级 + 二级 + 年份」组合，下次进入直接还原。
 * 切换一级 Tab 时保留年份、清空二级由 UI 负责；这里只负责存取。
 */
class CategoryFilterStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("tvbox_category_filters", Context.MODE_PRIVATE)

    data class Memory(
        val primaryTypeId: Int,
        val subTypeId: Int?,
        val subName: String?,
        val year: String?,
    )

    fun read(): Memory? {
        val primary = prefs.getInt(KEY_PRIMARY, -1)
        if (primary == -1) return null
        val subType = prefs.getInt(KEY_SUB_TYPE, -1).takeIf { it > 0 }
        val subName = prefs.getString(KEY_SUB_NAME, null)?.takeIf { it.isNotBlank() }
        val year = prefs.getString(KEY_YEAR, null)?.takeIf { it.isNotBlank() }
        return Memory(primary, subType, subName, year)
    }

    fun write(primaryTypeId: Int, subTypeId: Int?, subName: String?, year: String?) {
        prefs.edit()
            .putInt(KEY_PRIMARY, primaryTypeId)
            .putInt(KEY_SUB_TYPE, subTypeId ?: -1)
            .putString(KEY_SUB_NAME, subName)
            .putString(KEY_YEAR, year)
            .apply()
    }

    fun reset() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val KEY_PRIMARY = "primary_type_id"
        const val KEY_SUB_TYPE = "sub_type_id"
        const val KEY_SUB_NAME = "sub_name"
        const val KEY_YEAR = "year"
    }
}

/**
 * 子分类数量角标缓存（t34 §2）：`typeId -> (total, savedAt)`，24h 有效。
 * 用 SharedPreferences 而不是文件缓存：条目只有几十个，读写更快、无需清理。
 */
class FacetCountCache(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("tvbox_facet_counts", Context.MODE_PRIVATE)

    fun total(typeId: Int, nowMs: Long = System.currentTimeMillis()): Int? {
        val savedAt = prefs.getLong("$typeId.at", 0L)
        if (!CategoryFacets.isFresh(savedAt, nowMs)) return null
        val total = prefs.getInt("$typeId.total", -1)
        return total.takeIf { it >= 0 }
    }

    fun put(typeId: Int, total: Int, nowMs: Long = System.currentTimeMillis()) {
        prefs.edit().putInt("$typeId.total", total).putLong("$typeId.at", nowMs).apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }
}

/** 筛选能力探测结果缓存（t34 §3）：7 天有效。 */
class CapabilityCache(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("tvbox_filter_caps", Context.MODE_PRIVATE)

    fun read(key: String, nowMs: Long = System.currentTimeMillis()): FilterCapability? {
        val savedAt = prefs.getLong("$key.at", 0L)
        if (!CategoryFacets.isFresh(savedAt, nowMs, CategoryFacets.PROBE_TTL_MS)) return null
        val area = prefs.getString("$key.area", null) ?: return null
        val sort = prefs.getString("$key.sort", null) ?: return null
        return FilterCapability(
            area = runCatching { FilterSupport.valueOf(area) }.getOrDefault(FilterSupport.UNKNOWN),
            sort = runCatching { FilterSupport.valueOf(sort) }.getOrDefault(FilterSupport.UNKNOWN),
        )
    }

    fun put(key: String, capability: FilterCapability, nowMs: Long = System.currentTimeMillis()) {
        prefs.edit()
            .putString("$key.area", capability.area.name)
            .putString("$key.sort", capability.sort.name)
            .putLong("$key.at", nowMs)
            .apply()
    }
}
