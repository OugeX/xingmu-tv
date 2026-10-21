# 源导入与源管理实现说明（t10）

对应规范：`docs/SOURCE_IMPORT_SPEC.md`、`docs/PLAN_SOURCES.md`（tvapp.source.v1.1）、`assets/sources.schema.json`。
本文件只回答“代码落在哪、端口/token/安全限制在哪、怎么验收”。

## 1. 文件与职责

| 文件（`app/src/main/java/com/tvbox/app/`） | 职责 |
| --- | --- |
| `source/SourceSpecParser.kt` | **L0→L2**：字节解码（UTF-8/GB18030/BOM/大小上限）、JSON、schema 等价校验、语义校验（id/端点指纹/自伤/频道/EPG/广告规则） |
| `source/SourceStore.kt` | SQLite 源表（`tvbox_sources.db`，`source`+`meta`），内置源幂等装载、增删改、探测状态回写 |
| `source/SourceRepositoryImpl.kt` | `SourceRepository` 全实现：列表/详情/搜索/换源/频道/启停/优先级/测试；聚合并发 3、单源 3.5s、硬截止 4.5s |
| `source/MacCmsClient.kt` | MacCMS/macjson：`ac=list` 列表、`ac=detail` 详情/搜索、宽松字段解析（只用 ~30 字段） |
| `source/M3uParser.kt` | m3u 子集解析、分组过滤、`channels[]` 覆盖合并 |
| `source/SourceProbe.kt` | **L3**（ac=list 200+code=1 / m3u 含 #EXTM3U）与 **L4**（1 条 detail 且 play_url 非空）；4s 超时不重试 |
| `source/UrlRules.kt`·`AdBlockRules.kt` | URL/指纹/id 纯函数、SSRF 内网判定、广告域自伤检查（与 `validate_sources.py` 等价） |
| `source/SourceGraph.kt` | 进程单例；`SourceGraph.install(context)` 覆盖 `AppServices` 默认源仓库（**t4 需在 App 启动调用**） |
| `import/ImportPipeline.kt` | 管线编排：L0→规范化→L1/L2→落库→L3/L4→报告 |
| `import/ImportNormalizer.kt` | 输入识别：JSON / m3u 文本 / 裸 URL / URL 列表 → 统一 tvapp JSON；URL 自动识别 maccms/m3u/bundle |
| `import/LanImportServer.kt` | 局域网 HTTP（`ServerSocket` 手写），端口/token/配对码/请求限制全在这里 |
| `import/ImportService.kt` | 统一入口（扫码/U 盘/SAF/遥控器），`lanState`/`lastReport` StateFlow |
| `import/ImportInputSpec.kt` | 遥控器兜底输入契约：字符集、域名补全、最近 10 条历史（SharedPreferences） |
| `import/UsbImportScanner.kt`·`SafImport.kt` | U 盘根目录扫描；`ACTION_OPEN_DOCUMENT` 读流（8MB 上限） |
| `import/QrCodes.kt` | zxing-core 生成二维码 Bitmap（420px，无 camera 模块） |
| `app/src/main/assets/import_page.html` | 单文件 H5（无外网依赖），TV 端注入 token 后由 `ImportPage` 返回 |

## 2. 端口 / 生命周期 / token（安全限制落点）

- **端口**：`LanImportServer.DEFAULT_PORT = 8088`，被占用时 `start()` 循环 +1（最多试 20 个），全失败返回 null。
- **绑定**：只绑定一个站点本地 IPv4（`LocalNetwork.lanIpv4()`，优先 wlan/eth，跳过 rmnet/vpn）；无 WiFi 退化为 `127.0.0.1`（手机连不上，UI 应提示）。
- **生命周期**：只在导入页 `ImportService.startLanImport()`..`stopLanImport()` 之间存活；页面退出即 `close()`（关 ServerSocket、线程池、定时器）。成功导入且 `stopAfterSuccess=true` 时 350ms 后自动关闭。
- **token**：`LocalNetwork.token()` 16 字节 SecureRandom → 22 字符；5 分钟过期（`TOKEN_TTL_MS`），**成功导入后立即失效（单次有效）**；失败不消耗、但 5 次失败或 60 次请求即关闭服务。
- **配对码**：`LocalNetwork.pairingCode()` 6 位数字；`POST /api/pair` 校验后返回同一 token。配对码只在已带 token 的页面注入，`/` 页（整个局域网可见）不含配对码。
- **安全边界**：客户端必须是回环/站点本地（`LocalNetwork.isLanClient`）；请求体 ≤2MB+256KB；Content-Type 白名单（`application/json`/`text/plain`/`multipart/form-data`）；只允许 1 个并发导入；imported URL 默认拒绝内网/回环（SSRF，`UrlRules.isPrivateHost`，`allowLan` 显式放行）；日志脱敏 `UrlRules.maskCredentials`。
- **二维码**：内容 `http://<lan-ip>:8088/i/<token>`；`LanImportState.qrBitmap` 由 `QrCodes` 生成，`pairingCode`/`serviceUrl` 同结构暴露。
- **H5 三种提交**：粘贴 JSON（`payload`）、multipart 文件（.json/.m3u/.txt ≤2MB）、单填 URL（自动识别），全部落到 `ImportPipeline`。

## 3. 校验错误码一致性

`source/SourceIssueCodes.kt` 的 16 个 `E_*` 与 `tools/validate_sources.py` / PLAN_SOURCES §4.3 一一对应：
`E_JSON/E_SIZE/E_ENCODING/E_SCHEMA/E_KIND/E_ID/E_NAME/E_URL/E_FIELD/E_REGEX/E_LIMIT/E_NESTED/E_SIGNATURE/E_ADBLOCK/E_CHANNEL/E_EPG`。
schema 层的类型/范围问题统一映射为文档表里的具体码（如缺 baseUrl→`E_URL`、adBlock 非对象→`E_ADBLOCK`、频道问题→`E_CHANNEL`），而不是笼统 `E_SCHEMA`。
警告：`W_HTTP/W_UNVERIFIED/W_DUPLICATE_ENDPOINT/W_URL_NORMALIZED/W_CREDENTIALS/W_SIGNATURE_UNVERIFIED`。

已跑（本机，Python 3 + jsonschema）：
```
python tools/validate_sources.py assets/sources.builtin.json   # ✓ 7 源通过
python tools/validate_sources.py assets/sources.example.json   # ✓ 4 源通过
python tools/validate_sources.py --selftest                    # ✓ 旧 9/9 + 新 8/8 拦截，正例 3/3
```
Kotlin 侧等价自检（不依赖 JUnit，已在真机 LeTV/X4-50（Android 6.0/API23）跑过）：
```
adb push app-debug.apk /data/local/tmp/tvbox-check.apk
adb shell CLASSPATH=/data/local/tmp/tvbox-check.apk app_process /system/bin com.xingmu.tv.import.ImportSelfCheck /data/local/tmp/sources.builtin.json
adb shell CLASSPATH=/data/local/tmp/tvbox-check.apk app_process /system/bin com.xingmu.tv.import.LanImportSelfCheck 18088
```
`ImportSelfCheck`（55 项）：17 个负例 + 3 个正例 + 指纹/域名规则/补全/m3u/剧集解析/真实 builtin 文件（7 源 0 拒绝）。
`LanImportSelfCheck`（21 项，真开 ServerSocket 发 HTTP）：H5/token 页、错误 token 410、配对码 403/成功、multipart 与 JSON 导入、token 单次有效、成功后自动关闭、413/415、close 后拒连；`lan` 参数用真实局域网 IP 绑定。

## 4. 内置源与幂等

`SourceStore.seedBuiltin()` 读 `assets/sources.builtin.json`，按 `bundle.version` 记 `meta.builtin.version`；已装过直接跳过，用户删除的 id 记 `removed.<id>` 不复活。导入按 `id` 更新、按 `(kind, 端点指纹)` 复用旧 id（`W_DUPLICATE_ENDPOINT`），重复导入同一文件源数量不变。
`bundleId` 只写 DB 列、不写 `config_json`（用户导入带 `bundleId` 仍按未知字段 `E_FIELD` 拒绝）；老版本写入的 `bundleId` 由 `SourceStore.selfHealConfigs()` 在装载时清除。

## 5. t4 接线（两行）

```kotlin
// App.onCreate()
com.xingmu.tv.source.SourceGraph.install(this)   // 覆盖 AppServices 默认源仓库

// 导入页 onStart/onDispose
val svc = com.xingmu.tv.import.ImportService.get(context)
svc.startLanImport()   // LanImportState(qrBitmap/serviceUrl/pairingCode/expiresAt)
svc.stopLanImport()    // 必须调用：关闭 8088
// U 盘：svc.scanUsb() → svc.importUsbFile(file)
// SAF：launcher.launch(SafImport.openDocumentIntent()) → svc.importFromUri(uri)
// 遥控器：ImportInputSpec.KEY_ROWS/completions()/normalize() + svc.importUrl(url)
```
多源聚合详情换源：`SourceRepositoryImpl.findAlternateSources`；源管理页用 `listAllSources()`/`listSourceStates()`（含停用与 probe 状态）。

## 6. 导入 UI（t17 接线完成）

- `ui/source/SourceManageScreen.kt`：底部「添加播放源」五个入口 = 二维码 / 配对码 / 本地文件 / U 盘 / 遥控器键盘；点击进入全屏子页 `ImportPanelScreen`（不是弹窗，D-pad 焦点更稳）。
- `ui/source/ImportPanel.kt`：
  - **二维码**：`ImportService.startLanImport()` → 二维码 Bitmap + `http://<lan-ip>:<port>` + 6 位配对码 + `mm:ss` 倒计时；`lastReport` 成功后 `onImported()` 刷新列表并显示「已导入 N 个源（M 个频道）」。
  - **配对码**：大字显示 + 6 位输入核对（`verifyPairingCode`，错误码 `E_PAIR`/`E_PAIR_EXPIRED`）+ 手机配对状态（`isLanPaired`）。
  - **本地文件**：优先 `ACTION_OPEN_DOCUMENT`（API 19+，多候选 Intent 逐个尝试）；实测 LeTV X4-50 只有不带 `CATEGORY_OPENABLE` 的 `com.stv.filemanager`，带 OPENABLE 会抛 `ActivityNotFoundException`，所以 `SafImport.resolveLaunchableIntents()` 依次尝试 OPEN_DOCUMENT(±OPENABLE)/GET_CONTENT，失败再回退到 `LocalFileScanner`（App 外部目录 `Android/data/com.xingmu.tv/files/import/` 与下载目录，无需存储权限）。
  - **U 盘**：`scanUsb()` → 「发现 N 个源文件，导入？」→ 单个/全部导入。
  - **键盘**：`ImportInputSpec` 分组键盘（数字/字母/符号）+ 候选 + 历史一键填入；聚焦输入框时外接键盘/ADB 可直接打字（`utf16CodePoint`），ENTER 提交。
- 生命周期：进入面板启服务；离开 `DisposableEffect` 关服务；App 退到后台 `ON_STOP` 也立即关（`LanServiceLifecycleEffect`），回前台重新生成 token。
- 调试/截图入口（QA 用）：`am start -n com.xingmu.tv/.MainActivity --es screen sources --es importPanel qr|pair|file|usb|keyboard`。
- 真机验收截图：`design/mockups/import_qr.png`（二维码）、`import_qr_result.png`（手机导入成功）、`import_qr_list.png`/`import_list_final.png`（列表刷新）、`import_pair.png`（配对码）、`import_keyboard.png`（键盘导入成功）、`import_file.png`/`import_file_list.png`（本地文件）、`import_usb.png`（无 U 盘实物时的空态）、`import_sources_clean.png`（清理后 7 内置源）。

