package com.xingmu.tv.data

import java.io.File

/**
 * 测试产物定位：单测的 cwd 是 app/ 模块目录，而样例/预置包在仓库的 assets/、release/ 下。
 * 从 cwd 向上找，不写死绝对路径（换机器/CI 也能跑）。
 */
object TestFiles {

    private fun locate(relative: String): File {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, relative)
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        error("找不到测试产物 $relative（cwd=${File(".").absoluteFile}）")
    }

    /** t26 生成的 20 条样例（仓库根 assets/，故意不打进 APK）。 */
    val seedSample: File get() = locate("assets/detail_seed.sample.json")

    /** 完整预置包（release/ 下，对应 Release v0.2.4 资产）。 */
    val seedZip: File get() = locate("release/detail-seed-v1.zip")
}
