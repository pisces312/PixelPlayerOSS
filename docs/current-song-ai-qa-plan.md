# 当前歌曲 AI 问答方案

> 状态：**已实施（2026-10-27）** —— 实现、构建与验证记录见 §8。
> 目标：在全屏播放器给当前歌曲一个一步可达的 AI 问答入口；默认附带经过最小化的歌曲元数据，同时允许用户移除上下文并把它当作普通 AI 助手使用。

## 1. 入口与承载

### 1.1 入口

在全屏播放器 `FullPlayerContent` 顶部操作区、书签与队列按钮之前增加 `AutoAwesome` 圆形按钮，无障碍文案为“AI 问答”。

选择这里的原因：

- 语义准确：入口只在全屏播放器出现，明确对应“当前正在播放的歌”。
- 操作短：从已展开播放器只需一次点击；从迷你播放器也只需“展开 → AI”两步。
- 不挤压播放控制：迷你播放器、底部随机/循环/收藏/评分区都已满载，不适合再塞入口。
- 布局有空间：目标设备只覆盖主流直板手机竖屏（参照 411 × 914 dp），顶栏容纳第三个 42 dp 操作按钮仍有余量。

不做迷你播放器直接入口、隐藏手势或长按入口。

### 1.2 承载 UI

使用新的 `AiSongQaSheet`，按现有 `AiMixSheet` 的模式做全展开 `ModalBottomSheet`，挂在播放器覆盖层宿主中，而不是新增 Navigation destination。

- Sheet 内是消息列表 + 底部输入框，适合围绕当前歌曲的短会话。
- 不走路由，就不需要把 `Song` 序列化进参数，也不会污染返回栈。
- 新 Sheet 由独立的 `AiSongQaViewModel` 持有业务状态；播放器数据仍从全局唯一的 `PlayerViewModel` 显式传递链读取。
- `FullPlayerContent` 的透明预热层只传空回调，避免不可见副本响应点击。

## 2. 交互流程

```text
全屏播放器顶栏 ✨
  → 全展开 AI 问答 Sheet
  → 顶部歌曲卡：封面、标题、歌手/专辑、“已附带歌曲信息”
  → 快捷问题或自由输入
  → 流式显示回答
  → 可追问、停止、新对话
```

首版快捷问题：

- 介绍这首歌
- 介绍一下歌手
- 这张专辑有什么背景？
- 这首歌属于什么风格？
- 本地已有歌词时显示“歌词表达了什么？”

回答流式追加；发送中按钮变为停止。失败保留草稿与历史，并显示可重试的错误。关闭 Sheet 时取消未完成请求，避免继续消耗 token。

顶部有“新对话”；它清空当前内存会话，但不修改播放状态或歌曲数据。

## 3. 歌曲上下文与切歌规则

### 3.1 默认附带

默认把以下元数据作为系统上下文发送给当前配置的 AI provider：

- 标题；
- 歌手、合作艺术家、专辑艺术家；
- 专辑；
- 流派；
- 年份、发布日期；
- 时长；
- 音轨号、碟片号；
- 音频格式、比特率、采样率。

### 3.2 默认不附带

- 本地文件路径；
- `contentUri`；
- Navidrome / Jellyfin 曲目 ID；
- 封面图片；
- 完整曲库；
- 播放历史；
- 收藏、评分；
- 完整歌词。

歌曲卡提供“附带歌曲信息”开关，默认开启；关闭后就是普通 AI 问答。歌词不默认发送，只在用户点击歌词问题或显式启用歌词上下文时读取**已经保存**的歌词，不触发远程歌词搜索，并做长度截断。

### 3.3 切歌

打开 Sheet 时保存歌曲快照。播放中切歌时：

- 当前会话继续讨论打开时的歌曲，不静默替换上下文；
- 顶部继续显示“正在讨论”的歌曲；
- 用户可点“切换到当前歌曲”，这会开启以新歌曲为上下文的新会话；
- 每次发送请求绑定当时的 `songId`，避免回答被归到错误歌曲。

## 4. AI 请求层

复用现有 AI 设置与网络基础设施：MiMo、Volcano Engine Ark、自定义 OpenAI-compatible endpoint，以及已有的错误分类、SSE 流式响应、用量统计和请求日志。

改动：

1. `OpenAiCompatibleClient` 增加通用 `messages: List<ChatMessage>` 请求能力，保留现有单轮 `systemPrompt + userPrompt` 包装，避免影响 AI 歌单。
2. `AiHandler` 增加无缓存的多轮问答方法，使用 `promptType = "song_qa"`，继续记录 usage 与 request log。问答强制关闭 provider thinking 参数，避免聊天被推理延迟拖慢。
3. 新增 `SongQaContextBuilder`：
   - 构建系统提示词；
   - 过滤敏感字段；
   - 组装多轮历史；
   - 截断歌词与历史，防止超长上下文。

系统提示词必须明确：本地元数据是确定事实，模型自身知识可能过时；用户问题优先，与歌曲无关的问题也要正常回答；不能虚构发行背景、榜单成绩或歌词。

首版不实现 tool calling，不让 AI 直接播放、加队列或修改曲库。

## 5. 文件改动清单

新增：

- `data/ai/SongQaContextBuilder.kt`
- `presentation/viewmodel/AiSongQaViewModel.kt`
- `presentation/components/AiSongQaSheet.kt`
- `app/src/test/.../data/ai/SongQaContextBuilderTest.kt`

修改：

- `data/ai/OpenAiCompatibleClient.kt`：通用多消息请求。
- `data/ai/AiHandler.kt`：多轮流式问答与 `PROMPT_TYPE_SONG_QA`。
- `presentation/components/player/FullPlayerContent.kt`：顶栏入口。
- `presentation/components/UnifiedPlayerSheetLayers.kt`：入口回调向上传递，预热层保持 inert。
- `presentation/components/UnifiedPlayerSheetV2.kt`：Sheet 可见状态与覆盖层宿主接线。
- `presentation/components/UnifiedPlayerOverlaysLayer.kt`：新增 AI 问答层。
- `res/values/strings_ai.xml` 与 `res/values-zh-rCN/strings_ai.xml`。
- `PRIVACY.md`：补充 AI 问答会发送的歌曲元数据与可选歌词。
- `CHANGELOG.md`：补 Unreleased 条目。

## 6. 验收标准

1. 全屏播放器顶栏出现 AI 按钮；迷你播放器与播放控制区不新增控件。
2. 点击后 Sheet 全展开，顶部歌曲卡显示当前歌曲，默认附带歌曲信息。
3. 快捷问题能发送；自由输入可多轮追问；回答流式更新。
4. 关闭歌曲上下文后仍可问普通问题。
5. 本地已有歌词时，歌词问题显式携带歌词；没有歌词时直接提示。
6. 请求可停止；失败保留消息和输入，可重试。
7. 未配置 provider 时显示空态，并能跳转 AI 设置。
8. 切歌不会让旧问题的回答归到新歌曲。
9. `.\gradlew.bat :app:testDebugUnitTest` 通过。
10. `.\gradlew.bat :app:assembleRelease` 通过；按本机环境变量签名时产物可通过 `apksigner verify --print-certs -v` 验证。

## 7. 首版明确不做

- AI 播放控制、加队列、编辑元数据或创建歌单；
- 完整曲库问答；
- 跨重启聊天记录（会话只保留在内存）；
- 联网资料检索与来源引用；
- 上传封面或音频；
- 内置本地 LLM；
- 新增设置开关（功能默认启用，是否可用取决于现有 AI provider 是否已配置）。

## 8. 实施与验证记录（2026-10-27）

已按 §5 落地：

- 顶栏入口在书签、队列之前；预热层回调为 `{}`，不会响应点击。
- `AiSongQaSheet` 的输入草稿存在 `AiSongQaUiState.draft`，关 Sheet 不丢半句话；关闭时取消请求，但同一首歌的内存会话会保留。
- 歌词先查 `LyricsRepository.getStoredLyrics`，再退回 `Song.lyrics` 原始文本；两者都没有才显示“没有可附带的歌词”，不发远程搜索。
- `AiHandler.chat()` 对请求历史做 `[system, user, assistant...]` 约束，`promptType = "song_qa"`；thinking 参数按 provider 能力固定关闭。
- 请求日志继续只写本地 `cacheDir/ai_logs/`，`PRIVACY.md` 已补充数据边界。

验证：

1. `.\gradlew.bat :app:testDebugUnitTest` 通过，新增 `SongQaContextBuilderTest` 覆盖元数据脱敏、上下文移除、歌词显式附带/截断、历史长度限制。
2. `.\gradlew.bat :app:assembleRelease` 通过，产物：`app/build/outputs/apk/release/pixelplayeross-arm64-v8a-0.4.3-pisces.1-release.apk`。
3. `apksigner verify --print-certs -v`：`Verifies`，`v2 scheme: true`，证书 `CN=pisces312`。
4. `aapt2 dump badging`：`versionCode='400301'`、`versionName='0.4.3-pisces.1'`、`native-code: 'arm64-v8a'`。
5. `.\gradlew.bat :app:lintDebug` 仍为仓库既有 4 个 error（Serendipity 定位权限、Home locale、CarLyricTitleController 的 UnstableApi 两处），本次新增文件没有进入 error 列表。

未完成：真机安装与交互验证（顶栏按钮、键盘、切歌固定上下文、停止/重试）待接设备后补。
