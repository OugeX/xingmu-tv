package com.tvbox.desktop.data

import com.tvbox.desktop.core.Log
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * 小型 JSON 状态文件的原子读写：先写同目录 .tmp，再 move 覆盖。
 * 用于 progress.json / favorites.json / recent.json，避免退出时留下半截文件。
 */
internal object JsonStorage {

    fun read(file: Path): JSONObject? {
        if (!Files.isRegularFile(file)) return null
        return runCatching { JSONObject(Files.readString(file, StandardCharsets.UTF_8)) }
            .onFailure { Log.w("JsonStorage", "读取 ${file.fileName} 失败：${it.message}") }
            .getOrNull()
    }

    fun write(file: Path, json: JSONObject) {
        try {
            val parent = file.toAbsolutePath().parent
            if (parent != null) Files.createDirectories(parent)
            val tmp = parent.resolve(file.fileName.toString() + ".tmp")
            Files.writeString(tmp, json.toString(), StandardCharsets.UTF_8)
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: Exception) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (t: Throwable) {
            Log.w("JsonStorage", "写入 ${file.fileName} 失败：${t.message}")
        }
    }
}
