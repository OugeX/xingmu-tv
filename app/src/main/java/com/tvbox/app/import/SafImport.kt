package com.tvbox.app.import

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import com.tvbox.app.core.TvLog
import java.io.ByteArrayOutputStream

/**
 * SAF（Storage Access Framework）导入：`ACTION_OPEN_DOCUMENT` 选文件 → 读流 → 同一条导入管线。
 *
 * UI（t4）只负责：
 * ```
 * activityResultLauncher.launch(SafImport.openDocumentIntent())
 * // onResult: ImportService.get(context).importFromUri(uri)
 * ```
 * 不申请存储权限、不复制文件到私有目录。
 */
object SafImport {

    private const val TAG = "SafImport"
    const val MAX_FILE_BYTES: Int = 8 * 1024 * 1024

    /** 文件选择器 Intent（MIME 白名单：json / m3u / 纯文本 + 兜底任意类型，供厂商文件管理器识别）。 */
    fun openDocumentIntent(): Intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        type = "*/*"
        putExtra(
            Intent.EXTRA_MIME_TYPES,
            arrayOf(
                "application/json",
                "text/plain",
                "text/x-mpegurl",
                "audio/x-mpegurl",
                "application/vnd.apple.mpegurl",
                "application/octet-stream",
            ),
        )
    }

    /** 读取 URI 内容（最多 [maxBytes]），失败返回 null。 */
    fun readBytes(context: Context, uri: Uri, maxBytes: Int = MAX_FILE_BYTES): ByteArray? {
        val resolver = context.contentResolver
        return try {
            resolver.openInputStream(uri)?.use { input ->
                val out = ByteArrayOutputStream(64 * 1024)
                val buf = ByteArray(16 * 1024)
                var total = 0
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    val keep = minOf(n, maxBytes - total)
                    if (keep > 0) {
                        out.write(buf, 0, keep)
                        total += keep
                    }
                    if (total >= maxBytes) break
                }
                if (total == 0) null else out.toByteArray()
            }
        } catch (t: Throwable) {
            TvLog.w(TAG, "读取 $uri 失败：${t.message}")
            null
        }
    }

    /** 文件名（用于生成源 id/名称；拿不到返回 null）。 */
    fun displayName(context: Context, uri: Uri): String? {
        return try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else uri.lastPathSegment
            }
        } catch (t: Throwable) {
            uri.lastPathSegment
        }
    }

    fun mimeType(context: Context, uri: Uri): String? = try {
        context.contentResolver.getType(uri)
    } catch (t: Throwable) {
        null
    }
}
