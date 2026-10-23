package com.tvbox.desktop.update

/**
 * 桌面端语义版本比较（纯函数）：
 *  - 去掉前缀 `v` / `V`，只取开头数字与点组成的核心（`v0.3.4-beta` → `0.3.4`）；
 *  - 按 `.` 拆段逐段比较数字，缺段补 0（`1.0` == `1.0.0`）；
 *  - 不做字符串比较（否则 `0.3.10` 会被判成小于 `0.3.9`）。
 */
object DesktopVersion {

    fun core(raw: String?): String {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return ""
        val withoutPrefix = if (trimmed.startsWith("v") || trimmed.startsWith("V")) trimmed.substring(1) else trimmed
        return Regex("^\\d+(?:\\.\\d+)*").find(withoutPrefix)?.value ?: ""
    }

    fun parts(raw: String?): List<Int> {
        val core = core(raw)
        if (core.isEmpty()) return listOf(0)
        return core.split('.').map { it.toIntOrNull() ?: 0 }
    }

    /** 负数 = a 旧，0 = 相同，正数 = a 新。 */
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

    /** remote 是否比 current 新；相等返回 false。 */
    fun isNewer(current: String?, remote: String?): Boolean = compare(remote, current) > 0
}
