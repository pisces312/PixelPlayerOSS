# 播放子系统评审（设计 / 性能 / 内存）

> 日期：2026-09（随 Serendipity 交互改造后的播放链路评审）
> 范围：`DualPlayerEngine` / `FadingPlayer` / `MappingPlayer` / `LyricTitlePlayer` / `CloudPlaybackUriResolver`、
> `MusicService`、`PlaybackStateHolder` / `QueueStateHolder`、`PlayerViewModel` 播放路径、外部试听链路。
> 结论按优先级给出；具体行号以评审当时候选源码为准。

---

## 1. 架构

### 1.1 分层

```
PlayerViewModel / Compose UI
        │  MediaController
        ▼
MusicService · MediaSession
        │
        ▼
LyricTitlePlayer          ← 最外层：车机 AVRCP 标题/歌词元数据合成与 timeline 闸门
        │
        ▼
MappingPlayer             ← 封面 URI 映射（外部控制器可见的 artwork）
        │
        ▼
FadingPlayer              ← play/pause 约 500ms 音量 ramp
        │
        ▼
DualPlayerEngine
   ├── ExoPlayer A（master）
   └── ExoPlayer B（交叉淡入预缓冲，aux timeline 窗口 ≤200 条）
        │
        ▼
CloudPlaybackUriResolver  ← offline → LRU 缓存 → proxy（navidrome:/jellyfin: 仍留在 timeline）
```

- **交叉淡入**：`performOverlapTransition` 双播放器同播 + 每 ~32ms 音量包络，完成后角色互换；退场侧 `stop/clear` 不 `release`（双 Exo 常驻是设计代价）。
- **状态归属**：播放真相在 Service 侧 `engine.masterPlayer`；UI 经 MediaController 读写。队列/进度等 UI 状态在 `@Singleton` Holder 中，由 `PlayerViewModel` 的 `initialize` / `onCleared` 驱动生命周期。
- **听歌统计**：唯一驱动是 `MusicService.syncLocalListeningStatsFromPlayer`（`MediaItemBuilder.EXTRA_QUICK_PLAY` 在入口拦截）。

### 1.2 外部试听（ExternalPlayer）

`ExternalPlayerActivity` 定位为**独立试听播放器**：试听文件管理器等打开的歌曲文件，**不走曲库历史/听歌统计路径**。当前实现用独立的 `PlayerViewModel`（`by viewModels()`）+ `ExternalMediaStateHolder`（可选整文件拷贝到 `cacheDir/external_audio` 以便文件夹 sibling 连播）。

---

## 2. 性能发现

| 级别 | 问题 | 位置 | 说明 |
|---|---|---|---|
| P1 | 云曲 URI 在 loading 线程 `runBlocking` | `DualPlayerEngine` `resolveDataSpec` | 缓存未命中 + proxy `ensureReady` 最长数秒，阻塞 Exo 加载线程，易欠载 |
| P1 | Metadata/Widget 全量刷 | `MusicService.onMediaMetadataChanged` → `requestWidgetFullUpdate(force=true)` + `refreshMediaSessionUiWithFollowUp` | LyricTitle 合成标题会触发主题/封面/Room 级 widget 重建；且 force + follow-up 会打两遍 |
| P1 | 主线程扫全队列 | `MusicService.capturePlaybackSnapshot` 强制 `Main.immediate` + `buildPlaybackSnapshotItems` | 大曲库切歌热路径卡顿 |
| P2 | `clearQueueExceptCurrent` 逐条 `removeMediaItem` | `PlayerViewModel` | 大队列 IPC 风暴 |
| P2 | `MappingPlayer.getCurrentTimeline` 每次分配 | `MappingPlayer` | 高频 timeline 查询上的垃圾 |
| P3 | 进度双循环 | `PlaybackStateHolder` 250ms/1s + `rememberSmoothProgress` 180ms | 轻微冗余，可合并采样 |

保留的好设计：进度 tick 按订阅分级；`FullPlayerLoadingTweaks` 延后全屏 chrome；`PlaybackSnapshotItemCache` 在 playlist 变更前复用；队列列表 key 稳定。

---

## 3. 内存发现

| 级别 | 问题 | 位置 | 说明 |
|---|---|---|---|
| P0 | **第二 `PlayerViewModel` 拆共享 Holder** | `ExternalPlayerActivity` + `PlayerViewModel.onCleared` + `PlaybackStateHolder` 等 | Holder **无 owner 协议**（仅 `SearchStateHolder` 有）：试听 Activity 销毁会 `onCleared`/`stopProgressUpdates`，主界面进度/搜索/曲库静默失效。与 AGENTS.md 已知问题同源，且更重 |
| P2 | `queueSnapshot` 不随 `release()` 清 | `DualPlayerEngine.release` | Engine `@Singleton`，停服后整队 MediaItem 仍驻留 |
| P2 | `MediaController` 栈 pop 不 `release()` | `PlaybackStateHolder` | IPC + listener 累积 |
| P2 | Widget/快照缓存与进程同寿 | `MusicService` art/theme 字节、`PlaybackSnapshotItemCache` | 可接受上限，但需知悉 |
| P2 | 外部文件夹 sibling **整文件拷贝** | `ExternalMediaStateHolder.persistExternalAudioForPlayback` 等 | 磁盘/内存尖峰（详见 §5.5） |
| P3 | `MediaMetadataRetrieverPool` 名不副实 | `acquire()` 每次 new，`clear`/`poolSize` 空操作 | 无泄漏，API 误导 |

双 Exo 常驻、`resolvedUriCache = LruCache(100)`、aux timeline 窗口 200 条——可接受。

---

## 4. 优先修复清单

1. **P0** External 试听不得拆主播放状态（见 §5.1 决议）
2. **P1** `resolveDataSpec` 去 `runBlocking`（异步 resolve / 移出 loading 线程）
3. **P1** 元数据刷新只刷标题相关字段（见 §5.3 决议）
4. **P1** 快照构建移出主线程
5. **P2** `release()` 清 `queueSnapshot` + callback；`MediaController` pop 时 `release()`
6. **P2** 外部文件拷贝策略（见 §5.5 说明与可选优化）；`playExternalUri` 补 request token

已知小竞态（低于上述优先级）：`FadingPlayer` 淡入中途捕获 volume；`awaitPlayerReady` 检查与 `addListener` 之间可能漏事件。

---

## 5. 问题决议记录

### 5.1 ExternalPlayerActivity 第二 VM（问题 1）

**产品意图**（用户 2026-09 确认）：独立的**试听**播放器，用于试听文件管理器等送进来的歌曲文件，**不进曲库历史、不进听歌统计**。

**结论：定位正确，保留独立试听形态，不必并回主播放链路。** 仍需修的是生命周期误伤，不是“第二 VM 本身”：

- 保留独立 `ExternalPlayerOverlay` / `playExternalUri`；试听不写 Engagement、不进听歌统计。
- **要修的 bug**：`ExternalPlayerActivity.kt:33` 的 `by viewModels()` 会 new 完整 `PlayerViewModel`，其 `onCleared` 会拆 `@Singleton` Holder（`PlaybackStateHolder` / `QueueStateHolder` / `LibraryStateHolder` 等**没有** owner 守卫，仅 `SearchStateHolder` 有）。试听页一关，主界面进度 tick / 搜索会静默失效——这与“要不要记历史”无关。
- **建议改法（二选一）**：共享 Holder 补 first-owner 协议（对齐 `SearchStateHolder`），或试听走轻量通道、根本不 `initialize` 主 Holder。
- 统计侧继续用 `MediaItemBuilder.EXTRA_QUICK_PLAY` 在 `syncLocalListeningStatsFromPlayer` 入口拦截（勿改用 `EXTERNAL_EXTRA_FLAG`）。试听若也经 `MusicService`，确认同一拦截标记即可。

### 5.2 性能 P1 两项（问题 2）

**同意实施**：`resolveDataSpec` 去 `runBlocking`；快照构建移出主线程。

### 5.3 只刷标题相关字段会不会伤车机蓝牙标题/歌词（问题 3）

**不会。** 两条链路是拆开的：

| 链路 | 通道 | 本次动不动 |
|---|---|---|
| 车机 AVRCP 标题/歌词 | `LyricTitlePlayer` 合成元数据 → `onMediaMetadataChanged` → **MediaSession / legacy stub**（`LyricTitlePlayer.kt` 注释写明 AVRCP 读这里；另有 timeline 闸门 + 2s 重试） | **不动** |
| 桌面 Glance Widget 全量重建 | `MusicService.playerListener.onMediaMetadataChanged`（`MusicService.kt:1249–1251`）→ `requestWidgetFullUpdate(force=true)` + `refreshMediaSessionUiWithFollowUp`（主题色、封面字节、Room 队列预览、media button 签名） | **只收这里** |

MediaSession 元数据（含 LyricTitle 合成标题）照常完整发布；降级的只是 widget 的 force 全量重建。车机蓝牙标题、逐行歌词不受影响。实施后建议真机连车机回归一次（门禁见 `docs/car-lyrics-avrcp-gate.md`）。

### 5.4 内存 P2（问题 4）

**同意实施**：`release()` 清 `queueSnapshot` 与 callback 列表；`MediaController` 弹出时 `release()`。

### 5.5 外部文件为何复制（问题 5）

**含义**：`ExternalMediaStateHolder.persistExternalAudioForPlayback`（`ExternalMediaStateHolder.kt:310–334`）把 `content://` 音频流**整文件**拷到 `cacheDir/external_audio/`，再用 `file://` 播。封面另存 `external_artwork/`（小）。

**触发条件**（不是无脑拷）：`resolveDirectFilePath` 拿不到真实可读路径且 scheme 为 `content`（`:232–237`）——典型是 SAF / 文档提供器 / 文件管理器临时 URI。MediaStore 有可读 `DATA`、或本身就是 `file://` 时**不拷**。

**为什么要拷**：

1. **授权会过期**：`ACTION_VIEW` 的读权限通常只在本次任务栈内有效。`ExternalPlayerActivity.persistUriPermissionIfNeeded`（`:124–144`）只在 intent 带 `FLAG_GRANT_PERSISTABLE_URI_PERMISSION` 时才 `takePersistableUriPermission`，多数文件管理器不给。不落地的话，服务恢复、稍后重进、grant 过期后同一 URI 会读失败。
2. **文件夹连播**：一次「打开」往往只授权当前这一首；同目录 sibling（`:128–135`）想接着播，就得在还有权限时读出来——当前是**每个 sibling 也走同一套 persist（整文件拷贝）**，所以同目录多首歌会 I/O/磁盘尖峰。
3. **稳定可 seek 路径**：部分源需要稳定 fd/路径做 seek 与断点恢复；ExoPlayer 对临时 `content://` 更脆。

**可选优化**（备忘，非本次决议必须）：persistable 权限拿得到就直接播 URI 不拷；仅在 grant 即将失效时拷贝；sibling 改按需/引用而非预拷；`external_audio` 加 LRU 上限与退出清理。

---

## 6. 怎么读这份文档

- 摸播放问题先看 §1 分层，再查 §2/§3 表里的级别与位置；
- 改 External / 标题刷新 / 外部文件策略前，先读 §5 对应决议，避免推翻已定产品意图。
