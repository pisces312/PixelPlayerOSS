# 曲库歌曲页排序弹窗 — 偶发全选项点击无反应

> 调研日期：2026-09-18
> 状态：**仅调研，未改代码**。结论与修复方案待确认后落地。
> 修订：2026-09-18 14:10 由源码级核对重写 §3 / §4 / §5 / §6（初版的机理与方案 A 均被证否，
> 变更点见 §9 变更记录）。**目前尚未确定根因**，§6 的判据实验是先决条件。

## 1. 现象

在「曲库 → 歌曲」页，点击排序按钮进入排序底部弹窗（`LibrarySortBottomSheet`）。
现象特征：

- 弹窗能正常弹出、选项列表齐全；
- 偶发性地，**所有排序项点击都无反应**（点了不高亮、不排序、不关闭）；
- 绝大多数情况正常，只在某些时机触发；
- 不是「某一个选项死」，而是「整批同时死」，且可恢复（重开弹窗即可）。

## 2. 已排除纯业务逻辑

点击接线本身是通的（`LibraryScreen.kt:1206-1209`）：

```kotlin
onOptionSelected = { option ->
    onSortOptionChanged(option)        // 歌曲 tab → playerViewModel.sortSongs(option)
    playerViewModel.hideSortingSheet() // 无条件复位 flag
}
```

**决定性判据（初版未点明，但这是最强的证据）**：`hideSortingSheet()`（`PlayerViewModel.kt:4233-4235`
→ `_isSortingSheetVisible.value = false`）在这一分支里是**无条件调用**的，
而挂载守卫是 `if (isSortSheetVisible && …)`（`LibraryScreen.kt:1187`）。

> 回调只要执行，弹窗必被卸载。既然现象是「**不关闭**」，
> 即可断定 **`onOptionSelected` 根本没有执行** ⇒ 问题在输入层，不在排序逻辑。

逐层核对（作为佐证）：

| 环节 | 代码位置 | 结论 |
|---|---|---|
| `sortSongs` 是否静默 no-op | `LibraryStateHolder.sortSongs`，内部 `scope?.launch { … }` | `scope` 在 `PlayerViewModel.kt:1740` `libraryStateHolder.initialize(viewModelScope)` 赋值；且 no-op 只发生于 `persist && storageKey 相等` 时（`LibraryStateHolder.kt:370-373`），此时**弹窗仍会关**。排除 |
| `SortOption` 是否返回 null | `SortOption.resolveForDirection()`（`SortOption.kt:471-476`） | 返回类型非空，任意 `methodKey` 都有 `?: methodOption()` 兜底。排除 |
| 单个选项失效 | 各 `sort*` 的 `storageKey` 相等提前 return | 只会让「当前已选中的那一项」无效，**不会**整批失效。排除 |
| 数据来源是否为空 | `sanitizedSortOptions` 由 `availableSortOptions` 派生（`LibraryScreen.kt:956`） | 弹窗在 `sanitizedSortOptions.isNotEmpty()` 下才挂载。排除 |

## 3. 机理：初版假说被证否，现存三种候选（需日志分流）

### 3.1 初版假说「`AnchoredDraggable` 在 settle 期吞掉子项 tap」不成立

- Compose 指针派发是一条**内→外**的命中路径：DOWN 先交给子节点（`selectable`），
  父级拖拽检测器只有在超过 touch slop 后才消费；一次 tap 达不到 slop。
- `AnchoredDraggableState` 的 `MutatorMutex` 只串行化「动画 / 拖拽」两类操作，不阻断指针事件。
- 因此「进场 settle 期间手势层抢走点击」这条机理与框架行为不符。

### 3.2 顺带更正：条件挂载 + 每次新建 `sheetState` 是 M3 的设计行为，不是缺陷

`LibrarySortBottomSheet.kt:80` 在**组件内部**声明
`val sheetState = rememberModalSheetState(skipPartiallyExpanded = true)`，
配合调用点的条件挂载 ⇒ 每次打开新建一个初始 `Hidden` 的 state，再由组件自己首帧 `show()`。
这是 Material3 模态 sheet 的标准用法，其进场动画属正常行为。

### 3.3 M3 源码事实（**常驻挂载方案在框架层不可行**）

核对对象：本项目锁定版本 `material3 = 1.5.0-alpha25`（`gradle/libs.versions.toml:33`）；
证据来自本机 Gradle 缓存 AAR 的反汇编 + androidx 源码。

| 事实 | 证据 | 后果 |
|---|---|---|
| `ModalBottomSheet` **组合即自动 `show()`** | `ModalBottomSheetKt$ModalBottomSheet$3$1.invokeSuspend` 体只有一句 `SheetState.show(Continuation)`；外层门禁为 `if (SheetState.getHasExpandedState())`（`ModalBottomSheetKt` 中同时可见 `getHasExpandedState` 与 `LaunchedEffect` 调用）。该版本 AAR 中检索 `isModalBottomSheetStateRestorationFixEnabled` 命中 **0 次** ⇒ 新版那个「恢复修复」开关尚不存在，此路径更无条件 | 只要被组合，弹窗立刻自己弹开，与外部布尔无关 |
| 组合即存在**模态 Dialog 窗口** | `ModalBottomSheet` 体内 `ModalBottomSheetDialog(...)` 为**无条件**调用 | 常驻 = 屏幕上永远有一个模态窗口，并接管返回键（`shouldDismissOnBackPress`） |
| `Scrim` 的点击拦截**与 alpha 无关** | M3 `Scrim.kt`：只要 `onClick != null` 就挂 `Modifier.fillMaxSize().pointerInput { detectTapGestures { onClick() } }`；隐藏态只是 `alpha = 0`，参与命中的仍是同一层 | 常驻 = 全屏透明可点层，底层列表不可点 |

⇒ 结论：**「常驻挂载 + 用布尔控制显隐」在本版本框架上不成立**（初版方案 A 即因此被否决，见 §5.1）。
注意区分：这否定的是**常驻挂载**，不是「进场动画期间存在时序竞争」这件事本身。

### 3.4 与现象一致的候选机理

| 编号 | 机理 | 预测的可观测结果 | 状态 |
|---|---|---|---|
| **M1** | 命中路径在 DOWN 时即固定：DOWN 落在 sheet 的**非可点区域**（拖拽把手 / 标题 / 选项间隙 / 尚未到位的空白），随后内容随进场动画上移，UP 仍交付给同一节点 ⇒ 无反应、弹窗不关。触发集中在打开后约 0.3–0.5 s 窗口，与「偶发」「重开即好」吻合 | `selectable.onClick` 与 `onOptionSelected` 日志**全无**；弹窗保持打开 | 待验证（最可疑） |
| **M2** | DOWN 落在 `Scrim`（内容尚未到位，而 Scrim 覆盖全屏）⇒ UP 也在 Scrim ⇒ `animateToDismiss` ⇒ `sheetState.hide()` + `onDismissRequest()` | **弹窗会自己关闭** | 与「不关闭」矛盾 ⇒ 基本排除；若实测偶见「点一下就自己关了」，则归此类 |
| **M3** | 回调其实进了，但被 `persist && storageKey 相等` 提前 return（`LibraryStateHolder.kt:371`）⇒ 弹窗会关、列表不重排 | 弹窗关闭、顺序不变 | 与「不关闭」矛盾 ⇒ 基本排除，日志可直接证实 |

## 4. 更正：切 tab 的排序状态滞后（初版 §4 前提不成立）

初版称「`sanitizedSortOptions` 与 `onSortOptionChanged` 路由用**本地** `currentTabId`（立即变），
而 `availableSortOptions` 来自 VM（异步派发）⇒ 存在一帧错位窗口」。**该前提与代码不符**：

- `LibraryScreen.kt:352`：`val currentTabId by playerViewModel.currentLibraryTabId.collectAsStateWithLifecycle()`
  —— 全文件**唯一**声明，没有第二个「本地索引」参与排序；
- `PlayerViewModel.kt:988-1009`：`availableSortOptions = currentLibraryTabId.map { … }.stateIn(…)`。

两者**同源**（同一个 `_currentLibraryTabId`）⇒ 弹窗显示的选项与点击后的路由**永远自洽**，
不存在初版描述的错位。

真实存在的是另一件事（`LibraryScreen.kt:712`）：

```kotlin
LaunchedEffect(currentTabIndex) { playerViewModel.onLibraryTabSelected(currentTabIndex) }
```

VM 侧 tab 在**同一帧的 effect 阶段**才更新（晚于 composition）。窗口期内
action row、排序按钮、弹窗选项、路由**全部仍是旧 tab** ⇒ 表现是
「**排序弹窗正常关闭，但当前这一页列表没有重排**」（因为排的是上一个 tab）。

这与 §1 的现象（不关闭）不同，是**独立问题**，不应与本次修复绑定（见 §5.4）。

## 5. 修复方案

### 5.1 方案 A（初版首选）—— **否决**

原文「不再用 `if (isSortSheetVisible)` 条件挂载，改为始终挂载 + `LaunchedEffect` 调 `show()/hide()`」。
按 §3.3 的源码事实，照此实现会得到：

1. `ModalBottomSheet` 挂载即自动 `show()`：进入曲库瞬间弹窗自己出现；
2. 外部 `LaunchedEffect(isSortSheetVisible) { hide() }` 与 M3 内部 effect 同帧竞争，
   而 M3 的 effect 在组件内部、注册在后 ⇒ `show()` 胜出（显隐彻底失控）；
3. 即使侥幸压住：留下全屏透明可点层 + 模态窗口 ⇒ 底层列表不可点、返回键被吃。

另：原文示例把 `rememberModalSheetState`（仓库自有，`SheetStates.kt`，基于 M3 `rememberBottomSheetState`）
写成了已废弃的 `rememberModalBottomSheetState`，且丢掉了 `isNotEmpty()` 守卫 —— 后者会导致
`LibraryScreen.kt:1195` 的 `sanitizedSortOptions.first()` 在空列表抛 `NoSuchElementException`。

### 5.2 方案 A'（修正版，**待确认**）：保留条件挂载，只补退场动画

```kotlin
// 调用点：sheetState 提升到调用点，与 isVisible 一起决定是否挂载
val sheetState = rememberModalSheetState(skipPartiallyExpanded = true)

if (isSortSheetVisible || sheetState.isVisible) {
    LibrarySortBottomSheet(
        sheetState = sheetState,                      // 组件新增参数
        onRequestHide = { playerViewModel.hideSortingSheet() },
        options = sanitizedSortOptions,               // 仍需容忍空列表
        selectedOption = selectedOptionForSheet,
        onOptionSelected = { option ->
            scope.launch { sheetState.hide() }.invokeOnCompletion { onRequestHide() }
        },
        ...
    )
}
```

组件内改 `ModalBottomSheet(sheetState = sheetState, onDismissRequest = onRequestHide)`，
不再自建 state。收益：退场动画化（避免动画中途 unmount），state 生命周期跨开合稳定。

落地约束（缺一不可）：

- **空列表守卫**：`if (… || sheetState.isVisible)` 意味着退场动画期间组件仍组合，
  此间 `sanitizedSortOptions` 可能为空 ⇒ `LibraryScreen.kt:1195` 的 `first()` 必须改 `firstOrNull()` 兜底；
- **三处调用点同改**：`LibraryScreen.kt:1201`、`PlaylistDetailScreen.kt:1177`、`YearDetailScreen.kt:220`。
  其中前两处共用**同一个全局 flag** `playerViewModel.isSortingSheetVisible`
  （`PlaylistDetailScreen.kt:1150-1152`），第三处是屏幕本地布尔，改组件签名前需先决定统一策略；
- **不能宣称它修掉 §1 的偶发无反应**：A' 只改变「退场是否动画化」。
  若 §6 判据指向 M1（进场动画期间的误触），A' 无效 —— 因此 A' 的落地顺序必须在实验之后。

### 5.3 方案 B（初版次选）—— **对本现象无效**

B 的做法是「先排序、延迟一帧再隐藏」。但 §2 已证回调未执行；
回调不执行时延迟隐藏改变不了任何行为。B 已无存在意义。

### 5.4 方案 C（初版列为必做）—— 降级为独立问题

初版要「统一排序选项来源」，但两者已同源（§4），没有可统一的对象。
若确实要处理切 tab 的产品问题，正解是消除 §4 的 VM 侧滞后（让 action row / 排序按钮
与 pager 使用同一时刻的 tab），与本 bug 无因果关系，**不应与本次修复绑定**。

## 6. 先做判据实验（改动前的唯一必要动作）

在写任何修复代码前，用日志确定归属；成本约十几分钟。

1. 埋点：`LibrarySortBottomSheet.kt:188` 的 `selectable.onClick` 首行、以及
   `LibraryScreen.kt:1206` 的 `onOptionSelected` 首行各加一条 `Timber.d`。
2. 复现配方：进入「曲库 → 歌曲」→ 点排序 → **在弹窗完全停住之前**点第一项，
   连续 20 次，记录每次的两条日志与「弹窗最终是否关闭」。
3. 判据：

| `selectable.onClick` | `onOptionSelected` | 弹窗关闭 | 归属 |
|---|---|---|---|
| 无 | 无 | 否 | §3.4 **M1**（命中路径固定 / 进场动画期误触） |
| 无 | 无 | 是 | §3.4 **M2**（DOWN / UP 都落在 Scrim） |
| 有 | 有 | 是 | **M3** 或纯业务层，回到 §2 逐层排查 |

- 必须用 **debug 包**：整条链路是 `d`/`v` 级，release 的 `ReleaseTree` 只放行 WARN+。
- 若 20 次复现不出来，先加大密度（连点、不同起手位置），不要直接进入方案选择。

## 7. 修复后验证（视 §6 结论决定是否执行）

1. §6 配方 20 次不再出现「无反应且不关闭」；
2. 空列表路径：切到某 tab 后立即开合弹窗，logcat 无 `NoSuchElementException`（对应 5.2 的守卫）；
3. 若采用 A'，回归三条关闭路径：下滑关闭、点 scrim 关闭、返回键关闭 —— 三条都必须
   走到 `hide()` 且动画结束后才 unmount；
4. 其余两个调用点（歌单详情、年份详情）同样手测一遍；
5. UI 交互问题，只需真机 / 模拟器复现，不需要 instrumented 测试。

## 8. 待确认

- 是否按 §6 先做日志实验（**建议**），拿到归属后再定方案；
- 若要做 A'：是否接受三处调用点同改 + 组件新增 `sheetState` / `onRequestHide` 参数？
- §4 的 VM 侧滞后是否单独立项。

## 9. 变更记录（相对 2026-09-18 14:00 初版）

| 章节 | 初版 | 修订后 |
|---|---|---|
| §2 | 逐层排除业务逻辑 | 保留，并补上「`hideSortingSheet()` 无条件调用 ⇒ 回调未执行」这一决定性判据 |
| §3 | 「主因（高度可疑）：`AnchoredDraggable` 吞 tap」 | 该假说被证否；新增 M3 源码事实（1.5.0-alpha25）与三种候选机理（M1 / M2 / M3）+ 判据 |
| §3 | 「每次重建 sheetState、进场 settle 加剧边界态」列为缺陷 | 更正为 M3 的设计行为 |
| §4 | 本地 vs VM 两个 tab 来源、一帧错位 | 更正：两者同源，前提不成立；真实问题是 `LaunchedEffect(currentTabIndex)` 造成的 VM 侧滞后 |
| §5 A | 首选、治本 | **否决**（框架层不可行，会引入更严重回归） |
| §5 B | 次选、治标 | **无效**（回调未执行，延迟隐藏无意义） |
| §5 C | 必做 | 降级为独立问题，不与本修复绑定 |
| §5 A' | — | 新增：保留条件挂载 + 退场动画化（待确认） |
| §6 | 手工复现观察 | 新增二分判据实验（埋点位置 + 复现配方 + 归属表） |
