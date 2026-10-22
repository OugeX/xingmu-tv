package com.xingmu.tv.update

/**
 * 更新资产选择（UPDATE_SPEC §2）：
 *
 * - 只接受 `.apk` 且文件名包含目标版本号的资产（`app-debug.apk` 之类会被排除）；
 * - `/data` 可用空间 < 300MB → 优先 R8 包（文件名含 `-r8-`），否则优先标准包；
 * - 首选风格不存在时回退另一种风格（有包总比没有强）；
 * - 一个可用 APK 都没有 → 返回 null（调用方退化成"只给 Release 页面 URL"）。
 */
object UpdateAssetSelector {

    const val LOW_STORAGE_BYTES: Long = 300L * 1024 * 1024

    data class Pick(val asset: ReleaseAsset, val flavor: ApkFlavor)

    fun flavorOf(asset: ReleaseAsset): ApkFlavor =
        if (asset.name.contains("-r8-", ignoreCase = true)) ApkFlavor.R8 else ApkFlavor.STANDARD

    fun select(assets: List<ReleaseAsset>, version: String, freeBytes: Long): Pick? {
        val candidates = assets.filter { asset ->
            asset.name.endsWith(".apk", ignoreCase = true) &&
                asset.downloadUrl.startsWith("http", ignoreCase = true) &&
                UpdateVersion.assetMatches(asset.name, version)
        }
        if (candidates.isEmpty()) return null

        val lowStorage = freeBytes >= 0 && freeBytes < LOW_STORAGE_BYTES
        val preferred = if (lowStorage) ApkFlavor.R8 else ApkFlavor.STANDARD
        val backup = if (lowStorage) ApkFlavor.STANDARD else ApkFlavor.R8

        val hit = candidates.firstOrNull { flavorOf(it) == preferred }
            ?: candidates.firstOrNull { flavorOf(it) == backup }
            ?: return null
        return Pick(hit, flavorOf(hit))
    }
}
