package com.tvbox.app.data

import android.content.Context
import com.tvbox.app.core.AppDispatchers
import com.tvbox.app.core.TvLog
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * 内置源装载：解析 assets/sources.builtin.json（`tvapp.source.v1.1` bundle）。
 * bundle 会被展开为多条 [SourceConfig]；首次读取后常驻内存（<32KB）。
 */
class BuiltinSources(private val context: Context) {

    @Volatile
    private var cached: List<SourceConfig>? = null

    suspend fun load(): List<SourceConfig> {
        cached?.let { return it }
        return withContext(AppDispatchers.IoLimited) {
            val text = try {
                context.assets.open(ASSET_NAME).bufferedReader(Charsets.UTF_8).use { it.readText() }
            } catch (t: Throwable) {
                TvLog.e("BuiltinSources", "读取 $ASSET_NAME 失败", t)
                return@withContext emptyList()
            }
            val parsed = parse(text)
            cached = parsed
            TvLog.i("BuiltinSources", "内置源 ${parsed.size} 个")
            parsed
        }
    }

    internal fun parse(text: String): List<SourceConfig> = try {
        val root = JSONObject(text)
        val out = mutableListOf<SourceConfig>()
        collect(root, out)
        out.distinctBy { it.id }
    } catch (t: Throwable) {
        TvLog.e("BuiltinSources", "解析内置源失败", t)
        emptyList()
    }

    private fun collect(obj: JSONObject, out: MutableList<SourceConfig>) {
        val kind = obj.optString("kind").lowercase()
        if (kind == "bundle") {
            val sources = obj.optJSONArray("sources") ?: JSONArray()
            for (i in 0 until sources.length()) {
                sources.optJSONObject(i)?.let { collect(it, out) }
            }
            return
        }
        val id = obj.optString("id").trim()
        val name = obj.optString("name").trim()
        if (id.isBlank() || name.isBlank()) return
        val baseUrl = obj.optString("baseUrl").trim().ifBlank { obj.optString("url").trim() }
        val fallback = mutableListOf<String>()
        obj.optJSONArray("fallbackBaseUrls")?.let { array ->
            for (i in 0 until array.length()) {
                val url = array.optString(i).trim()
                if (url.isNotEmpty()) fallback.add(url)
            }
        }
        val categories = LinkedHashMap<String, List<Int>>()
        obj.optJSONObject("categories")?.let { cats ->
            val keys = cats.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val values = mutableListOf<Int>()
                cats.optJSONArray(key)?.let { numbers ->
                    for (i in 0 until numbers.length()) {
                        val value = numbers.optInt(i, -1)
                        if (value > 0) values.add(value)
                    }
                }
                if (values.isNotEmpty()) categories[key.uppercase()] = values
            }
        }
        val headers = LinkedHashMap<String, String>()
        obj.optJSONObject("headers")?.let { headersObj ->
            val keys = headersObj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                headers[key] = headersObj.optString(key)
            }
        }
        val adBlock = obj.optJSONObject("adBlock")
        val domains = mutableListOf<String>()
        val keywords = mutableListOf<String>()
        adBlock?.optJSONArray("domains")?.let { array ->
            for (i in 0 until array.length()) array.optString(i).takeIf { it.isNotBlank() }?.let(domains::add)
        }
        adBlock?.optJSONArray("urlKeywords")?.let { array ->
            for (i in 0 until array.length()) array.optString(i).takeIf { it.isNotBlank() }?.let(keywords::add)
        }
        out.add(
            SourceConfig(
                id = id,
                name = name,
                kind = when (kind) {
                    "m3u" -> SourceKind.M3U
                    "bundle" -> SourceKind.BUNDLE
                    else -> SourceKind.MACCMS
                },
                baseUrl = normalizeBase(baseUrl),
                fallbackBaseUrls = fallback.map(::normalizeBase).filter { it.isNotBlank() },
                priority = obj.optInt("priority", 100),
                enabled = obj.optBoolean("enabled", true),
                headers = headers,
                timeoutMs = obj.optInt("timeoutMs", 6000).coerceIn(1000, 15000),
                pageSize = obj.optInt("pageSize", 20).coerceIn(10, 50),
                categories = categories,
                adBlockDomains = domains,
                adBlockKeywords = keywords,
                skipIntroSeconds = obj.optInt("skipIntroSeconds", 0),
                maxQuality = obj.optString("maxQuality", "auto"),
            ),
        )
    }

    private fun normalizeBase(url: String): String = when {
        url.isBlank() -> ""
        url.endsWith('/') -> url
        else -> "$url/"
    }

    private companion object {
        const val ASSET_NAME = "sources.builtin.json"
    }
}
