package com.xingmu.tv.data

import android.content.Context
import com.xingmu.tv.core.AppDispatchers
import com.xingmu.tv.core.TvLog
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 解析 assets/overseas_map.json：每个源独立的海外子分类 typeId。
 * 结构：{ "<sourceId>": { "typeIds": [...], "names": [...] } }
 */
class OverseasMap(private val context: Context) {

    data class SourceTypes(val typeIds: List<Int>, val names: List<String>)

    @Volatile
    private var cached: Map<String, SourceTypes>? = null

    suspend fun load(): Map<String, SourceTypes> {
        cached?.let { return it }
        return withContext(AppDispatchers.IoLimited) {
            val result = LinkedHashMap<String, SourceTypes>()
            try {
                val text = context.assets.open(ASSET_NAME).bufferedReader(Charsets.UTF_8).use { it.readText() }
                val root = JSONObject(text)
                val keys = root.keys()
                while (keys.hasNext()) {
                    val sourceId = keys.next()
                    val obj = root.optJSONObject(sourceId) ?: continue
                    val typeIds = mutableListOf<Int>()
                    val names = mutableListOf<String>()
                    obj.optJSONArray("typeIds")?.let { array ->
                        for (i in 0 until array.length()) typeIds.add(array.optInt(i, -1))
                    }
                    obj.optJSONArray("names")?.let { array ->
                        for (i in 0 until array.length()) names.add(array.optString(i))
                    }
                    if (typeIds.isNotEmpty()) {
                        result[sourceId] = SourceTypes(typeIds, names)
                    }
                }
            } catch (t: Throwable) {
                TvLog.w("OverseasMap", "读取 $ASSET_NAME 失败", t)
            }
            cached = result
            result
        }
    }

    /** 按海外分区名（别名）找到该源的真实 typeId。 */
    fun typeIdFor(sourceTypes: SourceTypes?, section: CanonicalCategory.Section): Int? {
        if (sourceTypes == null) return null
        val index = sourceTypes.names.indexOfFirst { name ->
            section.aliases.any { alias -> name == alias || name.contains(alias) }
        }
        return if (index in sourceTypes.typeIds.indices) sourceTypes.typeIds[index] else null
    }

    private companion object {
        const val ASSET_NAME = "overseas_map.json"
    }
}
