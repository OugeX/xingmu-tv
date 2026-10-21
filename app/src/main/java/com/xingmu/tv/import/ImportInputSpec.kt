package com.xingmu.tv.import

import android.content.Context
import com.xingmu.tv.core.TvLog
import com.xingmu.tv.source.UrlRules
import org.json.JSONArray

/**
 * 遥控器兜底输入的**契约**（UI 由 t4 实现，本类只给规则与数据）。
 *
 * 依据 docs/SOURCE_IMPORT_SPEC.md §4：
 * - 分组键盘只显示 URL 需要的字符（数字/字母/`: / . - _ ? = &`…）；
 * - 域名自动补全：输入 `macapi1` → `https://macapi1.com/maccms/json/baofeng/`；
 * - 最近 10 条成功导入的地址一键复用（历史持久化在 SharedPreferences）；
 * - 输入法不可用时仍可通过“历史地址”完成导入。
 */
object ImportInputSpec {

    /** 允许出现在输入框中的字符（遥控器分组键盘的并集）。 */
    const val ALLOWED_CHARS: String =
        "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ:/.-_?=&%#~+"

    /** 分组键盘行（UI 直接按行渲染；最后一行是符号）。 */
    val KEY_ROWS: List<String> = listOf(
        "1234567890",
        "qwertyuiop",
        "asdfghjkl",
        "zxcvbnm",
        ":/.-_?=&%#~+",
    )

    /** 已知域名补全表（输入前缀 → 标准 API 根地址）。 */
    private val KNOWN_HOSTS: List<Pair<String, String>> = listOf(
        "macapi1" to "https://macapi1.com/maccms/json/baofeng/",
        "bfzy" to "https://bfzyapi.com/api.php/provide/vod/",
        "ffzy" to "https://api.ffzyapi.com/api.php/provide/vod/",
        "dytt" to "https://caiji.dyttzyapi.com/api.php/provide/vod/",
        "lzi" to "https://cj.lziapi.com/api.php/provide/vod/",
        "zuidapi" to "https://api.zuidapi.com/api.php/provide/vod/",
        "guangsu" to "https://api.guangsuapi.com/api.php/provide/vod/",
        "apibdzy" to "https://api.apibdzy.com/api.php/provide/vod/",
    )

    /** 过滤非法字符（UI 逐键输入时调用）。 */
    fun sanitize(raw: String): String = buildString(raw.length) {
        for (c in raw) {
            if (ALLOWED_CHARS.indexOf(c) >= 0) append(c)
        }
    }.trim()

    /** 是否已经是一个完整可提交的地址。 */
    fun isCompleteUrl(raw: String): Boolean = UrlRules.isHttpUrl(raw.trim()) && UrlRules.hostOf(raw).isNotEmpty()

    /**
     * 候选补全（最多 6 条、去重、原样保留顺序：历史优先，其次内置域名）。
     * UI 以列表显示，用户 OK 选中即填入。
     */
    fun completions(raw: String, history: List<String>): List<String> {
        val input = raw.trim()
        val out = LinkedHashSet<String>()
        if (input.isEmpty()) {
            out.addAll(history.take(6))
            return out.toList()
        }
        for (h in history) {
            if (h.contains(input, ignoreCase = true) || input.contains(h, ignoreCase = true)) out.add(h)
            if (out.size >= 4) break
        }
        val lower = input.lowercase()
        for ((prefix, url) in KNOWN_HOSTS) {
            if (lower.startsWith(prefix) || prefix.startsWith(lower)) out.add(url)
            if (out.size >= 6) break
        }
        if (!input.startsWith("http", ignoreCase = true)) {
            if (!input.contains('.')) {
                out.add("https://$input.com/")
            } else if (!input.contains('/')) {
                out.add("https://$input/")
            } else {
                out.add("https://$input")
            }
        } else if (!input.endsWith("/") && !input.contains('?')) {
            out.add("$input/")
        }
        return out.take(6)
    }

    /** 提交前规范化：补 scheme、去空格、maccms 补结尾斜杠（校验管线仍会再校验一次）。 */
    fun normalize(raw: String): String {
        var s = sanitize(raw)
        if (s.isEmpty()) return s
        if (!s.startsWith("http://", true) && !s.startsWith("https://", true)) {
            s = "https://$s"
        }
        return s
    }

    /** 最近成功导入地址（最多 10 条，持久化）。 */
    fun history(context: Context): ImportHistory = ImportHistory(context.applicationContext)
}

/**
 * 导入地址历史（遥控器兜底输入的关键数据）。
 * 只存地址字符串（含 https:// 前缀），不存账号密码（写入前脱敏）。
 */
class ImportHistory(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun recent(limit: Int = 10): List<String> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            val out = ArrayList<String>(arr.length())
            for (i in 0 until arr.length()) {
                val s = arr.optString(i, "")
                if (s.isNotBlank()) out.add(s)
                if (out.size >= limit) break
            }
            out
        } catch (t: Throwable) {
            TvLog.w(TAG, "历史解析失败", t)
            emptyList()
        }
    }

    fun remember(url: String) {
        val clean = UrlRules.maskCredentials(url.trim())
        if (clean.isEmpty()) return
        val list = ArrayList(recent(10))
        list.removeAll { it == clean }
        list.add(0, clean)
        while (list.size > 10) list.removeAt(list.size - 1)
        val arr = JSONArray()
        list.forEach { arr.put(it) }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    fun clear() {
        prefs.edit().remove(KEY).apply()
    }

    private companion object {
        const val TAG = "ImportHistory"
        const val PREFS = "tvbox_import_history"
        const val KEY = "recent"
    }
}
