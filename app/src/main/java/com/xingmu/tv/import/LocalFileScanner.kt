package com.xingmu.tv.import

import android.content.Context
import android.os.Environment
import com.xingmu.tv.core.TvLog
import java.io.File

/**
 * 本机可读目录扫描（系统没有 DocumentsUI 的电视盒子兜底）。
 *
 * 背景：部分电视盒子（实测 LeTV X4-50 / Android 6）**没有 ACTION_OPEN_DOCUMENT 的处理器**，
 * 系统文件选择器会直接抛 ActivityNotFoundException。此时引导用户把文件放到 App 自己的
 * 外部目录（`Android/data/com.xingmu.tv/files/import/`，adb 可 push、无需存储权限），
 * 再从本列表导入——仍然是同一条 L0–L4 管线。
 */
object LocalFileScanner {

    private const val TAG = "LocalFile"
    private const val MAX_BYTES = 8 * 1024 * 1024
    private val EXTENSIONS = setOf("json", "m3u", "m3u8", "txt")

    fun scan(context: Context, limit: Int = 20): List<UsbSourceFile> {
        val app = context.applicationContext
        val candidates = ArrayList<Pair<File, String>>()
        app.getExternalFilesDir(null)?.let { ext ->
            candidates.add(File(ext, "import") to "应用外部目录/import")
            candidates.add(ext to "应用外部目录")
        }
        candidates.add(File(app.filesDir, "import") to "应用内部目录/import")
        runCatching {
            val downloads = File(Environment.getExternalStorageDirectory(), "Download")
            candidates.add(downloads to "下载目录")
        }
        // 预创建可写目录，方便用户/adb 直接放文件（无系统文件选择器的盒子唯一入口）
        candidates.forEach { (dir, _) ->
            if (!dir.exists()) runCatching { dir.mkdirs() }
        }

        val out = ArrayList<UsbSourceFile>()
        for ((dir, label) in candidates) {
            try {
                if (!dir.isDirectory || !dir.canRead()) continue
                dir.listFiles()?.forEach { f ->
                    if (!f.isFile || !f.canRead()) return@forEach
                    if (f.length() <= 0 || f.length() > MAX_BYTES) return@forEach
                    if (f.name.startsWith(".")) return@forEach
                    if (f.name.substringAfterLast('.', "").lowercase() !in EXTENSIONS) return@forEach
                    out.add(
                        UsbSourceFile(
                            path = f.absolutePath,
                            name = f.name,
                            sizeBytes = f.length(),
                            modifiedAt = f.lastModified(),
                            volumeName = label,
                        ),
                    )
                }
            } catch (t: Throwable) {
                TvLog.w(TAG, "扫描 ${dir.absolutePath} 失败：${t.message}")
            }
        }
        return out.distinctBy { it.path }
            .sortedByDescending { it.modifiedAt }
            .take(limit)
    }

    /** 提示文案里展示的可写目录（没有系统文件选择器时用户要手动放文件）。 */
    fun importDirHint(context: Context): String {
        val ext = context.applicationContext.getExternalFilesDir(null)
        return if (ext != null) {
            ext.absolutePath + "/import/"
        } else {
            runCatching {
                Environment.getExternalStorageDirectory().absolutePath + "/Android/data/" +
                    context.packageName + "/files/import/"
            }.getOrDefault("Android/data/<包名>/files/import/")
        }
    }
}
