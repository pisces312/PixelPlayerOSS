# 不期而遇恢复「高级」采样选项方案（定稿）

> 状态：已实现（独立 serendipity 采样偏好，默认 RANDOM/200，高级区两入口均显示），
> assembleDebug + testDebugUnitTest 通过，待真机走查。
> 涉及代码：`data/preferences/AiPreferencesRepository.kt`、`data/ai/AiPlaylistGenerator.kt`、
> `presentation/viewmodel/PlaylistViewModel.kt`、`presentation/screens/HomeScreen.kt`、
> `presentation/components/AiMixSheet.kt`。零新增字符串、不涉及设置页与数据库 migration。

## 1. 需求

短按「不期而遇！」打开的面板里恢复「高级」折叠区（采样模式 / 采样数量两个下拉），
让不期而遇也能调整送给模型的曲库切片。

## 2. 定稿决策（已与维护者确认）

1. 不期而遇**显示**高级选项，采样模式可在「随机 / 最常播放」之间选择
   （沿用现有 `AiLibrarySampleMode` 的 `RANDOM` / `MOST_PLAYED` 两个枚举，
   **不新增**"最近播放"之类的第三种模式；`MOST_PLAYED` 即按播放次数排序的常听曲目）。
2. 不期而遇的**默认采样模式为随机（RANDOM）**。
3. 不期而遇的**默认采样数量为 200 首**（取代原硬编码的 100）。
4. 不期而遇的采样偏好与普通描述生成的全局采样偏好（默认 最常播放 / 300）**相互独立、
   互不影响**：在不期而遇里改成"最常播放"不会改变普通入口，反之亦然。

### 为什么默认随机、默认 200 首（维护者给出的原因，留档）

- 不期而遇的用途就是**听到不常听的歌**；如果默认挑"最常播放"，来来回回都是熟歌，
  听多了会腻，违背"不期而遇"的初衷，所以默认必须是**随机**，让冷门类目也有机会被选中。
- 送给模型的上下文默认取 **200 首**：比原来的 100 首覆盖更广（随机时更多歌有机会进入候选），
  又不至于像 300/500 那样增加过多 token 与延迟，是覆盖度与成本之间的折中。
- 普通描述生成是"我明确想要某类歌"，默认从最常播放里挑命中率更高；两个入口目标不同，
  因此默认值不同、且各自独立保存。

## 3. 现状与根因（源码证据）

### 3.1 UI 被刻意隐藏，不是回归

`AiMixSheet.InputPhase`（约 L288-305）：

```kotlin
// Sampling is forced on the Serendipity path, so offering the controls there would lie.
if (serendipity == null) {
    TextButton(…) { Text(ai_mix_advanced) }
    if (advancedExpanded) { SampleModeDropdown(…); SampleSizeDropdown(…) }
}
```

`git log -S` 确认这段从 Serendipity 首个提交 `6e4a944b` 起就存在：因为数据层把不期而遇的
采样硬编码死，显示控件却不生效等于欺骗用户。灵感 chips 区块同样按 `serendipity == null`
隐藏，**与本次无关，保持不动**。

### 3.2 两条路径采样来源不同

`AiPlaylistGenerator`：

- 普通 `generate()`：`sampleMode/sampleSize` 为 null 时读全局偏好
  （`getLibrarySampleMode/Size`，默认 MOST_PLAYED / 300，数量可选 50/100/200/300/500）；
- `generateSerendipity()`：硬编码 `sampleMode = RANDOM`、
  `sampleSize = SERENDIPITY_SAMPLE_SIZE(100)`，只保留专属 `promptType` 与 `useCache = false`。

高级下拉绑定的是偏好（`HomeScreen` 的 `SampleConfig`，onChange → repository setter）。
采样设置在 AI 设置页没有别的入口，sheet 的「高级」是唯一调整处；测试目录没有用例锁定
`generateSerendipity` 的 RANDOM/100 行为。

## 4. 最终设计：不期而遇独立的、可覆盖且持久化的采样偏好

为不期而遇新增**一对独立偏好**，默认 RANDOM / 200；UI 高级区恢复显示并绑定这对偏好；
数据层生成时读这对偏好。普通入口仍用原全局偏好。两条链路完全对称、互不污染。

**为什么持久化而不是"每次打开重置"**：高级下拉在普通入口本就是持久化设置，用户心智是
"我选的会被记住"；长按快速生成不打开面板，也需要读到用户保存的选择。因此新增 DataStore key
持久保存（默认值即随机/200），用户改过就记住，不采用会话级临时状态。

## 5. 改动清单

| # | 文件 | 改动 |
|---|---|---|
| 1 | `data/preferences/AiPreferencesRepository.kt` | companion 加 `DEFAULT_SERENDIPITY_SAMPLE_SIZE = 200`；`Keys` 加 `SERENDIPITY_SAMPLE_MODE`(string)、`SERENDIPITY_SAMPLE_SIZE`(int)；新增 `getSerendipitySampleMode()`（缺省 RANDOM）/`setSerendipitySampleMode()`、`getSerendipitySampleSize()`（缺省 200）/`setSerendipitySampleSize()`；把两个新 key 加入 `allAiPreferenceKeyNames()`（属可备份的用户选择，与 `LIBRARY_SAMPLE_*` 同类；注意 `SERENDIPITY_STEP_*` 是设备计数，仍不进备份） |
| 2 | `data/ai/AiPlaylistGenerator.kt` | `generateSerendipity()` 改为 `sampleMode = preferences.getSerendipitySampleMode().first()`、`sampleSize = preferences.getSerendipitySampleSize().first()`，保留专属 `promptType`、`useCache=false`；删除 companion 中不再使用的 `SERENDIPITY_SAMPLE_SIZE = 100`；更新 `generate()` 与 `generateSerendipity()` 的 KDoc（不再"强制 RANDOM/100"，而是读自身默认 RANDOM/200 的可覆盖偏好） |
| 3 | `presentation/viewmodel/PlaylistViewModel.kt` | 仿照 `aiLibrarySampleMode/Size`，新增 `aiSerendipitySampleMode`（stateIn 初始 `RANDOM`）/`setAiSerendipitySampleMode()`、`aiSerendipitySampleSize`（初始 200）/`setAiSerendipitySampleSize()` |
| 4 | `presentation/screens/HomeScreen.kt` | 新增两个 flow 的 `collectAsState`；构造 `SampleConfig` 时按 `aiEntryIsSerendipity` 分叉——不期而遇绑定新的 serendipity mode/size/setter，普通入口仍绑定 library 那套；`modes = AiLibrarySampleMode.entries`、`sizes = LIBRARY_SAMPLE_SIZE_OPTIONS` 不变（200 已在选项内） |
| 5 | `presentation/components/AiMixSheet.kt` | InputPhase 高级区去掉外层 `if (serendipity == null)` 包裹并改写注释，两个入口都显示；下拉绑定的 `sampleConfig` 已由 HomeScreen 按入口注入，组件本身不改；灵感 chips 的隐藏条件不动 |

- **字符串**：零新增，复用 `ai_mix_advanced`、`ai_playlist_sample_mode_*`、
  `ai_playlist_sample_size_*`（中英文现成）。
- **设置搜索 / Room / DI**：不涉及（不是设置页开关、无实体变更、repository 已注入）。
- **长按快速生成**：`quickGenerateSerendipity()` → `generateSerendipity()` 内部即读 serendipity
  偏好，自动遵循用户保存值（默认随机/200），无需额外改动。

一个英文 commit：
`Give Serendipity its own sampling settings (random / 200 by default) and show the advanced controls`。

## 6. 两入口最终行为对照

| | 普通描述生成 | 不期而遇（短按/长按） |
|---|---|---|
| 高级区是否显示 | 显示 | **恢复显示** |
| 默认采样模式 | 最常播放 (MOST_PLAYED) | **随机 (RANDOM)** |
| 默认采样数量 | 300 | **200** |
| 可选数量 | 50/100/200/300/500 | 同左 |
| 偏好存储 | `ai_library_sample_*` | `ai_serendipity_sample_*`（独立） |
| 其他特性 | 走响应缓存 | 专属 promptType、每次换措辞、不走缓存（不变） |

## 7. 验证计划

`.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest`；真机走查：

1. 短按不期而遇：能看到「高级」，展开默认显示 **随机 / 200**；可切到最常播放、改数量并生成，
   结果符合所选（随机时两次生成的候选不同，最常播放时上下文稳定）；
2. 普通描述入口高级默认仍是 **最常播放 / 300**，且两入口各自改动互不相干、关闭重开后各自记住；
3. 长按快速生成遵循不期而遇保存的采样设置（默认随机/200）；
4. 不期而遇信号组句、换一换/换个说法、结果页保存/播放流程正常；
5. 高级展开后面板可滚动、生成按钮不被裁切（沿用全展开 + verticalScroll）；
6. 备份/导出 AI 设置包含两个新 key（如方便验证）。
