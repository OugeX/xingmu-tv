package com.tvbox.app.ui.settings

import android.content.Context
import com.tvbox.app.ui.DisplayPrefs
import com.tvbox.app.ui.FontTier

/**
 * 设置项（docs/UI_SPEC.md §0.5/§0.6、PLAYBACK_SPEC、SEARCH_SPEC §1）：
 * 全部持久化，AppRoot 冷启动首帧读取；显示类立即生效，播放器类在下一次起播/设置变更时下发。
 */
data class AppSettingsValues(
    val fontTier: FontTier = FontTier.STANDARD,
    /** 过扫描补偿：0 / 2.5 / 5（百分比） */
    val overscanPercent: Float = 0f,
    /** C 保命模式（与低内存自动判定叠加） */
    val forceLite: Boolean = false,
    /** 直播 4K 优先（能力探测不通过时自动跳过） */
    val prefer4K: Boolean = false,
    /** 硬解优先（软解回退仍保留） */
    val hardwareFirst: Boolean = true,
    /** 画质上限：0=自动，其它=1080/720/480 */
    val qualityCap: Int = 0,
    /** 音画校准 −500..+500ms */
    val audioDelayMs: Int = 0,
    /** 字幕字号：0 小 / 1 标准 / 2 大 */
    val subtitleSize: Int = 1,
    val subtitleOutline: Boolean = true,
    /** 自建 ASR（OpenAI 兼容 /audio/transcriptions） */
    val asrEndpoint: String = "",
    val asrKey: String = "",
    val asrModel: String = "whisper-1",
    /** t27：使用预置详情包（378KB 下载，详情冷开秒开），默认开 */
    val seedDetails: Boolean = true,
) {
    val asrConfigured: Boolean get() = asrEndpoint.startsWith("http") && asrKey.isNotBlank()

    fun subtitleSp(): Float = when (subtitleSize) {
        0 -> 18f
        2 -> 30f
        else -> 24f
    }
}

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("tvbox_settings", Context.MODE_PRIVATE)
    private val displayPrefs = DisplayPrefs(context)

    fun read(): AppSettingsValues = AppSettingsValues(
        fontTier = runCatching {
            prefs.getString(KEY_FONT_TIER, null)?.let { FontTier.valueOf(it) }
        }.getOrNull() ?: displayPrefs.readTier(),
        overscanPercent = prefs.getFloat(KEY_OVERSCAN, 0f),
        forceLite = prefs.getBoolean(KEY_FORCE_LITE, false),
        prefer4K = prefs.getBoolean(KEY_PREFER_4K, false),
        hardwareFirst = prefs.getBoolean(KEY_HARDWARE_FIRST, true),
        qualityCap = prefs.getInt(KEY_QUALITY_CAP, 0),
        audioDelayMs = prefs.getInt(KEY_AUDIO_DELAY, 0).coerceIn(-500, 500),
        subtitleSize = prefs.getInt(KEY_SUBTITLE_SIZE, 1).coerceIn(0, 2),
        subtitleOutline = prefs.getBoolean(KEY_SUBTITLE_OUTLINE, true),
        asrEndpoint = prefs.getString(KEY_ASR_ENDPOINT, "").orEmpty(),
        asrKey = prefs.getString(KEY_ASR_KEY, "").orEmpty(),
        asrModel = prefs.getString(KEY_ASR_MODEL, "whisper-1").orEmpty().ifBlank { "whisper-1" },
        seedDetails = prefs.getBoolean(KEY_SEED_DETAILS, true),
    )

    fun write(values: AppSettingsValues) {
        prefs.edit()
            .putString(KEY_FONT_TIER, values.fontTier.name)
            .putFloat(KEY_OVERSCAN, values.overscanPercent)
            .putBoolean(KEY_FORCE_LITE, values.forceLite)
            .putBoolean(KEY_PREFER_4K, values.prefer4K)
            .putBoolean(KEY_HARDWARE_FIRST, values.hardwareFirst)
            .putInt(KEY_QUALITY_CAP, values.qualityCap)
            .putInt(KEY_AUDIO_DELAY, values.audioDelayMs.coerceIn(-500, 500))
            .putInt(KEY_SUBTITLE_SIZE, values.subtitleSize.coerceIn(0, 2))
            .putBoolean(KEY_SUBTITLE_OUTLINE, values.subtitleOutline)
            .putString(KEY_ASR_ENDPOINT, values.asrEndpoint.trim())
            .putString(KEY_ASR_KEY, values.asrKey.trim())
            .putString(KEY_ASR_MODEL, values.asrModel.trim())
            .putBoolean(KEY_SEED_DETAILS, values.seedDetails)
            .apply()
        // 兼容 t4/t12 已写入的显示档位
        displayPrefs.writeTier(values.fontTier)
    }

    private companion object {
        const val KEY_FONT_TIER = "font_tier"
        const val KEY_OVERSCAN = "overscan_percent"
        const val KEY_FORCE_LITE = "force_lite"
        const val KEY_PREFER_4K = "prefer_4k"
        const val KEY_HARDWARE_FIRST = "hardware_first"
        const val KEY_QUALITY_CAP = "quality_cap"
        const val KEY_AUDIO_DELAY = "audio_delay_ms"
        const val KEY_SUBTITLE_SIZE = "subtitle_size"
        const val KEY_SUBTITLE_OUTLINE = "subtitle_outline"
        const val KEY_ASR_ENDPOINT = "asr_endpoint"
        const val KEY_ASR_KEY = "asr_key"
        const val KEY_ASR_MODEL = "asr_model"
        const val KEY_SEED_DETAILS = "seed_details"
    }
}

/** 过扫描百分比 → 水平/垂直 dp（0% 也留 16/9dp 安全边，避免贴边）。 */
fun overscanDp(percent: Float, screenWidthDp: Int, screenHeightDp: Int): Pair<Int, Int> {
    if (percent <= 0f) return 16 to 9
    val h = (screenWidthDp * percent / 100f).toInt().coerceIn(8, 96)
    val v = (screenHeightDp * percent / 100f).toInt().coerceIn(4, 64)
    return h to v
}
