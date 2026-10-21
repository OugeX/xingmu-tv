package com.tvbox.desktop.data

import com.tvbox.desktop.core.Log
import org.json.JSONObject
import java.nio.file.Files

/** 桌面端用户设置（JSON 落盘，字段只增不改，未知字段忽略）。 */
data class AppSettings(
    val vlcHome: String = "",
    val volume: Int = 80,
    val maxQuality: String = "auto",
    val hardwareDecode: Boolean = true,
    val disabledSourceIds: Set<String> = emptySet(),
) {
    fun save(paths: AppPaths) {
        val json = JSONObject()
            .put("version", 1)
            .put("vlcHome", vlcHome)
            .put("volume", volume)
            .put("maxQuality", maxQuality)
            .put("hardwareDecode", hardwareDecode)
            .put("disabledSourceIds", disabledSourceIds.toList())
        runCatching {
            Files.createDirectories(paths.configDir)
            Files.writeString(paths.settingsFile, json.toString(2))
        }.onFailure { Log.w("Settings", "保存设置失败: ${it.message}") }
    }

    companion object {
        fun load(paths: AppPaths): AppSettings = try {
            if (!Files.isRegularFile(paths.settingsFile)) {
                AppSettings()
            } else {
                val json = JSONObject(Files.readString(paths.settingsFile))
                val disabled = json.optJSONArray("disabledSourceIds")?.let { array ->
                    buildSet {
                        for (i in 0 until array.length()) add(array.optString(i))
                    }
                } ?: emptySet()
                AppSettings(
                    vlcHome = json.optString("vlcHome", ""),
                    volume = json.optInt("volume", 80).coerceIn(0, 100),
                    maxQuality = json.optString("maxQuality", "auto").ifBlank { "auto" },
                    hardwareDecode = json.optBoolean("hardwareDecode", true),
                    disabledSourceIds = disabled,
                )
            }
        } catch (t: Throwable) {
            Log.w("Settings", "读取设置失败，使用默认值: ${t.message}")
            AppSettings()
        }
    }
}
