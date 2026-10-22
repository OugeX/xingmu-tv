# 直播分类展示实现说明（t30）

> 对应规格：`docs/LIVE_CATEGORY.md`。范围：只改 `live/`、`ui/live/`（外加一份文档）。
> 状态：**代码 + 本地 `assembleDebug` + `lintDebug(0 Error)` 通过；截图与真机数字 PENDING_DEVICE（LeTV 关机、Konka 无 ADB）。**

## 1. 改了哪些文件

| 文件 | 作用 |
| --- | --- |
| `ui/live/LiveCategoryPanel.kt`（新增） | 整个频道面板：分组 chips、分页签、两列网格、搜台过滤态、底部提示、右侧信息卡。 |
| `ui/live/LiveCategory.kt`（新增） | 纯函数层：`buckets()` 分组与计数、`pageCount/pageOf/pageLabel` 分页、`filterByNumber()` 台号过滤、`tagOf()` 右上角标签。 |
| `live/LiveChannelPrefs.kt`（新增） | 收藏 / 最近（SharedPreferences + StateFlow；最近保留 10 条，按首帧成功时间倒序）。 |
| `live/ChannelSearch.kt`（新增） | 搜台匹配：中文名包含、首拼（hnws）、全拼（hunan，支持多音字逐字消费）。复用 `assets/pinyin_initials.json`、`pinyin_full.json`。 |
| `live/LiveController.kt` | 暴露 `channelPrefs`；`toggleFavorite()`；首帧成功时 `touchRecent()`。 |
| `live/LiveKeys.kt` | 数字键超时 1.5s → **3s**（规格 §3）。 |
| `ui/live/LiveScreen.kt` | 用分类面板替换旧单列浮层；渲染 `UnsafeChannelConfirmDialog`（此前只有组件、没有渲染）；按键路由按「面板打开/关闭」分流。 |

## 2. 结构

```
频道  266 台 · 4 组        台号 5   [显示全部] [搜台]
[高清专区 114] [央视 24] [卫视 17] [地方 122] [收藏 0] [最近 0]
[1-60] [61-120] [121-122]                    ← 仅 >60 台时出现
┌ 000 CCTV-1 综合      576p ┐ ┌ 001 CCTV-2 财经   576p ┐
│ … 两列，1080p 下一屏约 14 格 │
数字键直达 · 长按 OK 收藏 · MENU 搜台 · LEFT/BACK 收起
```

- 分组来源：`state.groups`（`live_seed.json` 的 `group_id`：cctv / satellite / local，按 `order` 排），`hd` 来自 `live_hd.json`；「收藏」「最近」是虚拟组，始终显示（空组给提示文案）。
- **计数口径**：`state.channels` 已经是 `LiveController.visibleChannels()` 过滤后的列表（安全模式只留 `has_safe=true`），所以 Tab 上的数字天然与「点进去看到的台数」一致。
  用 seed 数据静态核对：全部 266（cctv 28 / satellite 26 / local 212），安全模式 163（cctv 24 / satellite 17 / local 122），高清专区 114 → 地方组分 3 页。
- 网格列数：大字号档 1 列，其余 2 列；C 模式（LITE）格子更矮（54dp vs 58dp）。

## 3. 行为

| 项 | 实现 |
| --- | --- |
| 切分组不播放 | chips 只改面板内 `bucketId`，焦点留在 chip 上；只有频道格获得焦点才走 `live.preview()`（800ms 防抖不变）。 |
| 焦点边界 | UP 在首行 → 焦点回当前分组 chip；DOWN 在末行 → 吞掉；LEFT 在行首 / RIGHT 在行尾 → 吞掉（不会串到右侧信息卡或桌面）。 |
| 数字直达 | 根节点收数字（1–3 位）→ `state.numberEntry` → 面板过滤台号前缀并自动聚焦第一台；`OK` 确认播放；3s 无输入清空并提示。 |
| 搜台 | 点「搜台」或面板内按 MENU 进入过滤态：分组 chips 置灰、顶部出现输入框、结果实时过滤；BACK 退出过滤态回分组视图。 |
| 收藏 | 频道格长按 OK（≥600ms）加入/取消收藏，格子显示 ★，顶部 toast 式提示；持久化在 `tvbox_live_channels`。 |
| 最近 | 首帧成功后写入，最多 10 条、时间倒序；播放失败/只是焦点划过的台不写入。 |
| 可能黑屏 | 「显示全部」时 `safe=false` 的台右侧显示红色「可能黑屏」，播放前弹 `UnsafeChannelConfirmDialog` 二次确认。 |
| MENU 语义 | 面板关闭：MENU = 切换「显示全部」并打开面板（保留旧语义）；面板打开：MENU = 搜台。 |

## 4. 埋点（真机可直接量）

- `TVBox/LIVE_CAT`：`group=<id> switch_ms=<ms> channels=<n> pages=<n>` —— 验收「切分组 ≤100ms」。
- 面板本身不碰播放器；切分组不会触发 `playChannel()`，因此 `PLAYER_EVT action=zap` 不应因切分组而增加（可用于验证「不重建播放器」）。

## 5. 验证方法（设备可用后）

```powershell
pwsh tools/measure_player.ps1 -Zaps 10      # 直播起播/切台回归
adb logcat -s TVBox/LIVE_CAT TVBox/PLAYER_EVT
adb exec-out screencap -p > screenshots/t30_categories.png
```

人工检查清单：6 组 Tab 计数（安全模式 24/17/122）、地方组三页、输入 `5` → CCTV-5、长按 OK 收藏后「收藏」组出现该台、最近 10 台倒序、切分组时 `PLAYER_EVT` 不新增。

## 6. 已知限制

1. 当前 seed 只有 cctv / satellite / local 三组，规格里的「港澳台」「其他」不会凭空出现——分组是数据驱动的，源里出现 `hk_mo_tw` / `other` 才会显示（不造假 Tab）。
2. 搜台输入依赖遥控器软键盘/外接键盘；纯遥控器场景主要用数字直达（已按规格实现）。
3. 「一屏 ≥12 台」按 1080p + 默认字号推算约 14 格（58dp 格子），需真机截图确认。
