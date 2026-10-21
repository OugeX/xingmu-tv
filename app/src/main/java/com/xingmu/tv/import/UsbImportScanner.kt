package com.xingmu.tv.import

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import androidx.annotation.RequiresApi
import com.xingmu.tv.core.AppDispatchers
import com.xingmu.tv.core.TvLog
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream

/**
 * U 盘导入：扫描可移除存储卷根目录的 `*.json / *.m3u / *.txt`。
 *
 * 只读根目录（不做递归，避免低端盒子 IO 抖动）；每次最多返回 20 个文件。
 * 权限：Android 6+ 通过 SAF 或系统授权访问；无权限时返回空列表（不崩溃）。
 */
data class UsbSourceFile(
    val path: String,
    val name: String,
    val sizeBytes: Long,
    val modifiedAt: Long,
    val volumeName: String,
)

class UsbImportScanner(context: Context) {

    private val appContext = context.applicationContext

    fun scan(limit: Int = 20): List<UsbSourceFile> {
        val out = ArrayList<UsbSourceFile>()
        for (root in removableRoots()) {
            try {
                val children = root.listFiles() ?: continue
                for (f in children) {
                    if (!f.isFile || !f.canRead()) continue
                    if (f.length() <= 0 || f.length() > MAX_FILE_BYTES) continue
                    if (f.name.startsWith(".")) continue
                    if (!hasSupportedExtension(f.name)) continue
                    out.add(
                        UsbSourceFile(
                            path = f.absolutePath,
                            name = f.name,
                            sizeBytes = f.length(),
                            modifiedAt = f.lastModified(),
                            volumeName = root.name,
                        ),
                    )
                }
            } catch (t: Throwable) {
                TvLog.w(TAG, "扫描 ${root.absolutePath} 失败：${t.message}")
            }
        }
        return out.sortedWith(compareByDescending<UsbSourceFile> { it.modifiedAt }.thenBy { it.name })
            .take(limit)
    }

    fun read(file: UsbSourceFile, maxBytes: Int = MAX_FILE_BYTES): ByteArray? = readPath(file.path, maxBytes)

    fun readPath(path: String, maxBytes: Int = MAX_FILE_BYTES): ByteArray? {
        val f = File(path)
        if (!f.isFile || !f.canRead()) return null
        return try {
            FileInputStream(f).use { input ->
                val buf = ByteArray(minOf(f.length(), maxBytes.toLong()).toInt().coerceAtLeast(1))
                var read = 0
                while (read < buf.size) {
                    val n = input.read(buf, read, buf.size - read)
                    if (n <= 0) break
                    read += n
                }
                if (read == 0) null else buf.copyOf(read)
            }
        } catch (t: Throwable) {
            TvLog.w(TAG, "读取 $path 失败：${t.message}")
            null
        }
    }

    suspend fun scanAsync(limit: Int = 20): List<UsbSourceFile> = withContext(AppDispatchers.Io) { scan(limit) }

    companion object {
        private const val TAG = "UsbImport"
        const val MAX_FILE_BYTES: Int = 8 * 1024 * 1024
        private val EXTENSIONS = setOf("json", "m3u", "m3u8", "txt")

        fun hasSupportedExtension(name: String): Boolean {
            val ext = name.substringAfterLast('.', "").lowercase()
            return ext in EXTENSIONS
        }
    }

    /** 可移除存储卷根目录（API 24+ 用 StorageManager，21-23 退化枚举 /storage）。 */
    private fun removableRoots(): List<File> {
        val roots = ArrayList<File>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                appendRemovableVolumeRoots(roots)
            } catch (t: Throwable) {
                TvLog.w(TAG, "StorageManager 枚举失败", t)
            }
        }
        if (roots.isEmpty()) {
            val storage = File("/storage")
            val skip = setOf("emulated", "self", "sdcard0")
            storage.listFiles()?.forEach { f ->
                if (f.isDirectory && f.name !in skip && f.canRead()) roots.add(f)
            }
        }
        return roots.distinctBy { it.absolutePath }
    }

    /** API 24+（StorageVolume.isPrimary/isRemovable 均为 24 新增）。 */
    @RequiresApi(Build.VERSION_CODES.N)
    private fun appendRemovableVolumeRoots(roots: MutableList<File>) {
        val sm = appContext.getSystemService(Context.STORAGE_SERVICE) as? StorageManager ?: return
        sm.storageVolumes?.forEach { volume ->
            if (volume.isPrimary) return@forEach
            if (!volume.isRemovable) return@forEach
            val dir = volumeDirectory(volume)
            if (dir != null && dir.isDirectory) roots.add(dir)
        }
    }

    /** API 24+；API 30 起优先 `getDirectory()`，更低版本反射 `getPath()`。 */
    @RequiresApi(Build.VERSION_CODES.N)
    private fun volumeDirectory(volume: StorageVolume): File? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            volumeDirectoryApi30(volume)?.let { return it }
        }
        return try {
            @SuppressLint("DiscouragedPrivateApi")
            val method = StorageVolume::class.java.getMethod("getPath")
            (method.invoke(volume) as? String)?.let { File(it) }
        } catch (t: Throwable) {
            null
        }
    }

    /** API 30+：StorageVolume.getDirectory()。 */
    @RequiresApi(Build.VERSION_CODES.R)
    private fun volumeDirectoryApi30(volume: StorageVolume): File? = try {
        volume.directory
    } catch (_: Throwable) {
        null
    }
}
