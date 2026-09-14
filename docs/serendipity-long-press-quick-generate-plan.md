# 不期而遇「长按快速生成」方案

> 状态：已按审阅结论实现（方案 A；保留长按触感；长按区域限「不期而遇！」胶囊），
> assembleDebug + testDebugUnitTest 通过；真机首测触感不生效，已按 3.4.1 修复，待复测。
> 涉及代码：`presentation/components/AiGenerateEntryCard.kt`、
> `presentation/screens/HomeScreen.kt`、`presentation/viewmodel/PlaylistViewModel.kt`。
> 前置改动：默认 15 首、sheet 打开即全展开（已合入 main）。

## 1. 需求

长按首页「不期而遇！」按钮：**自动采集此刻信号 → 自动组 prompt → 用默认设置直接开始生成**，
全程不需要用户再点任何按钮；期间给出明确的等待指示，让用户知道后台已经在跑。
普通短按行为保持不变（打开 sheet、可编辑 prompt、手动点生成）。

## 2. 现状链路（短按，源码证据）

```
ActionPill(onClick)                         AiGenerateEntryCard.kt
  → openAiEntry(serendipity = true)         HomeScreen.kt
       ├─ isAiConfigured=false → 跳 AI 设置页
       └─ serendipityPermissionLauncher.launch(位置/计步权限)   # 已授权时系统立即回调
              回调 → openSerendipitySheet()
                   ├─ aiEntryIsSerendipity = true
                   ├─ playlistViewModel.openSerendipity()      # VM：异步 collect 信号 + 本地组句
                   └─ showAiMixSheet = true                    # 弹 AiMixSheet
用户点「生成」→ onGenerate(prompt, maxLength)
              → generateSerendipityPreview(prompt, maxLength)  # 才真正请求 AI
```

关键事实：

- `PlaylistViewModel.openSerendipity()`（L532-547）：先置 `SerendipityUiState(isCollecting=true)`，
  异步 `serendipityContextCollector.collect()` 完成后填入 `context/prompt` 并 `isCollecting=false`；
  协程发现 `_serendipityState == null`（sheet 已关）会自行中止。
- `generateSerendipityPreview(description, maxLength)`（L490-492）→ 私有 `startPreview()`
  （L494-520）：置 `isGenerating=true`，结果/错误写回同一个 `_aiPlaylistPreviewState`。
- `AiMixSheet` 的三阶段完全由 state 驱动（AiMixSheet.kt L163-201）：
  `isGenerating → GeneratingPhase（大转圈）`、`hasResult → ResultPhase`、否则 InputPhase；
  **UI 不需要知道生成是"人点的"还是"自动触发的"**。
- 等待文案已存在，**无需新增任何字符串**：
  - 信号采集阶段：`ai_serendipity_collecting`（中「正在感知此刻……」/ 英 "Reading the moment…"）
  - AI 生成阶段：`ai_mix_generating`（中「正在挑选歌曲……」/ 英 "Picking songs…"）
- 长按手势项目统一用 `Modifier.combinedClickable(onClick, onLongClick)`
  （LibraryScreen、PlaylistContainer、FullPlayerContent 等多处先例）；
  触感反馈走 `LocalHapticFeedback`（MainActivity 已按设置替换为真实/NoOp 实现，直接用即可）。

## 3. 推荐方案 A：长按即开 sheet，阶段自动推进，sheet 自身就是等待界面

### 3.1 交互时序

```
长按「不期而遇！」
  → 触感反馈（LongPress）
  → 与短按相同的权限通道
  → VM.quickGenerateSerendipity() + 立即打开 sheet
       sheet 内自动演绎：
         ① InputPhase + "正在感知此刻……"（采集信号，通常亚秒~数秒，天气可能走网络）
         ② GeneratingPhase + "正在挑选歌曲……"（AI 生成，大 LoadingIndicator）
         ③ ResultPhase：歌单列表，用户直接播放 / 仅保存 / 移除歌曲 / 再生成
```

用户全程零点击；两段等待都有转圈+文案，进度语义比单一转圈更清楚。

### 3.2 ViewModel：新增 `quickGenerateSerendipity()`

在 `PlaylistViewModel` 新增方法，把「采集 → 组句 → 生成」在 VM 内串起来，
编排逻辑不泄漏到 UI：

```kotlin
/**
 * Long-press shortcut for Serendipity: collect the signals and immediately generate with
 * defaults, skipping the manual confirm step. The same sheet observes the resulting states.
 */
fun quickGenerateSerendipity() {
    _serendipityState.value = SerendipityUiState()
    viewModelScope.launch {
        val context = runCatching { serendipityContextCollector.collect() }.getOrNull()
        // Dismissed while collecting — same guard as openSerendipity().
        if (_serendipityState.value == null) return@launch
        val variant = Random.nextInt(SerendipityPromptComposer.VARIANT_COUNT)
        val prompt = context?.let { SerendipityPromptComposer.compose(it, variant) }.orEmpty()
        _serendipityState.value =
            SerendipityUiState(
                context = context,
                prompt = prompt,
                variant = variant,
                isCollecting = false
            )
        // No signal could be composed: leave the sheet on the manual input phase as a fallback.
        if (prompt.isBlank()) return@launch
        startPreview(prompt) {
            aiPlaylistGenerator.generateSerendipity(prompt, DEFAULT_AI_MIX_LENGTH)
        }
    }
}
```

- 长度直接用 `DEFAULT_AI_MIX_LENGTH`（当前 15，即"默认设置"）；serendipity 路径本就强制
  随机采样，与 sheet 里的采样高级项无关。
- 与 `openSerendipity()` 的采集/组句代码重复约 6 行，实现时抽一个私有
  `suspend fun collectAndCompose(): SerendipityUiState?` 供两者共用（小重构，不改行为）。
- 结果、错误、空结果全部复用 `startPreview` 现有逻辑（错误文案直接显示在结果页顶部）。

### 3.3 HomeScreen：权限通道携带"快速"标记

```kotlin
// 记录本次权限请求来自短按还是长按；回调时按此分流
var pendingQuickSerendipity by remember { mutableStateOf(false) }

val openSerendipitySheet: (quick: Boolean) -> Unit = { quick ->
    aiEntryIsSerendipity = true
    if (quick) playlistViewModel.quickGenerateSerendipity()
    else playlistViewModel.openSerendipity()
    showAiMixSheet = true
}

val serendipityPermissionLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        openSerendipitySheet(pendingQuickSerendipity)   // 已授权时系统立即回调，长短按同通道
    }

val openAiEntry: (Boolean, Boolean) -> Unit = { serendipity, quick ->
    if (isAiConfigured) {
        if (serendipity) {
            pendingQuickSerendipity = quick
            serendipityPermissionLauncher.launch(
                if (serendipityWantsLocation) SerendipityLocationPermissions
                else SerendipityStepPermissions
            )
        } else {
            aiEntryIsSerendipity = false
            showAiMixSheet = true
        }
    } else {
        navController.navigateSafely(Screen.SettingsCategory.createRoute(SettingsCategory.AI.id))
    }
}
```

### 3.4 AiGenerateEntryCard：给「不期而遇！」胶囊加长按

- `AiGenerateEntryCard` 新增 `onSerendipityLongClick: (() -> Unit)? = null`，透传给该
  `ActionPill`；普通描述入口（整张卡片的 Surface）**不加**长按，避免手势冲突。
- `ActionPill` 把 `Surface(onClick=…)` 改为无点击重载 Surface +
  `Modifier.clip(CircleShape).combinedClickable(onClick = onClick, onLongClick = onLongClick)`，
  形状/水波纹/颜色不变。
- 长按区域仅限「不期而遇！」胶囊本身。

#### 3.4.1 长按触感的实现（真机不震的修复，2026-09-14 补）

真机首版触感不生效，反编译 `foundation 1.11.x` 的 `CombinedClickableNode` 后确认两条事实：

1. `combinedClickable` 长按时**框架会自动**经 `LocalHapticFeedback` 触发一次 `LongPress`
   （在 onLongClick 回调之前），不需要、也不应该再手动调第二次；
2. Compose 平台触感实现等价于 `View.performHapticFeedback(LONG_PRESS)`，**不带
   `FLAG_IGNORE_GLOBAL_SETTING`**，系统「设置 → 声音和振动 → 触感反馈」总开关关闭时
   （国产 ROM 常默认关）会被静默丢弃——这正是不震的根因。项目其余触感统一走
   `performAppCompatHapticFeedback`（ViewCompat）正是为绕开它。

最终做法（只作用于该胶囊，不改动全局触感基座）：

```kotlin
CompositionLocalProvider(LocalHapticFeedback provides NoOpHapticFeedback) {
    Surface(Modifier.clip(CircleShape).combinedClickable(
        onClick = { onClick?.invoke() },
        onLongClick = {
            performAppCompatHapticFeedback(
                view, appHapticsConfig,
                HapticFeedbackConstantsCompat.LONG_PRESS,
                HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING   // 只受 app 内触感开关控制
            )
            action()
        }
    )) { content() }
}
```

- 局部 `NoOpHapticFeedback` 吞掉框架自带那次（仅这棵子树），ViewCompat 手动触发可靠的一次，
  因此系统开关关闭也能震、且**不会双震**；
- app 内「触感反馈」开关关闭时 `performAppCompatHapticFeedback` 内部直接返回，仍然不震，符合设置；
- 未改 MainActivity 的全局 `LocalHapticFeedback` 基座，避免牵动全 app 其余 27 处触感行为
  （全局统一到 ViewCompat 可作为后续独立优化，届时需清理 combinedClickable 链上的重复手动触感）。

### 3.5 边界处理

| 场景 | 行为 |
|---|---|
| 未配置 AI provider | 与短按一致，跳 AI 设置页，不启动采集 |
| 权限被拒 | 与短按一致：信号缺天气/步数行，仍继续；不阻断快速生成 |
| collect 异常、prompt 为空（极罕见，时间信号恒在） | 停在 sheet 输入页（等同短按打开），用户可手动生成，不额外报错、不加字符串 |
| 生成失败（key/额度/网络） | 复用 startPreview：sheet 结果页顶部显示现有本地化错误文案，可「再生成」 |
| 采集/生成途中下滑关闭 sheet | 现有 onDismiss 重置两个 state；采集协程有 null 检查中止；已发出的 AI 请求竞态与短按流程完全相同，不新增问题 |
| 重复触发 | sheet 遮罩打开后盖住首页卡片，无法再次点按，无需额外防抖 |
| 生成中用户想改 prompt | ②阶段不可编辑（与短按点了生成之后一样）；结果页可「再生成」，想改文案可关闭后短按进入 |

### 3.6 方案 A 优缺点

- 优点：复用全部既有状态机/结果页/错误处理；**AiMixSheet 零改动、零新字符串**；
  进度分两段可见；实现量小、行为与短按严格一致。
- 代价：长按后仍会弹出 sheet（但自动推进，用户无需操作）——它同时承担"等待界面"和结果页。

## 4. 备选方案 B：原位转圈，生成完才弹结果（不推荐，列出供选择）

长按后不弹 sheet：「不期而遇！」胶囊上显示小 `LoadingIndicator`，VM 后台采集+生成，
**完成后再弹 sheet 直接到结果页**；失败时弹 sheet 显示错误。

- 优点：首页不被打断，"魔法感"更强，等待时仍可滚动浏览首页。
- 缺点与额外成本：
  1. VM 需新增"快速生成中"界面状态驱动按钮转圈，且要区分用户是否已离开 Home、
     sheet 是否已被短按打开等组合，状态矩阵明显更复杂；
  2. 采集阶段（天气网络可达数秒）首页没有进度文案，只有小转圈，反馈反而不如 A 清楚；
  3. 生成期间用户短按卡片、切换 Tab、返回等都要定义行为；
  4. 失败/空 prompt 仍需弹 sheet，存在两条打开路径，测试面更大。

## 5. 改动清单（方案 A）

| 文件 | 改动 |
|---|---|
| `presentation/viewmodel/PlaylistViewModel.kt` | 新增 `quickGenerateSerendipity()`；抽 `collectAndCompose()` 私有函数供 open/quick 复用 |
| `presentation/screens/HomeScreen.kt` | `pendingQuickSerendipity` 标记；`openAiEntry`/`openSerendipitySheet`/权限回调加 quick 分流；卡片处传 `onSerendipityLongClick` |
| `presentation/components/AiGenerateEntryCard.kt` | 新增 `onSerendipityLongClick` 参数；`ActionPill` 支持 `combinedClickable` + 长按触感 |

- 不动 `AiMixSheet.kt`；无 strings 新增（中英文都不加）；无设置项、无 DB/迁移、无 DI；
- 纯编排+手势，无新增单测（现有 SerendipityPromptComposer 等测试不受影响）；
- 一个英文 commit：`Long-press Serendipity to auto-generate with defaults`。

## 6. 验证计划

构建：`.\gradlew.bat :app:assembleDebug`，并跑 `:app:testDebugUnitTest` 确认无回归；
UI 按仓库约定真机验证：

1. 长按「不期而遇！」：有触感；sheet 直接全展开，先「正在感知此刻……」再「正在挑选歌曲……」，
   自动到结果页，全程无手动点击；长度按默认 15；
2. 短按「不期而遇！」：行为与现在完全一致（停在输入页、不自动生成）；
3. 首次使用弹权限：允许与拒绝两条路径下，长按都能继续推进（拒绝时缺信号行但能生成）；
4. 未配置 AI 时长按 → 跳 AI 设置页；
5. 生成失败（如断网/错误 key）：结果页显示错误文案，「再生成」可用；
6. 采集/生成中关闭 sheet：无崩溃、无残留 loading；再次短按/长按正常；
7. 整张卡片的短按（描述生成入口）不受长按手势影响，水波纹与点击正常。

## 7. 审阅结论（2026-09-14）

1. 采用 **方案 A**：长按即弹 sheet，自动推进采集 → 生成 → 结果；
2. 保留长按触感反馈（跟随应用内触感总开关）；
3. 长按区域限定在「不期而遇！」胶囊，不扩展到整张卡片。
