# 不期而遇：默认生成 15 首 + 弹出 sheet 布局裁切修复方案

> 状态：方案已审阅确认（2026-09-14），两个入口**共享默认 15 首**，输入阶段滚动加固同批实现。
> 涉及代码：`presentation/components/AiMixSheet.kt`、`presentation/screens/HomeScreen.kt`、
> `presentation/viewmodel/PlaylistViewModel.kt`、`presentation/components/SheetStates.kt`（只读参考）。

## 1. 需求

1. 点击首页「不期而遇！」打开生成面板后，**默认生成歌曲数为 15**（当前默认 25）；
   审阅结论：普通「描述生成」入口**一并改为默认 15**，两个入口共享。
2. 面板弹出后**只露出一半**、生成按钮被截在下方需要手动上拉，优化为打开即可见。
3. 输入阶段增加纵向滚动兜底（大字体 / 小屏 / 软键盘场景）。

## 2. 现状与根因（源码证据）

### 2.1 默认数量：两个入口共用一个初始值

- `AiMixSheet.kt`：长度候选 chip 为 `private val AI_MIX_LENGTHS = listOf(15, 25, 40)`——
  **15 本来就在候选首位**，chip 选中态不需要额外处理。
- 选中长度的本地状态初始值取自 ViewModel 常量：
  `mutableIntStateOf(PlaylistViewModel.DEFAULT_AI_MIX_LENGTH)`。
- `PlaylistViewModel.kt`：原 `const val DEFAULT_AI_MIX_LENGTH = 25`。
- 该 sheet 被两个入口共用（`HomeScreen.kt`，由 `aiEntryIsSerendipity` 区分）：
  普通「描述生成」与「不期而遇」。
- 生成时 sheet 总是**显式**把 `maxLength` 传给 `onGenerate`，最终调用
  `generateSerendipityPreview(prompt, maxLength)` / `generateAiPlaylistPreview(prompt, maxLength)`。
  全仓库只有 HomeScreen 这一个调用点。
- 每次关闭面板时 `showAiMixSheet = false`，`ModalBottomSheet` 与 `AiMixSheet` 整体离开
  组合，`rememberSaveable` 状态随之丢弃；下次打开是全新初始化，
  **用户上一次手选的长度不会跨次残留**，初始值每次打开都会重新生效。

### 2.2 面板只显示一半：sheet 锚定在半展开态

- `HomeScreen.kt`：原 `val aiMixSheetState = rememberModalSheetState()`。
- `SheetStates.kt`：`rememberModalSheetState` 默认 `skipPartiallyExpanded = false`，
  即 `enabledValues = setOf(Hidden, PartiallyExpanded, Expanded)`。Material3 `ModalBottomSheet`
  在该配置下弹出时锚定到 **PartiallyExpanded（约半屏高）**，不会自动到 Expanded。
- 不期而遇的输入阶段（`AiMixSheet.InputPhase`）纵向内容为：

  ```
  标题行
  SerendipitySignals：信号 label + 信号 chips 横滚行 + 换一换/换个说法 按钮行
  OutlinedTextField（serendipity 时 minLines = 3）
  长度 chips 行（15/25/40）
  [生成] 按钮  ←─ 总高超过半屏锚点，被裁在面板外
  ```

  因此必须手动上拉到 Expanded 才能看到生成按钮。普通「描述生成」入口内容也不矮，
  只是恰好在临界附近。
- **项目已有既定模式**：仓库内 19 处内容型 sheet 都显式使用
  `rememberModalSheetState(skipPartiallyExpanded = true)`（如 `SongInfoBottomSheet.kt`、
  `StreamingProviderSheet.kt`、`PlaylistBottomSheet.kt`、`CustomPresetsSheet.kt` 等）。

## 3. 最终实现（已按审阅结论落地）

### 3.1 默认 15 首：两个入口共享

直接把共享常量改为 15（候选列表首位即 15，chip 选中态自动正确）：

```kotlin
/**
 * How many tracks a generated mix asks for when the user has not picked a length.
 *
 * Shared by the describe flow and Serendipity; it is the first chip in AI_MIX_LENGTHS.
 */
const val DEFAULT_AI_MIX_LENGTH = 15
```

两个 generate 方法签名上的默认参数同名引用该常量，一并变为 15，无需另改；
`AiMixSheet` 不需要新增参数。

### 3.2 面板打开即全展开

```kotlin
// Content (signals, multi-line prompt, length chips, generate button) is taller than the
// half-screen anchor, so expand straight away instead of clipping the action button.
val aiMixSheetState = rememberModalSheetState(skipPartiallyExpanded = true)
```

- 弹出直接锚定 Expanded，生成按钮立即可见，两个入口行为一致；
- `Hidden` 仍在 `enabledValues` 中，**下滑/遮罩点击关闭不受影响**，只是去掉半屏停靠点；
- 与项目内其余内容型 sheet 的处理方式一致。

### 3.3 输入阶段可滚动（兜底）

给 `InputPhase` 的根 `Column` 加 `Modifier.verticalScroll(rememberScrollState())`：

- 只包 **InputPhase 内部**：其内部只有横向 `horizontalScroll`（灵感/信号 chips），
  不存在垂直 Lazy 列表，无嵌套滚动冲突；
- **没有**包到 `AiMixSheet` 最外层 Column——`ResultPhase` 内含 `LazyColumn`，
  垂直滚动容器互嵌会触发无限高约束崩溃；
- 不压缩 `minLines = 3`：不期而遇的 prompt 是完整语境句子，3 行保证可读性；
- 弹出键盘时内容也能滚到生成按钮，顺带改善 IME 体验。

### 3.4 不采用的做法

- **打开后用 `LaunchedEffect` 调 `sheetState.expand()`**：会出现「先停半屏、再弹到全屏」
  的两段动画；且保留半屏锚点后用户随手下滑又会回到裁切态。
- **`skipPartiallyExpanded = aiEntryIsSerendipity` 动态传值**：`aiMixSheetState` 在
  HomeScreen 顶层 remember、不随面板开关重建，`enabledValues` 只按首次组合时的值固定，
  动态值不会再生效。
- **只压缩间距 / 把输入框降到 2 行**：只能缓解不能根治，更小屏幕上依然会被裁。

## 4. 改动清单

| 文件 | 改动 |
|---|---|
| `presentation/viewmodel/PlaylistViewModel.kt` | `DEFAULT_AI_MIX_LENGTH` 由 25 改为 15，补注释说明两入口共享 |
| `presentation/screens/HomeScreen.kt` | `aiMixSheetState` 改 `skipPartiallyExpanded = true`（含注释） |
| `presentation/components/AiMixSheet.kt` | `InputPhase` 根 Column 加 `verticalScroll`，补 `verticalScroll` import 与说明注释 |

- 无字符串变更（**不动** strings，中英文都不用加）；
- 无设置项、无数据库/迁移、无 DI 改动；纯 UI 默认值与布局，无新增单测；
- 按「每功能一个 commit」约定拆成两个英文 commit：
  1. `Default AI mix length to 15`
  2. `Expand AI mix sheet on open and make input phase scrollable`

## 5. 验证计划

构建：`.\gradlew.bat :app:assembleDebug` 通过（UI 类改动按仓库约定装真机验证）。

真机/模拟器逐项核对：

1. 点「不期而遇！」：面板直接全展开，**无需上拉即可看到生成按钮**；长度 chip 默认选中 **15**；
2. 点普通「描述生成」：面板同样直接全展开；长度 chip 默认也选中 **15**；
3. 两个入口内当场切换 25/40 后生成，结果数量与所选一致；
4. 关闭面板重新打开：两个入口都回到默认 15（初始值每次重算）；
5. 不期而遇的「换一换」「换个说法」、生成中、结果页（播放/仅保存/再生成）流程正常；
6. 输入阶段弹出键盘后可滚动到生成按钮；大字体/小屏下内容可滚动、无裁切；
7. 面板仍可下滑关闭，遮罩点击关闭正常。
