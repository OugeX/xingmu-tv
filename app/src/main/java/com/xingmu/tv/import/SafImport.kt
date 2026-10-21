package com.xingmu.tv.import

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import com.xingmu.tv.core.TvLog
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

    /**
     * 本机是否存在可用的文件选择器。
     * 实测部分电视盒子（LeTV X4-50 / Android 6）只注册了不带 CATEGORY_OPENABLE 的
     * `OPEN_DOCUMENT`（com.stv.filemanager），带 OPENABLE 的 Intent 会抛
     * ActivityNotFoundException。因此这里返回**按优先级排列、已确认可解析**的 Intent 列表，
     * UI 逐个尝试并把第一个能启动的交给系统。
     */
    @SuppressLint("QueryPermissionsNeeded")
    fun resolveLaunchableIntents(context: Context): List<Intent> {
        // API 30+ 未声明 <queries> 时 queryIntentActivities 可能返回空（包可见性），
        // 此时不做预过滤：UI 会逐个 launch 并 catch ActivityNotFoundException。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) return pickerIntents()
        return pickerIntents().filter { intent ->
            try {
                intent.resolveActivity(context.packageManager) != null
            } catch (t: Throwable) {
                false
            }
        }
    }

    /** 候选 Intent：OPEN_DOCUMENT(带/不带 OPENABLE) → GET_CONTENT(带/不带 OPENABLE)。 */
    fun pickerIntents(): List<Intent> {
        val mimes = arrayOf(
            "application/json",
            "text/plain",
            "application/octet-stream",
            "audio/x-mpegurl",
            "application/vnd.apple.mpegurl",
        )
        fun build(action: String, openable: Boolean, withMime: Boolean): Intent =
            Intent(action).apply {
                type = "*/*"
                if (openable) addCategory(Intent.CATEGORY_OPENABLE)
                if (withMime) putExtra(Intent.EXTRA_MIME_TYPES, mimes)
            }
        return listOf(
            build(Intent.ACTION_OPEN_DOCUMENT, openable = true, withMime = true),
            build(Intent.ACTION_OPEN_DOCUMENT, openable = false, withMime = true),
            build(Intent.ACTION_OPEN_DOCUMENT, openable = false, withMime = false),
            build(Intent.ACTION_GET_CONTENT, openable = true, withMime = false),
            build(Intent.ACTION_GET_CONTENT, openable = false, withMime = false),
        )
    }

    fun hasDocumentPicker(context: Context): Boolean = resolveLaunchableIntents(context).isNotEmpty()
}
