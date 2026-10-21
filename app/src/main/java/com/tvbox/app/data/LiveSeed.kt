package com.tvbox.app.data

import android.content.Context
import com.tvbox.app.core.AppDispatchers
import com.tvbox.app.core.TvLog
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 直播种子解析：assets/live_seed.json → List<Channel>（t9 直播页消费）。
 * 只做展示所需字段；完整探测证据保留在 JSON 里，不进入内存。
 */
class LiveSeed(private val context: Context) {

    @Volatile
    private var cached: List<Channel>? = null

    suspend fun load(): List<Channel> {
        cached?.let { return it }
        return withContext(AppDispatchers.IoLimited) {
            val channels = mutableListOf<Channel>()
            try {
                val text = context.assets.open(ASSET_NAME).bufferedReader(Charsets.UTF_8).use { it.readText() }
                val root = JSONObject(text)
                val array = root.optJSONArray("channels")
                if (array != null) {
                    for (i in 0 until array.length()) {
                        val obj = array.optJSONObject(i) ?: continue
                        val id = obj.optString("id")
                        val name = obj.optString("name")
                        if (id.isBlank() || name.isBlank()) continue
                        channels.add(parseChannel(obj))
                    }
                }
            } catch (t: Throwable) {
                TvLog.e("LiveSeed", "读取 $ASSET_NAME 失败", t)
            }
            cached = channels
            TvLog.i("LiveSeed", "直播频道 ${channels.size} 个")
            channels
        }
    }

    private fun parseChannel(obj: JSONObject): Channel {
        val sourceMeta = HashMap<String, JSONObject>()
        obj.optJSONArray("sources")?.let { sources ->
            for (i in 0 until sources.length()) {
                val source = sources.optJSONObject(i) ?: continue
                sourceMeta[source.optString("url")] = source
            }
        }
        val urls = mutableListOf<ChannelUrl>()
        obj.optJSONArray("urls")?.let { array ->
            for (i in 0 until array.length()) {
                val url = array.optString(i).trim()
                if (url.isEmpty()) continue
                val meta = sourceMeta[url]
                urls.add(
                    ChannelUrl(
                        url = url,
                        quality = meta?.optString("quality")?.takeIf { it.isNotBlank() },
                        resolution = meta?.optString("resolution")?.takeIf { it.isNotBlank() },
                        latencyMs = meta?.optInt("ttfb_ms", -1)?.takeIf { it >= 0 },
                        fallbackOnly = meta?.optBoolean("fallback_only", false) ?: false,
                    ),
                )
            }
        }
        return Channel(
            id = obj.optString("id"),
            name = obj.optString("name"),
            group = obj.optString("group"),
            logo = obj.optString("logo").takeIf { it.isNotBlank() },
            tvgId = obj.optString("tvg_id").takeIf { it.isNotBlank() },
            desc = obj.optString("desc").takeIf { it.isNotBlank() },
            urls = urls,
        )
    }

    private companion object {
        const val ASSET_NAME = "live_seed.json"
    }
}
