package com.tvbox.desktop.data

import org.json.JSONObject
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/** 测试用：从 desktop/ 或仓库根向上找 assets/。 */
object TestAssets {
    fun dir(): Path {
        var dir: Path? = Paths.get("").toAbsolutePath()
        repeat(5) {
            val candidate = dir?.resolve("assets")
            if (candidate != null && Files.isRegularFile(candidate.resolve("sources.builtin.json"))) {
                return candidate
            }
            dir = dir?.parent
        }
        error("找不到仓库 assets/ 目录（测试需从 desktop/ 运行）")
    }

    fun read(name: String): String = Files.readString(dir().resolve(name))
}

fun jsonOf(text: String): JSONObject = JSONObject(text)
