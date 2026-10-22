package com.xingmu.tv.update

import java.io.File

/** GitHub Release 里的一个资产（只保留更新器需要的字段）。 */
data class ReleaseAsset(
    val name: String,
    val sizeBytes: Long,
    val downloadUrl: String,
)

/** 解析后的 Release（UPDATE_SPEC §1/§2）。 */
data class ReleaseInfo(
    val tag: String,
    val version: String,
    val notes: String,
    val pageUrl: String,
    val prerelease: Boolean,
    val assets: List<ReleaseAsset>,
)

/** 资产风格：标准包 / R8 精简包（UPDATE_SPEC §2）。 */
enum class ApkFlavor { STANDARD, R8 }

/** 选中的更新包；asset 为 null 表示只能给 Release 页面 URL。 */
data class UpdateCandidate(
    val release: ReleaseInfo,
    val asset: ReleaseAsset?,
    val flavor: ApkFlavor?,
) {
    val pageOnly: Boolean get() = asset == null
}

/** 检查结果（内部用，不直接驱动 UI；UI 只看 [UpdateState]）。 */
sealed interface UpdateCheckResult {
    data class UpToDate(val checkedAt: Long) : UpdateCheckResult
    data class Available(val candidate: UpdateCandidate) : UpdateCheckResult
    data class Failed(val reason: String) : UpdateCheckResult
    data class Skipped(val reason: String) : UpdateCheckResult
}

/** UI 状态机：只有 [Available] 及之后的状态会让界面出现弹窗。 */
sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class UpToDate(val checkedAt: Long) : UpdateState
    data class Available(val candidate: UpdateCandidate, val currentVersion: String) : UpdateState
    data class Downloading(val candidate: UpdateCandidate, val bytes: Long, val total: Long) : UpdateState {
        val percent: Int
            get() = if (total > 0) ((bytes * 100) / total).toInt().coerceIn(0, 100) else 0
    }

    data class ReadyToInstall(
        val candidate: UpdateCandidate,
        val file: File,
        val needPermission: Boolean,
    ) : UpdateState

    data class Failed(val message: String) : UpdateState
}

/** 成功路径的副作用事件（单测用它断言"失败时没有任何 UI 事件"）。 */
sealed interface UpdateUiEvent {
    data class NewVersion(val candidate: UpdateCandidate, val currentVersion: String) : UpdateUiEvent
    data class UpToDate(val checkedAt: Long) : UpdateUiEvent
}
