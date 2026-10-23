package com.tvbox.desktop

/** 版本号来自构建生成的 version.properties（Gradle version），不在 UI 里硬编码。 */
object BuildInfo {
    val version: String by lazy {
        runCatching {
            BuildInfo::class.java.getResourceAsStream("/XingmuTV-version.properties")?.use { stream ->
                stream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                    lines.map { it.trim() }
                        .firstOrNull { it.startsWith("version=") }
                        ?.substringAfter('=')
                        ?.trim()
                }
            }
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: "dev"
    }
}
