package com.xingmu.tv.update

/**
 * 语义化版本比较（UPDATE_SPEC §1）：
 * - 去掉前缀 `v` / `V`，只取开头由数字与点组成的部分（`v0.3.4-beta` → `0.3.4`）；
 * - 按 `.` 拆段逐段比数字，缺段补 0；
 * - 不做字符串比较（否则 `0.3.10` 会被判成小于 `0.3.9`）。
 */
object UpdateVersion {

    /** 仅保留版本核心：`v0.3.4-r8` / `0.3.4` / `V0.3.4` → `0.3.4`。 */
    fun core(raw: String?): String {
        val trimmed = raw.orEmpty().trim()
        val withoutPrefix = when {
            trimmed.startsWith("v") || trimmed.startsWith("V") -> trimmed.substring(1)
            else -> trimmed
        }
        val end = withoutPrefix.indexOfFirst { it != '.' && !it.isDigit() }
        val core = if (end >= 0) withoutPrefix.substring(0, end) else withoutPrefix
        return core.trim('.')
    }

    /** 拆成数字段；非法段按 0 处理（GitHub tag 偶尔带 `-beta` 后缀，core 已裁掉）。 */
    fun parts(raw: String?): List<Int> =
        core(raw).split('.').map { it.toIntOrNull() ?: 0 }

    /** 比较两个版本：负数 = a 旧，0 = 相同，正数 = a 新。 */
    fun compare(a: String?, b: String?): Int {
        val left = parts(a)
        val right = parts(b)
        val size = maxOf(left.size, right.size)
        for (i in 0 until size) {
            val x = left.getOrElse(i) { 0 }
            val y = right.getOrElse(i) { 0 }
            if (x != y) return x.compareTo(y)
        }
        return 0
    }

    /** remote 是否比 current 新（版本相同返回 false）。 */
    fun isNewer(current: String?, remote: String?): Boolean = compare(remote, current) > 0

    /**
     * 资产文件名是否包含目标版本号。
     *
     * 用数字边界匹配，避免 `0.3.4` 命中 `v0.3.40`；`xingmu-tv-v0.3.4-abc.apk` 命中。
     */
    fun assetMatches(name: String, version: String): Boolean {
        val v = core(version)
        if (v.isEmpty()) return false
        val pattern = Regex("(^|[^0-9.])" + Regex.escape(v) + "([^0-9]|$)")
        return pattern.containsMatchIn(name)
    }
}
