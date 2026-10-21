package com.tvbox.app.data.casual

import android.content.Context
import com.tvbox.app.core.AppDispatchers
import com.tvbox.app.core.TvLog
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 内置电台精选（离线兜底）：assets/casual_radio.json。
 * 首次进入或接口失败时使用，保证「打开就能出声」。
 */
class CasualRadioSeed(private val context: Context) {

    @Volatile
    private var cached: List<RadioStation>? = null

    suspend fun load(): List<RadioStation> {
        cached?.let { return it }
        return withContext(AppDispatchers.IoLimited) {
            val stations = mutableListOf<RadioStation>()
            try {
                val text = context.assets.open(ASSET).bufferedReader(Charsets.UTF_8).use { it.readText() }
                val root = JSONObject(text)
                val array = root.optJSONArray("stations")
                if (array != null) {
                    for (i in 0 until array.length()) {
                        val obj = array.optJSONObject(i) ?: continue
                        val name = obj.optString("name").trim()
                        val urls = mutableListOf<String>()
                        obj.optJSONArray("urls")?.let { list ->
                            for (j in 0 until list.length()) {
                                list.optString(j).trim().takeIf { it.startsWith("http") }?.let(urls::add)
                            }
                        }
                        if (name.isEmpty() || urls.isEmpty()) continue
                        stations.add(
                            RadioStation(
                                id = obj.optString("id").ifBlank { name },
                                name = name,
                                tag = obj.optString("tag"),
                                codec = obj.optString("codec").ifBlank { "UNKNOWN" },
                                bitrateKbps = obj.optInt("bitrateKbps", 0),
                                urls = urls,
                                logo = obj.optString("logo").takeIf { it.startsWith("http") },
                                group = obj.optString("group").ifBlank { "内置精选" },
                            ),
                        )
                    }
                }
            } catch (t: Throwable) {
                TvLog.e(TAG, "读取 $ASSET 失败", t)
            }
            cached = stations
            TvLog.i(TAG, "内置电台 ${stations.size} 个")
            stations
        }
    }

    private companion object {
        const val TAG = "CasualRadioSeed"
        const val ASSET = "casual_radio.json"
    }
}
