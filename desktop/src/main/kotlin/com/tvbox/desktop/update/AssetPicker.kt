package com.tvbox.desktop.update

/**
 * 桌面安装包选择（纯函数）。
 *
 * 规则（按 Captain t36 补充口径）：
 *  - 在 latest release 的全部 assets 里找 `XingmuTV-<x.y.z>.(msi|exe)`，从文件名解析资产自己的版本；
 *  - 取资产版本最高的一个，**不是用 tag 版本**（tag 属于安卓线 v0.3.4，桌面是 0.2.0）；
 *  - 同版本同时有 msi/exe：Windows 选 msi，其它平台选 exe（缺失时回退另一种）；
 *  - 忽略 .apk / .zip / `*.sha256` 等非安装包；版本必须是数字边界，`XingmuTV-0.2.0.exe.sha256` 不匹配；
 *  - 没有可用安装包 → null（调用方按 tag 版本决定只给 Release 页面还是 Idle）。
 */
object AssetPicker {

    private val ASSET_PATTERN = Regex("^XingmuTV-(\\d+(?:\\.\\d+)*)\\.(msi|exe)$", RegexOption.IGNORE_CASE)

    fun isWindowsPlatform(osName: String = System.getProperty("os.name").orEmpty()): Boolean =
        osName.lowercase().contains("win")

    /** 解析 `XingmuTV-0.2.0.msi`；严格匹配，避免 `.msi.sha256` 或 `0.2.00` 误判。 */
    fun parse(asset: ReleaseAsset): DesktopAsset? {
        val match = ASSET_PATTERN.matchEntire(asset.name.trim()) ?: return null
        if (!asset.downloadUrl.startsWith("http://", true) && !asset.downloadUrl.startsWith("https://", true)) return null
        val version = match.groupValues[1]
        val kind = if (match.groupValues[2].equals("msi", ignoreCase = true)) AssetKind.MSI else AssetKind.EXE
        return DesktopAsset(asset, version, kind)
    }

    /** 所有合法桌面资产里版本最高的那个；同版本按平台优先级选择。 */
    fun best(assets: List<ReleaseAsset>, windows: Boolean): DesktopAsset? {
        val parsed = assets.mapNotNull(::parse)
        if (parsed.isEmpty()) return null
        var highest = parsed.first()
        parsed.drop(1).forEach { candidate ->
            if (DesktopVersion.compare(candidate.version, highest.version) > 0) highest = candidate
        }
        val sameVersion = parsed.filter { DesktopVersion.compare(it.version, highest.version) == 0 }
        return if (windows) {
            sameVersion.firstOrNull { it.kind == AssetKind.MSI } ?: sameVersion.firstOrNull { it.kind == AssetKind.EXE }
        } else {
            sameVersion.firstOrNull { it.kind == AssetKind.EXE } ?: sameVersion.firstOrNull { it.kind == AssetKind.MSI }
        }
    }
}
