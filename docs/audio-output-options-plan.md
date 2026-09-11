# 音频输出方案（面向"最好的播放效果"）——双正交选项版

> 状态：**方案待确认**（未实现）
> 更新：2026-09-10（v2：采纳双正交选项结构，取代 v1 的平级档位）
> 分支：`pisces/port`（`pisces312/PixelPlayerOSS`，GPL-3.0-or-later fork）
> 背景：`docs/direct-audio-output.md` 已分析 DIRECT 直通原理、验证方法与 china-only 32 位浮点对比
> 结论：**主入口 = 两个正交选项（采样率策略 × 输出精度），"档位"降级为预设快捷**，详见 §3，待确认后实施

---

## 1. 目标与原则

让用户在"最好的播放效果"上有**清晰、可预期、可自由组合**的选项。判断效果好坏涉及四个维度：

| 维度 | 好坏判定 | 现状 |
| --- | --- | --- |
| **采样率保真** | 不被系统重采样（44.1/96/192 kHz 原样直达 DAC）最好 | 只有 DIRECT 有机会，且无上限保护 |
| **位深/编码** | 整数 PCM 原样 vs 32 位浮点（处理余量） | PCM_FLOAT 可开 float |
| **应用层处理** | 越少越好（上限保护、降混都是妥协） | 默认档有 192 kHz 封顶 + Dolby 降混 |
| **功耗/兼容** | offload 省电但受 HAL 限制，部分设备有卡顿/gapless 问题 | 仅 SYSTEM_DEFAULT 允许 offload |

**核心矛盾**：采样率保真（直通）与 32 位浮点在**系统层互斥**——float 编码几乎无法命中硬件 direct profile，直通必失效（`direct-audio-output.md` §7）。因此"最好效果"不是全开，而是**让用户按场景组合一个最优配置**。

---

## 2. 可选方案对比

- **方案 A（最小改动）**：保持三选一，只调文案/默认值。缺点：缺"安全直通"选项，无法表达"直通但保留 192 kHz 保护"。
- **方案 B（双正交）**：两个独立开关自由组合。缺点：对普通用户太技术化，需配合法性约束。
- **方案 C（平级档位）**：3~4 个语义档位。缺点：档位间差异藏在背后，无法自由组合。

**结论：采用 B 为主结构、C 的档位作为预设快捷**——两者不冲突，正交选项是"引擎"，档位是"一键配方"。

---

## 3. 推荐结构：双正交选项 + 预设快捷

### 3.1 两个正交选项

**选项 1 · 采样率策略 `SampleRateStrategy`**

| 值 | 含义 | 采样率处理 |
| --- | --- | --- |
| `SYSTEM` 系统默认 | 交给系统 | 可能被重采样到 48 kHz |
| `SAFE_DIRECT` 高解析直通 | 保采样率 + 留保护 | **≤192 kHz 原样**，>192k 降采样到 192k |
| `FULL_DIRECT` 极致直通（实验） | 全速率原样 | 含 352.8/384 kHz，无保护 |

**选项 2 · 输出精度 `OutputPrecision`**

| 值 | 含义 |
| --- | --- |
| `INTEGER` 整数 PCM | 跟随源文件位深（最高 24-bit） |
| `FLOAT` 32 位浮点 | 处理余量优先 |

### 3.2 组合与合法性（3×2 = 6，合法 4）

| 采样率策略 \ 精度 | 整数 PCM | 32 位浮点 |
| --- | --- | --- |
| `SYSTEM` 系统默认 | ✅ 智能默认（offload 可开） | ✅ 32 位浮点 |
| `SAFE_DIRECT` 高解析直通 | ✅ 高解析直通 | ❌ 非法（float 无法命中硬件 direct profile） |
| `FULL_DIRECT` 极致直通 | ✅ 极致直通 | ❌ 非法（同上） |

- 非法组合在 UI **禁用**并给出解释（一句话：浮点编码在系统层无法直通，选择浮点后采样率只能交给系统）。
- 配置映射（推导规则，落在引擎一处）：
  - 直通类（SAFE/FULL_DIRECT）→ 关闭 offload、独立 audio session id；
  - `SYSTEM` → offload 按设备可用性自动（带卡死 fallback）、共享 session；
  - `FLOAT` → `setEnableFloatOutput(true)`；直通类恒为整数输出。

### 3.3 音质梯度排序（由弱到强）

排序依据：**采样率保真 > 输出精度**。被系统重采样到 48 kHz 是最大音质损失源；float 在 mixer 路径下只提供处理余量（最终仍转整数输出），是次要改善。

| 排序 | 组合（采样率策略 × 精度） | 采样率处理 | 精度 | offload | 音质特征 | 适用 / 风险 |
| --- | --- | --- | --- | --- | --- | --- |
| 1（最弱） | 系统默认 × 整数 PCM | 交系统，可能重采样到 48 kHz | 16/24-bit 整数 | 可开 | 兼容性最佳，最可能被重采样 | 日常 / 蓝牙 / 在线流 |
| 2 | 系统默认 × 32 位浮点 | 交系统，可能重采样 | 32-bit float | 关 | 处理余量，减少中间精度损失，但仍可能重采样 | 设备不支持直通、追求 DSP 余量 |
| 3 | 高解析直通 × 整数 PCM | ≤192 kHz 原样，>192k 降 192k | 16/24-bit 整数 | 关 | 高解析文件保采样率，设备支持时无重采样 | 本地高解析 FLAC + 有线 / Hi-Fi 设备 |
| 4（最强） | 极致直通 × 整数 PCM | 全速率原样（含 352.8/384 kHz） | 16/24-bit 整数 | 关 | 无任何应用处理，采样率完全原样 | 发烧友 + 确认设备支持；**有卡死风险（实验）** |

> 注意：这是**潜在音质上限**排序，实际音质取决于设备 HAL 是否授予 direct 线程、文件采样率和输出设备。对普通 44.1/48 kHz 文件，直通类未必能拿到 direct 线程（设备主输出多为 48 kHz），此时与系统默认差异缩小。FULL_DIRECT 最强 ≠ 最推荐。

### 3.4 预设快捷（"档位"按钮，一键应用常见组合）

| 预设 | 组合 |
| --- | --- |
| 智能默认（推荐） | `SYSTEM` + `INTEGER` |
| 高解析直通 | `SAFE_DIRECT` + `INTEGER` |
| 极致直通（实验） | `FULL_DIRECT` + `INTEGER` |
| 32 位浮点 | `SYSTEM` + `FLOAT` |

预设只是把两个选项一次性设置好，**不新增状态**；用户随后仍可单独改任一选项。

### 3.5 选择建议（写入设置页说明）

- 不知道选什么 / 蓝牙 / 在线流 → 智能默认
- 本地高解析 FLAC（96/192 kHz）+ 有线 / Hi-Fi 设备 → 高解析直通
- 发烧友 + 确认设备支持原生采样率（USB DAC / Hi-Fi 机型）→ 极致直通（用 dumpsys 验证）
- 追求处理余量、设备不支持直通 → 32 位浮点

---

## 4. 概念说明

### 4.1 什么是 offload（Audio Offload）

把"解码 + 播放"整体交给设备音频 DSP（HAL 硬件）：App 不产出 PCM，直接交**压缩码流**（MP3/AAC/FLAC…），由硬件解码并直出，跳过应用处理器链与 mixer 重采样。

- **好处**：息屏播放显著省电；卸载 CPU；部分设备 DSP 直出 DAC。
- **限制**：格式/采样率受 HAL 支持范围限制（不支持自动回退普通路径）；应用侧音效/处理器全部失效；部分设备有卡顿或 gapless 接缝 bug（项目已有 `AudioOffloadStallFallback`：检测到 HAL 卡住即禁用 offload 并重建 player）；音量走硬件。
- **与方案关系**：offload 仅在 `SYSTEM` + `INTEGER`（智能默认）下允许；直通/浮点组合强制关闭（语义冲突）。

> **注意：offload ≠ MediaCodec 硬件解码器**，两者是不同层面：
> - MediaCodec 解码器（设备能力页"硬件/软件"标签）= **App 进程内**解码压缩码流 → PCM；没有硬件解码器只是改用软件解码（更耗 CPU），照样能播，**与 offload 无关**。
> - Audio Offload = **音频 HAL/DSP 层面**，App 不走 MediaCodec，直接把压缩码流交给 `AudioTrack` 由系统硬件解码直出；能否 offload 由 `AudioManager.isOffloadedPlaybackSupported()`（设备能力页 `getOffloadSupportedFormats()` 已探测，格式行上的 "Offload" 芯片即结果）决定。
> - 结论：只有软件解码器的手机，其音频 HAL 依然可能支持 MP3/AAC/FLAC offload；反之有硬件解码器也不代表支持 offload。

### 4.2 什么是自动按内容选档（远期）

播放时监听 `player.audioFormat`（解码后的采样率/位深/声道），自动选组合：普通 44.1/48k → 智能默认（可 offload）；96/192k FLAC → 高解析直通。

- **难点**：切组合 = 重建 player = 短暂中断；跨曲切换需用 auxiliary player（crossfade 的 B player）预构建目标配置才可无感，复杂度高。
- **低成本替代**：不做自动重建，检测到高解析文件且当前非直通组合时提示"建议切换"，用户一键确认。
- **建议**：本期不做，列入路线图；低成本提示版可作为二期。

---

## 5. 实现要点（确认后动工）

### 5.1 数据层

- 新增两个枚举（`data/model/`）：
  - `SampleRateStrategy`（SYSTEM / SAFE_DIRECT / FULL_DIRECT）
  - `OutputPrecision`（INTEGER / FLOAT）
  - 合法性函数 `isValid(strategy, precision)`（直通 × FLOAT = false）
- 组合状态持久化：沿用 `audio_output_mode_v1` key，值改为组合串（如 `"sys|int"`）；`fromStorageKey` 提供 v1 旧值迁移表：

  | 旧值 | 迁移到 |
  | --- | --- |
  | `system_default` | SYSTEM + INTEGER |
  | `direct` | FULL_DIRECT + INTEGER |
  | `pcm_float` | SYSTEM + FLOAT |

  遗留 `hi_fi_mode_enabled` 迁移逻辑保留。
- 现有 `AudioOutputMode` 枚举**废弃或改造成"预设"视图**（映射到组合），避免双状态源。

### 5.2 引擎层（`DualPlayerEngine.kt`）

- `buildAudioSink`（:1002-1030）按组合分支：
  - `SYSTEM` + INTEGER：现状 SYSTEM_DEFAULT 分支；
  - `SYSTEM` + FLOAT：现状 PCM_FLOAT 分支（含能力检测）；
  - `SAFE_DIRECT` + INTEGER：默认链 + **仅** `HiResSampleRateCapAudioProcessor`（无降混）+ float 关；
  - `FULL_DIRECT` + INTEGER：现状 DIRECT 分支（`super.buildAudioSink`）。
- `shouldEnableAudioOffloadForMode`（:158-163）：仅 `SYSTEM + INTEGER` 允许。
- 共享 session id（:1129-1131）：直通类不共享，其余共享。
- `setAudioOutputMode`（:1204-1217）：PCM_FLOAT 能力回退改为"组合合法性 + 能力"双层校验；变更仍走 `rebuildPlayersPreservingMasterState`。

### 5.3 UI 层

- `SettingsCategoryScreen.kt:974-1009`：单选选择器改为**两个选项行**（采样率策略 3 值 + 精度 2 值），非法组合禁用；下方"预设快捷"一排按钮（仅写组合，不写状态）。
- `SettingsViewModel.kt` / `SettingsRegistry.kt:463` / `strings_settings.xml`（英文基准）：同步更新，注册搜索关键词，不写死中文。

### 5.4 测试与验证

- 单元：组合合法性、v1 迁移、offload 判定、预设→组合映射。
- 真机（每组合）：44.1/96/192/384 kHz 样本，`adb shell dumpsys audio` 核对 track 采样率与线程 flags（方法见 `direct-audio-output.md` §6）。
- `SAFE_DIRECT` 重点验证：384 kHz 不再卡死（cap 生效）且 96/192 kHz 原样。

---

## 6. 风险与权衡

1. 组合从 3 增至 4 个合法态，测试矩阵变大；每个切换都 rebuild player（短暂中断）。
2. `SAFE_DIRECT` 是新组合（cap + 无降混 + 独立 session），需真机验证后再放推荐位。
3. 非法组合的 UI 禁用与解释要足够清晰，避免用户困惑。
4. 自动选档（§4.2）成本高，本期不做；提示版作为可选二期。

---

## 7. 待确认问题（收敛后仅剩 2 个）

- **C（原）offload 默认策略**：智能默认组合下 offload 保持现状"可用则自动开（带卡死回退）"，还是默认关（更可预期的 PCM 路径）？—— 建议保持现状。
- **D（原）自动选档**：本期只做方案 + 提示版（低成本），还是完全不做、仅记录路线图？—— 建议记录路线图，不做本期。

> A（正交拆解）、B（高级自定义=正交开关本身，采纳正交后不再需要）已解决：主结构即两个正交选项，预设即档位。

---

## 8. 参考

- `docs/direct-audio-output.md`：直通原理、三模式现状、验证方法（§6）、与 china-only 32 位浮点对比（§7）。
- Media3 1.10.1 字节码核查结果见 `direct-audio-output.md` §8。
