package com.tvbox.desktop.update

import java.nio.file.Path

/** GitHub Release 资产（只保留更新器需要的字段）。 */
data class ReleaseAsset(
    val name: String,
    val sizeBytes: Long,
    val downloadUrl: String,
)

data class ReleaseInfo(
    val tag: String,
    /** tag 的核心版本（仅用于展示/Release 页面，不参与桌面端新旧判定）。 */
    val version: String,
    val notes: String,
    val pageUrl: String,
    val prerelease: Boolean,
    val assets: List<ReleaseAsset>,
)

enum class AssetKind { MSI, EXE }

/** 桌面安装包资产：版本号从文件名 `XingmuTV-<x.y.z>.(msi|exe)` 解析。 */
data class DesktopAsset(
    val asset: ReleaseAsset,
    val version: String,
    val kind: AssetKind,
)

/**
 * 可更新候选。
 *
 * [desktopAsset] == null 表示 Release 里没有桌面安装包，只能打开 Release 页面；
 * 此时 [displayVersion] 用 tag 版本（仅展示）。
 */
data class UpdateCandidate(
    val release: ReleaseInfo,
    val desktopAsset: DesktopAsset?,
) {
    val asset: ReleaseAsset? get() = desktopAsset?.asset
    val assetVersion: String? get() = desktopAsset?.version
    val displayVersion: String get() = desktopAsset?.version ?: release.version
    val pageOnly: Boolean get() = desktopAsset == null
}

/** 检查结果（内部；UI 只读 [UpdateState]）。 */
sealed interface UpdateCheckResult {
    data class UpToDate(val checkedAt: Long) : UpdateCheckResult
    data class Available(val candidate: UpdateCandidate) : UpdateCheckResult
    data class Failed(val reason: String) : UpdateCheckResult
    data class Skipped(val reason: String) : UpdateCheckResult
}

/** UI 状态机。检查异常时只回 [Idle]，不产生弹窗。 */
sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class UpToDate(val checkedAt: Long) : UpdateState
    data class Available(val candidate: UpdateCandidate, val currentVersion: String) : UpdateState
    data class Downloading(
        val candidate: UpdateCandidate,
        val bytes: Long,
        val total: Long,
    ) : UpdateState {
        val percent: Int
            get() = if (total > 0) ((bytes * 100) / total).toInt().coerceIn(0, 100) else 0
    }

    data class Ready(val file: Path, val candidate: UpdateCandidate? = null) : UpdateState
    data class Failed(val message: String) : UpdateState
}

/** 成功路径事件；失败路径不产生事件（单测据此断言静默）。 */
sealed interface UpdateUiEvent {
    data class NewVersion(val candidate: UpdateCandidate, val currentVersion: String) : UpdateUiEvent
    data class UpToDate(val checkedAt: Long) : UpdateUiEvent
}
