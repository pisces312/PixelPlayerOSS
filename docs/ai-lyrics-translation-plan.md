# AI 歌词翻译（阶段 A+C）

> 状态：已实现并构建通过（2026-10-28）。阶段 B（逐行双语叠加）未实施，见文末。

## 1. 目标与范围

在既有「当前歌曲 AI 问答」基础上增加 **翻译歌名和歌词** 能力，两个入口：

- **A：内置问题** —— AI 问答 sheet 的「可以这样问」多了「翻译歌名和歌词」，点击即发问并自动附带歌词。
- **C：歌词页入口** —— 歌词页「更多」面板新增「AI 翻译歌词」，关闭面板后直接打开 AI sheet 并自动发问。

产出是聊天里的一段文本回答（歌名一行 + 逐行「原文 → 译文」），**不是**逐行歌词叠加；后者是阶段 B。

## 2. 关键行为

| 场景 | 行为 |
|---|---|
| 点击翻译入口 | 先等本地歌词读完再发问，避免「问了翻译但没带歌词」 |
| 命中缓存 | 直接把上次问答灌进对话，不发请求 |
| 未配置 AI | sheet 打开但显示「请先配置 AI」卡片，不发问 |
| 无本地歌词 | 入口（C）不显示；内置问题（A）仍可点，只翻歌名 |
| 目标语言 | 跟随界面语言（`Locale.getDefault().toLanguageTag()`），写进 system prompt |
| 清空 AI 缓存 | 设置页既有「清空缓存」同时删除译文缓存目录 |

## 3. 缓存设计

- 位置：`filesDir/ai-lyrics/<hash>.json`（应用私有，未卸载不丢，其它 App 不可读）
- 键：`sha256(版本 + songId + 语言 tag + provider/model + 歌名 + 歌手 + 歌词全文)` 前 12 位
  - 歌词重新导入、换模型、换目标语言都会自然 miss，不需要主动失效
  - 只存译本回答，不额外复制原文
- 上限：200 个文件，按 `lastModified` 最旧先删
- 不做 Room 表：译文是可重建的派生数据，不值得一次 schema 迁移

## 4. 改动文件

| 文件 | 改动 |
|---|---|
| `data/ai/AiLyricsTranslationCache.kt` | 新增：缓存读写/清理/淘汰、`keyFor` |
| `data/ai/SongQaContextBuilder.kt` | system prompt 增加翻译输出规则（歌名一行、逐行对照、不评论、已是目标语言则说明） |
| `presentation/viewmodel/AiSongQaViewModel.kt` | `openSession(song, preset)`；歌词读完再跑 preset；翻译走缓存；失败重试保留缓存键 |
| `presentation/viewmodel/AiSongQaUiState.kt` | 未改 |
| `presentation/components/AiSongQaSheet.kt` | 建议项改为数据类（文本 + 是否附带歌词），新增翻译项；`preset` 入参 |
| `presentation/components/subcomps/LyricsMoreBottomSheet.kt` | 「歌词」分组新增「AI 翻译歌词」 |
| `presentation/components/LyricsSheet.kt` | 透传 `onAskAiTranslate` |
| `presentation/components/player/FullPlayerContent.kt` | 透传 `onAskAiTranslateLyrics` |
| `presentation/components/UnifiedPlayerSheetLayers.kt` | 两层（播放器 / 预热层）透传；预热层传空实现 |
| `presentation/components/UnifiedPlayerSheetV2.kt` | `aiQaPreset` 状态与入口接线 |
| `presentation/viewmodel/AiSettingsViewModel.kt` | 清缓存同时清文件缓存 |
| `di/AppModule.kt` | 提供 `AiLyricsTranslationCache`（`filesDir/ai-lyrics`） |
| `res/values*/strings_ai.xml`、`strings_components.xml` | `ai_song_qa_suggest_translate`、`lyrics_more_ai_translate`（EN + zh-rCN） |
| `app/src/test/.../AiLyricsTranslationCacheTest.kt` | 新增：往返、失效、淘汰、清空 4 例 |

## 5. 验证

- `:app:compileReleaseKotlin` 通过
- `:app:testDebugUnitTest --tests "data.ai.*"` 通过
- `:app:assembleRelease` 通过，产物 `pixelplayeross-arm64-v8a-0.4.3-pisces.1-release.apk`（36.1 MB，本机未设 `KEY_*`，因此未签名）
- `aapt2 dump resources` 确认两条新字符串均已进入 APK（含 zh-rCN）

## 6. 未做 / 下一步（阶段 B）

- 译文只在聊天里，不与播放同步、不逐行高亮。
- B 的落地要点（已论证）：`LyricsSheet` 内 `remember(lyrics, aiState)` 做纯函数合并，输出 `Lyrics` 副本给现有渲染与「显示翻译」开关，**不写回** `StablePlayerState`，避免切歌/歌词重载竞态；模型返回 `{"t":[...]}` 行数组，解析失败回退按行切分。
