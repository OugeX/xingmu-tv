# 性能优化路线（v0.2.5 起）

基线来自 v0.2.4 真机实测（LeTV X4-50 / Android 6 / MStar 6A938 / 2.87GB / 1080p）：

| 指标 | 当前 | 预算 | 余量 |
| --- | --- | --- | --- |
| 冷启动首屏 | 995ms | ≤1500ms | 达标 |
| 浏览 PSS | 68MB | ≤260MB | 大量余量 |
| 播放 PSS | 74.8MB | ≤340MB | 大量余量 |
| 播放掉帧 | 0.61% | ≤10% | 达标 |
| 详情（缓存命中） | 18ms | ≤300ms | 达标 |
| 详情（冷开） | 2.9–7.8s | ≤2000ms | **不达标** |
| 点播首帧（预热命中） | 749–927ms | ≤2000ms | 达标 |
| 点播首帧（冷启） | 2708ms | ≤3500ms | 达标 |
| 切台（快 CDN） | 584ms | ≤1500ms | 达标 |
| 切台（慢 CDN） | ~2300ms | ≤2500ms | 擦边 |
| APK 体积 | 12.4MB | ≤14MB | 达标但偏大 |

结论：**已经不是"卡不卡"的问题，而是"冷路径"和"包体"还剩空间。** 下面按性价比排序。

## P0 · 立刻能拿到的收益（不改架构）

### 1. 预置热门作品详情（解决 2.9–7.8s 冷开）
- 构建时把首页/热榜 Top 500 的 `ac=detail&ids=` 结果落到 `assets/detail_seed/*.json`（预计 5–8MB，**不进 APK**，作为可选数据包放 Release）。
- 首开命中预置数据 → 秒开；后台静默刷新覆盖。
- 预期：详情冷开 **2.9–7.8s → ≤300ms**（命中率按首页点击分布约 60–80%）。
- 成本：一个抓取脚本 + 打包流程；风险低。

### 2. 切台预连接（解决慢 CDN 擦边）
- 焦点停留时除了预取 playlist，再对目标 host 做 **TLS 预握手**（OkHttp `connectionPool` 预热 + `InetAddress` 解析缓存）。
- 相邻频道（CH+ / CH- 的下一个）也预连接，连切时命中。
- 预期：慢 CDN 切台 **2300ms → 1600–1900ms**；快 CDN 584ms → 400ms 级。
- 成本：小，纯播放层改动。

### 3. R8 + 资源裁剪（包体与启动）
- release 构建开 `isMinifyEnabled` + `isShrinkResources`，保留 Media3/Coil 的 keep 规则。
- 预期：APK **12.4MB → 8.5–9.5MB**；冷启动 **995ms → 850ms 左右**（类加载减少）。
- 风险：反射/序列化类被裁 → 必须跑一遍四链路冒烟；已用 org.json 无反射序列化，风险可控。

### 4. Baseline Profile（启动提速的"正规军"）
- 用 `androidx.baselineprofile` 生成首页/详情/直播三条路径的 profile，打进 APK。
- 预期：冷启动 **再降 15–25%**（995ms → 750–850ms），首屏掉帧减少。
- 成本：需要一次真机采集（`adb shell cmd package compile -m speed-profile`），中等。

## P1 · 体验层优化

### 5. 图片渐进加载
- 列表先用 `vod_pic_thumb`（或服务端缩略图参数）出图，命中后再换高清；滚动中 `Precision.INEXACT`。
- 预期：列表首屏视觉完成时间 **-30%**，弱网下不再一片灰。

### 6. 列表渲染收口
- `LazyVerticalGrid` 统一 `key` + `contentType`；行内 `PosterCard` 用 `@Immutable` 数据类；避免 `items.size` 变化导致整表重组。
- 预期：滚动 jank **0.61% → ≤0.3%**。

### 7. 播放态资源让位
- 进入播放：清 Coil 内存缓存到 8MB、暂停所有列表请求、释放详情页大图；退出恢复。
- 预期：播放 PSS **74.8MB → 65MB 左右**，给解码器更多余量（老机器更稳）。

## P2 · 结构性优化（收益大但成本高）

### 8. 详情/列表统一内存缓存 + 预取队列
- 现在文件缓存 + 8 条内存缓存；改成"首页可见 20 条详情预取"，滑动即命中。

### 9. 解码器会话复用
- 同一分辨率/编码连续切换时复用 `MediaCodec` 会话，避免每次切台重新 init（实测 init 55–68ms，20 次切台省 1s+）。

### 10. 播放器双实例预热（仅高内存设备）
- ≥3GB 设备允许"预开第二个 player 只 prepare 不渲染"，切台真正 0 等待；1GB 设备禁用（单硬解会炸）。

## 不做清单（避免过度设计）

- 不做动态码率自适应（源多为单码率媒体列表，master 多档极少）。
- 不做播放页转场动画。
- 不做 1GB 设备上的双播放器。

## 验收方式

每项都要给出**同一冻结包**的前后对比：

```bash
# 启动
adb shell am force-stop com.tvbox.app && adb shell am start -W -n com.tvbox.app/.MainActivity
# 详情冷开
adb logcat -c && adb shell am start -n com.tvbox.app/.MainActivity --es screen detail --es vodId 169023 --es sourceId bfzy1
adb logcat -d | grep DetailPerf
# 切台
pwsh tools/measure_player.ps1 -Zaps 20
# 包体/内存
pwsh tools/smoke_check.ps1 ; pwsh tools/measure_pss.ps1
```
