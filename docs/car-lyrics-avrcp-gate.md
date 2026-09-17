# 车机标题的 AVRCP「同步闸门」与绕过方案

> 专题文档，配合 `docs/car-lyrics-title-plan.md`（车机歌词标题功能本体）阅读。
> 本文只讨论**一件事**：为什么手机已经在推进新标题，车机上却会「卡住不动」，以及如何从我们这一侧短路那道闸门。
> 源码版本：AOSP `packages/modules/Bluetooth`（`lineage-23.2` 镜像）、androidx/media `release` 分支、本仓库当前 `main`。

## 0. 一句话结论

车机标题卡住不是我们发得慢，而是**蓝牙进程里有一道「元数据同步」闸门**：它要求「当前播放的队列项」与「当前元数据」一致，不一致就不发、改排 2 秒后重试。我们的功能按设计恰恰要改 Title，于是每次都能踩中，且**新变更会取消上一次的重试** —— 变更间隔小于 2 秒就永久饿死。

绕过它**不需要改蓝牙**（那是 Mainline 模块，改它要系统签名/root）：闸门自己留了短路条件，而进入短路所需的数据**全部由我们通过 media3 提供**。

## 1. 现象与现场判据

已观察到的现象：

- 车机标题**长时间停在某一行**，人声早已过去；安静 2 秒后可能「跳」到当前行。
- 手机侧一切正常：媒体通知、锁屏、App 内歌词都照常前进。

**2 分钟现场判据（不需要改代码）**：卡住时看**手机通知栏/锁屏标题**（它读同一个会话，但绕开了蓝牙进程）。

| 通知栏 | 车机 | 结论 |
|---|---|---|
| 正常前进 | 不动 | 闸门假说成立，问题在蓝牙栈 → 本文方案有效 |
| 一起冻住 | 不动 | 我方调度停止了（另一类问题，见 `car-lyrics-title-plan.md` §9.5 / §11.14） |

想拿到更硬的证据，可开 HCI snoop 看卡住期间 `btsnoop` 里是否还有 `EVENT_TRACK_CHANGED` / `GetElementAttributes` 往返。

## 2. 数据流与三层归属

「队列」不是某一层的私有物，它跨三层：

| 层 | 实现处 | 我们能改吗 | 角色 |
|---|---|---|---|
| 队列 / 元数据 / `activeQueueItemId` 的**内容** | media3（编译进 APK）+ 我们的代码 | ✅ | 生产者 |
| 队列的**载体与缓存** | framework `MediaSessionService`（system_server） | ❌（除非改 ROM） | 中转 |
| **`isMetadataSynced` 闸门 + 2s 重试** | AOSP `packages/modules/Bluetooth` | ❌（需系统签名/root） | 消费者 |

蓝牙进程只认框架 legacy API —— `MediaPlayerWrapper.java:21-23` 的 import 是 `android.media.session.MediaController` / `PlaybackState` / `android.media.MediaMetadata`，它不认识 media3 的任何类。media3 只是「把队列和元数据写进框架会话」的那一端：

- `MediaSessionLegacyStub.java:1253-1254` —— 用 timeline 填队列（`MediaSessionCompat.setQueue`）
- `MediaSessionLegacyStub.java:1934-1937` —— 用当前位置填 `activeQueueItemId`
- `LyricTitlePlayer`（本仓库）—— 在 session-facing 的一侧改写元数据 Title

## 3. 根因：蓝牙进程里的「元数据同步」闸门

### 3.1 我方是 fire-and-forget

`CarLyricTitleController.publish()` → `LyricTitlePlayer.publishMetadataOverride()`（同步回调 `onMediaMetadataChanged`）→ `MediaSessionImpl` → legacy `MediaSessionCompat`。**全程不等 ack、无发送队列、车机也不会回话**，所以「车机没接住上一条」本身不会阻塞我们后续的发布。

### 3.2 闸门本体

`audio_util/MediaPlayerWrapper.java`：

```java
// :444-470  MediaControllerListener.trySendMediaUpdate()
mTimeoutHandler.removeMessages(TimeoutHandler.MSG_TIMEOUT);   // ← 关键：先撤掉上一次待发的补发
if (!isMetadataSynced(mdata)) {
    mTimeoutHandler.sendEmptyMessageDelayed(MSG_TIMEOUT, 2000);  // 重排 2s
    return;                                                     // ← 不发给车机
}
sendMediaUpdate(mdata);
```

```java
// :391-418  TimeoutHandler（CALLBACK_TIMEOUT_MS = 2000）
handleMessage() → sendMediaUpdate(new MediaData(metadata, state, current_queue));  // 无条件补发
```

三个入口全部汇入同一个方法：`onMetadataChanged`（`:484-497`）、`onPlaybackStateChanged`（`:505-518`）、`onQueueChanged`（`:544-580`）。

**⇒ 每次新变更都 `removeMessages` + 重排那个 2s 定时器。变更间隔 < 2s 时补发永远不会到期 —— 车机在那段时间一条更新都收不到，标题冻住。**

### 3.3 为什么我们的歌词标题必然「不同步」

```java
// :267-303  isMetadataSynced(MediaData)
final List<Metadata> queue = data.queue;
if (!queue.isEmpty() && state != null && state.getActiveQueueItemId() != -1) {
    // 找到 activeQueueItemId 对应的队列项 qitem
    if (qitem == null || !qitem.equals(mdata)) {
        // 退一步：只比 title + artist
        if (qitem != null && Objects.equals(qitem.title, mdata.title)
                          && Objects.equals(qitem.artist, mdata.artist)) {
            return true;
        }
        return false;      // ← out of sync ⇒ 走 2s 延迟路径
    }
}
return true;               // ← 注意这条：见 §4.1
```

- `Util.toMetadataList`（`helpers/Util.java:138-158`）给每个队列项**强写** `trackNum` / `numTracks`，而当前元数据走 `Util.toMetadata(MediaMetadata)` 不写 ⇒ `Metadata.equals`（`helpers/Metadata.java:69-88`，比较 title/artist/album/trackNum/numTracks/genre/duration/image）必假。
- 于是实际判据退化成 **「队列项的 title 是否等于当前 title」**（第 3.3 节的 `title + artist` 兜底）。
- 而队列项的 title 来自**时间轴里那条 `MediaItem` 的 metadata**（`LegacyConversions.convertToQueueItem:430`），**永远是真实曲名**；media3 只在 `onTimelineChanged` 时重建队列（`MediaSessionLegacyStub:1626-1633` → `updateQueue`），`onMediaMetadataChanged` 只跑 `updateMetadataIfChanged`。
- 我们按设计改的恰恰是 Title ⇒ **只要歌词覆盖生效，就永远「out of sync」。**

模拟器实测坐实了闸门的输入条件成立：`dumpsys media_session` 里我们的会话是 `active item id=9`、`queueTitle=null, size=60`。

### 3.4 为什么最近感觉更容易卡

上一版把每段标题从 10 字母放宽到 30 字母（汉字仍 10 字），一段一行 → 一行 2 段，变更间隔从「一行一次」变成「1–2 秒一次」，正好压在 2s 窗口以下。这是可检验的预测：**卡住应该出现在快歌/密词段**。

### 3.5 没有旁路

`AvrcpTargetService.ListCallback.run:176-196` 把 metadata 差异算成 `track_changed` 再 `sendMediaUpdate(true, …)`；`AvrcpNativeInterface` 只有 `sendMediaUpdateNative`，**没有 getMetadata 的 JNI 回拉** ⇒ 车机的 `GetElementAttributes` 只能读到上次 push 出去的缓存。不发通知 = 车机显示旧标题，没有第二条路。

## 4. 唯一的绕过入口：闸门的短路条件

### 4.1 判据

再看 §3.3 的代码，前置条件不成立时方法直接 `return true`（视为同步 ⇒ 立即发送）：

```
!queue.isEmpty()  &&  state != null  &&  state.getActiveQueueItemId() != -1
```

即：**队列为空**，或 **`activeQueueItemId == -1`**，或没有 PlaybackState ⇒ 闸门短路。

### 4.2 `activeQueueItemId` 由 media3 决定

```java
// MediaSessionLegacyStub.java:1934-1937
long queueItemId = player.isCommandAvailable(Player.COMMAND_GET_TIMELINE)
    ? LegacyConversions.convertToQueueItemId(player.getCurrentMediaItemIndex())
    : MediaSessionCompat.QueueItem.UNKNOWN_ID;      // == -1
```

这里的 `player` 是 `PlayerWrapper`（`createPlaybackStateCompat(PlayerWrapper player)`，`:1879`），它的 `isCommandAvailable` / `getAvailableCommands` 直接转发（`PlayerWrapper.java:769-777`）到**我们传给 MediaSession 的那个 player** —— 也就是本仓库的 `LyricTitlePlayer`。

⇒ **在 `LyricTitlePlayer` 上摘掉 `COMMAND_GET_TIMELINE`，`activeQueueItemId` 立刻变 -1，闸门短路。**

### 4.3 这是 media3 支持的合法用法，不是 hack

1. `ForwardingPlayer` 的类文档（`:41-46`）明确写了「子类想移除某个可用命令」时该怎么做 —— 第一条就是 **override `isCommandAvailable(int)` 和 `getAvailableCommands()`**。
2. media3 为「没有 timeline 命令」的场景专门准备了降级路径：`PlayerWrapper.getCurrentTimelineWithCommandCheck()`（`:489-496`）在无命令时返回 `CurrentMediaItemOnlyTimeline`（只含当前曲目）而不是空。
3. `COMMAND_GET_TIMELINE` 在「命令 → `PlaybackStateCompat` actions」的映射里返回 `0`（`MediaSessionLegacyStub:2008-2062`，落到 default 分支）⇒ **摘掉它不会让车机/蓝牙少任何按钮能力**（`ACTION_SKIP_TO_QUEUE_ITEM` 归 `COMMAND_SEEK_TO_MEDIA_ITEM`，不受影响）。
4. 命令一变，legacy 会话会**立刻**重算状态与队列（`MediaSessionLegacyStub:290-311`）：

   ```java
   boolean commandGetTimelineChanged = ...;
   if (commandGetTimelineChanged) {
       updateLegacySessionPlaybackStateAndQueue(sessionImpl.getPlayerWrapper());  // state + queue 一起刷
   } else {
       updateLegacySessionPlaybackState(sessionImpl.getPlayerWrapper());
   }
   ```

   ⇒ 摘除与恢复都是**即时**的，不必等下一次 timeline 变化。

而且这条路径是**自动收尾**的：命令变化会让 session 重算并推一次 PlayerInfo / PlaybackState，蓝牙侧的 `onPlaybackStateChanged` 随即 `trySendMediaUpdate` —— 此刻判据已是同步，于是把当前标题立刻发出去。这正是我们要的「掐掉 2 秒延迟」。

## 5. 代价评估（逐条读源码，非推测）

### 5.1 不变的部分

| 项 | 结论 | 依据 |
|---|---|---|
| 车机/蓝牙的播放、暂停、上一首/下一首、seek 按钮 | 全保留 | `COMMAND_GET_TIMELINE` 不贡献任何 action（§4.3 第 3 条）；下详 |
| App 内**歌词显示** | 不受影响 | 走 `LyricsStateHolder` + `StablePlayerState` + 位置流（`PlayerViewModel:268/835/872`、`LyricsSheet:230-242`），与 MediaSession 无关 |
| App 内**播放控制** | 不受影响 | 读的是 `COMMAND_GET_CURRENT_MEDIA_ITEM` / `COMMAND_PLAY_PAUSE` 等 |
| App 内**队列写操作**（增删、移动、替换） | 命令层面不受影响 | `replaceMediaItems` 守卫是 `COMMAND_CHANGE_MEDIA_ITEMS`（`MediaControllerImplBase:1437-1440`） |

> ⚠️ **用户假设的更正**：一开始的判断是「只影响蓝牙标题歌词」。**歌词显示这一条成立**，但「只影响车机」不成立 —— 见 5.2。

#### 5.1.1 「下一首」这条链路的三段（逐段读码）

蓝牙耳机 / 车机的物理 next 键走的是 AVRCP **passthrough**，与队列无关：

1. **蓝牙侧**：`AvrcpTargetService.sendMediaKeyEvent:478` 只把 key 转成 `KeyEvent` 交给 `AudioManager`；另一条 `MediaPlayerWrapper.skipToNext():226-229` 直接 `controller.skipToNext()`。**两处都不读 `PlaybackState.getActions()`**（`MediaPlayerList` 里连 `getActions` 的引用都没有）。
2. **media3 侧**：`MediaSessionLegacyStub.onSkipToNext():656-670` → `COMMAND_SEEK_TO_NEXT`（否则 `COMMAND_SEEK_TO_NEXT_MEDIA_ITEM`）→ `PlayerWrapper.seekToNext()/seekToNextMediaItem()`（`:460-463`、`:805-807`，只 `verifyApplicationThread()` 后转发）。**两个命令都没被摘，也不需要 timeline。**
3. **实测**：窗口内与基线的 `PlaybackState.actions` **完全相同，都是 `7339995`** —— 摘 timeline 一个 bit 都没动（`dumpsys media_session`）。

#### 5.1.2 短路的生效条件（五个条件同时成立才走）

不变式是 `metadataOverride != null ⟺ timelineHidden`（`LyricTitlePlayer`）。控制器的 `tick()` 有五个出口会 `publish(player, null)`，所以：

| # | 条件 | 不成立时的日志 | 代码 |
|---|---|---|---|
| 1 | 设置「标题显示歌词」打开 | `idle: toggle off` | `CarLyricTitleController:219` |
| 2 | 音频路由到蓝牙 A2DP | `idle: bluetooth output not active` | `:226` |
| 3 | 有正在播放的曲目 | `idle: nothing playing` | `:234` |
| 4 | 该曲目有同步歌词 | `idle: no synced lyrics for song …` | `:247` |
| 5 | 当前 cue 文本非空（不是前奏 / 间奏 / 空行） | —— | `publish:434-437`，空文本 ⇒ `publishMetadataOverride(null)` |

**关掉开关是双保险**：偏好流变化时就地 `stopScheduling() + publish(null)`（`:143-151`，不等下一个边界），下一次 `tick()` 还会再兜一次（`:219`）。

⇒ **关闭后 `LyricTitlePlayer` 退化成一个纯粹的转发 wrapper**：`getAvailableCommands()` 恒等于 inner、不派发任何命令变更事件，**与没有这个包装类逐字节等价**。而开关默认关闭，所以不主动打开的用户，行为与改动前完全一致。

> ⚠️ **一项此前未评估的抖动（改动引入，真车留意）**：条件 5 意味着在车上听歌时，命令会随歌词行的节奏在「隐藏 ↔ 恢复」之间来回切。蓝牙侧 `MediaPlayerWrapper.onQueueChanged:548-570` 会监听队列变化，并先 `Util.toMetadataList(mContext, queue)` **全量转换**再与缓存比对 —— 队列 N 首时每次切换是 O(N)，频率约 1–3 s。空中流量不增（封面按 hash 去重），但蓝牙进程的 CPU 与「车机播放列表是否闪烁」值得在真车上看一眼。若不可接受，退路是 §10 的节流（把更新压到 ≥2 s）。

> 顺带一个**与本改动无关**的既有现象：`7339995` 里**没有** `ACTION_SKIP_TO_NEXT`（32），但有 `ACTION_SKIP_TO_PREVIOUS`（16）。原因是平台自定义布局里有 `SLOT_FORWARD` 按钮，media3 会据此摘掉它（`MediaSessionLegacyStub:1928-1931`）。改动前后一致，不是本次引入；若发现某些车机不显示「下一首」，根因在这里而不在歌词标题。

### 5.2 会变的部分

**A. 目标变化（就是我们想要的）**

- `isQueueEnabled()` 变 false（`MediaSessionLegacyStub:1262-1266`）⇒ 不再向框架暴露队列（`setQueue(null)`）、`queueTitle` 置 null。
- legacy `PlaybackStateCompat.activeQueueItemId = -1`。

**B. 连带：media3 的 `MediaController` 会看不到完整队列** ⚠️

```java
// MediaSessionImpl.java:862-865  —— 发给 controller 的命令 = 交集
Player.Commands intersectedCommands = MediaUtils.intersect(
    controllersManager.getAvailablePlayerCommands(controller),
    getPlayerWrapper().getAvailableCommands());     // ← 就是 LyricTitlePlayer
```

且每次广播 PlayerInfo 都用带命令检查的 timeline：

```java
// MediaSessionImpl.java:2388-2397  PlayerInfoChangedHandler
playerInfo = playerInfo.copyWithTimelineAndSessionPositionInfo(
    getPlayerWrapper().getCurrentTimelineWithCommandCheck(), ...);
```

`PlayerWrapper:489-496` 在无 GET_TIMELINE 时返回 `CurrentMediaItemOnlyTimeline` ⇒ **摘命令的瞬间，所有 media3 controller 看到的 timeline 降级为「只有当前曲目」（`mediaItemCount == 1`、`currentMediaItemIndex == 0`）**，命令恢复后随之恢复。

`handleAvailablePlayerCommandsChanged`（`MediaSessionImpl:1590-1596`）会显式推一次 PlayerInfo，所以这不是「等下次变化」，是**立刻**。

**C. 本仓库里会被影响的调用点**（窗口内，全部是「静默退化」而非崩溃）

| 文件:行 | 读取 | 窗口内表现 |
|---|---|---|
| `presentation/viewmodel/PlayerViewModel.kt:2796` | `updateCurrentPlaybackQueueFromPlayer(mediaController)` | 队列 UI 只剩当前一首 |
| `PlayerViewModel.kt:2088-2090` | `mediaItemCount` / `currentMediaItemIndex`（移动歌曲） | 索引校验失败 ⇒ 拖动排序静默无效 |
| `PlayerViewModel.kt:3283-3286` | `currentMediaItemIndex + 1`（「下一首播放」插入位置） | 插到队首之后，位置错误 |
| `PlayerViewModel.kt:3872-3878` | 遍历 `currentTimeline` 删除指定项 | 只遍历到 1 项 ⇒ 删不掉 |
| `PlayerViewModel.kt:1990` / `:1996` | `currentMediaItemIndex` / `mediaItemCount` | 多一次 seek；`> 0` 判断仍成立，无害 |
| `presentation/viewmodel/PlaybackStateHolder.kt:639-662` | `mediaItemCount` / `currentMediaItemIndex`（队列替换的索引校验） | 校验失败 ⇒ 队列分段更新被跳过 |

**D. 外部 controller「按 mediaId 删队列项」会静默失败**（窗口内）

```java
// MediaSessionLegacyStub.onRemoveQueueItem():806-809
if (!player.isCommandAvailable(Player.COMMAND_GET_TIMELINE)) {
    Log.w(TAG, "Can't remove item by ID without COMMAND_GET_TIMELINE being available");
    return;
}
```

AVRCP 不会走这条路径（它只发 passthrough 与 `GetElementAttributes`），Android Auto / Wear OS 理论上可能用。本仓库没有调用方，属于外部消费者的潜在退化，记录备查。

**窗口定义**：以上只在「蓝牙 A2DP 活跃 **且** 歌词覆盖生效」期间发生 —— 也就是插着车机听歌、且车机正在显示歌词的时候。断开蓝牙或关掉「标题显示歌词」即刻恢复。

### 5.3 专辑封面：当前曲目不受影响，队列封面会消失

**结论：当前曲目的封面照常传给车机。** 逐层依据：

| 环节 | 依据 | 是否受摘命令影响 |
|---|---|---|
| 我方构造覆盖元数据 | `CarLyricTitleController.publish` 用 `innerPlayer.mediaMetadata.buildUpon().setTitle(it)` —— 只改 title，`artworkUri` / `artworkData` 原样保留 | ❌ |
| media3 取元数据 | `MediaSessionLegacyStub.updateMetadataIfChanged:1753` 用 `player.getMediaMetadataWithCommandCheck()`，而 `PlayerWrapper:764-766` 的门禁是 **`COMMAND_GET_METADATA`**，不是 `COMMAND_GET_TIMELINE` | ❌ |
| 写进框架会话 | `LegacyConversions:736-741` 把 `artworkUri` 写为 `METADATA_KEY_ART_URI` / `METADATA_KEY_ALBUM_ART_URI`，另由 session 的 BitmapLoader 填 bitmap | ❌ |
| 蓝牙侧取图 | `Image(Context, MediaMetadata)`（`:54-79`）按 `ART` → `ALBUM_ART` → `DISPLAY_ICON` 的 bitmap，再退到三个 URI | ❌ |
| 存图与下发 | `AvrcpTargetService.getCurrentSongInfo:363-368` → `AvrcpCoverArtService.storeImage:152-156` → `AvrcpCoverArtStorage.storeImage:58-89` **按图像 hash 去重**，已存在的图直接返回原 handle | ❌ |

⇒ **车机不会重新下载封面**（handle 不变），也不会丢当前封面。

**会消失的是队列项的封面**：`AvrcpTargetService.getNowPlayingList:396-415` 逐个队列项 `storeImage`；队列被隐藏后 now-playing list 为空，所以车机若提供「播放列表/队列浏览」的封面，窗口内看不到 —— 与 §9.2 是同一条代价，不是新增的。

**一项新增成本（值得在真车上留意）**：摘掉闸门后，每条 cue 都会**真正发出**（此前被 2s 重试饿死），而蓝牙进程每收到一次元数据变更就构造一次 `Metadata`：`MediaPlayerWrapper.onMetadataChanged:484+` 与 `trySendMediaUpdate:449-454` 各一次，两次都会 `new Image(context, metadata)`。当应用只提供 URI 时，`Image.getImageFromUri:177-198` 是**同步解码**（`BitmapFactory.decodeStream`）。空中流量不增加（handle 去重），但蓝牙进程里每 1–3 秒多一次解码 + hash。若真车出现封面闪烁或蓝牙进程 CPU 异常，退路是 §10 的节流方案（把更新压到 ≥2s）。

## 6. 方案①：实现设计

### 6.1 落点

**`data/service/player/LyricTitlePlayer.kt`** —— 理由：

- 它已经是 session-facing 的最外层 wrapper（`MusicService.wrapFadingPlayer:318-325` 保证「must stay outermost」），`MediaSession` 拿到的就是它；
- 它已经为「合成事件」维护了 `registeredListeners`（用于 metadata 覆盖），命令变更事件可以复用同一机制；
- 命令摘除与 metadata 覆盖**天然是同一件事的两面**：只有正在覆盖时闸门才会不同步。

`CarLyricTitleController` 无需改动逻辑。

### 6.2 不变式

> **`metadataOverride != null` ⟺ 隐藏队列（摘掉 `COMMAND_GET_TIMELINE`）。**

这条不变式让窗口自动成立，不需要 `CarLyricTitleController` 里任何新判断：

- 覆盖生效的三条门槛（开关开、蓝牙活跃、有 synced lyrics）现有代码已经管住；
- 每个「恢复真实标题」的出口都是 `publish(player, null)` ⇒ `override = null` ⇒ 队列恢复。这些出口已覆盖：开关关闭（`:146-152`）、蓝牙不活跃（`:226-231`）、没有播放内容（`:233-239`）、无同步歌词（`:247-252`）、切歌（`:275`）；
- 服务/会话销毁时不再调用 ⇒ 与 session 一起消亡，无残留。

### 6.3 两个必须做对的地方

**(1) `isCommandAvailable` 必须一起覆盖**

`ForwardingPlayer` 的两个方法互相独立（`ForwardingPlayer:217-218` 与 `:229-230` 各自 delegate），而 `MediaSessionLegacyStub:1934` 用的正是 `isCommandAvailable`。只改 `getAvailableCommands()` 会导致 `activeQueueItemId` 仍是有效值 ⇒ **短路失效**。

**(2) 必须自己派发命令变更事件**

session 是通过 `Player.Listener.onAvailableCommandsChanged` 得知命令集变化的（`MediaSessionImpl:2199-2209` → `handleAvailablePlayerCommandsChanged`）。inner player 没变，事件不会自动来 —— 必须像现有的 metadata 覆盖那样，对 `registeredListeners` 主动派发一次，否则 legacy 会话不会重算，短路不会生效。

**顺序**：在 `publishMetadataOverride` 里**先**摘/恢复命令、**再**派发 metadata 事件。先摘命令时，session 重算发出的那次 `sendMediaUpdate` 因数据未变会被蓝牙的 `newData.equals(mCurrentData)` 去重掉（`MediaPlayerWrapper:369-377`），随后 metadata 事件到达时判据已是同步 ⇒ 一次就发到位，不留中间态。

### 6.4 代码骨架（设计稿，尚未落地）

```kotlin
// LyricTitlePlayer.kt
private var queueHidden = false

override fun getAvailableCommands(): Player.Commands =
    innerPlayer.availableCommands.buildUpon()
        .removeIf(Player.COMMAND_GET_TIMELINE, queueHidden)
        .build()

override fun isCommandAvailable(command: Int): Boolean =
    command != Player.COMMAND_GET_TIMELINE || !queueHidden

fun publishMetadataOverride(override: MediaMetadata?) {
    if (metadataOverride == override) return
    metadataOverride = override
    setQueueHidden(override != null)       // 先命令，后 metadata
    val published = mediaMetadata
    registeredListeners.forEach { runCatching { it.onMediaMetadataChanged(published) } }
}

private fun setQueueHidden(hidden: Boolean) {
    if (queueHidden == hidden) return
    queueHidden = hidden
    val commands = availableCommands
    registeredListeners.forEach { runCatching { it.onAvailableCommandsChanged(commands) } }
}
```

要点：

- 幂等：`queueHidden` 相同即返回；`buildUpon().removeIf(...)` 对命令集做的是「按条件移除」，重复调用结果一致。
- 不 no-op `getTimeline()` / `getCurrentMediaItemIndex()`：**故意保留真实 timeline 数据**，让 media3 内部那些「带命令检查」的取用点（`getCurrentTimelineWithCommandCheck`）走降级路径，而其余路径不受影响。这符合 `ForwardingPlayer` 文档的意图（它把 timeline 降级交给 `PlayerWrapper` 处理）。
- `Player.Commands.Builder.removeIf(@Command int, boolean)` 见 `Player.java:722`；`buildUpon()` 见 `:761`。

### 6.5 线程

`publishMetadataOverride` 的调用点已经在 `Dispatchers.Main.immediate` 上（`CarLyricTitleController.tick:211`，注释也说明了原因：media3 会在回调里 `verifyApplicationThread`）。命令派发在同一调用栈里，无需额外切换。

### 6.6 `onPlayerReplaced` 的处理

`MusicService.publishMediaSessionPlayer:327-345` 每次替换 session player 都会**新建**一个 `LyricTitlePlayer`（默认 `queueHidden = false`），并调用 `carLyricTitleController?.onPlayerReplaced()`（`:338`）。现有实现会把 `lastPublishedCue` 重置为 `CUE_UNPUBLISHED` 并 signal，下一个 tick 会重新 publish ⇒ 新 wrapper 自动重新摘命令。**无需额外改动**，但这是回归测试必须覆盖的一条路径。

## 7. App 内适配（1B，已实施）

不能接受 §5.2-C 的窗口内退化，所以把所有**队列读取**改为直连引擎的 master player：**读走 master，写仍走 controller。**

`PlaybackStateHolder` 里那条「不要直接操作 master player」的注释警告的是**写**路径（绕过 session 改 master 会让 controller 的 timeline 快照、通知、widget 队列预览失同步）；本方案只换读，与该警告不冲突。

### 7.1 为什么可以直换

master player 就是 session 包着的那个 player（`MusicService.wrapFadingPlayer`），两者之间只有 `MappingPlayer`（**仅改写 `artworkUri`**）与 `FadingPlayer`。所以除窗口内以外，`masterPlayer.mediaItemCount` / `.currentMediaItemIndex` / `.getMediaItemAt(i)` 与 controller 的返回值**逐字相同**，`mediaId` 更是恒等 ⇒ 这是等价替换，不是行为变更。窗口化队列（windowed queue）也不受影响：master 与 controller 看到的都是同一个窗口，既有代码在需要绝对下标时已经走 `getCurrentAbsoluteIndex()`。

### 7.2 改动清单

两边各加一个 `queueTimelineSource`（`get() = dualPlayerEngine.masterPlayer`），注释说明「读走它、不走 controller」的原因：

| 文件 | 位置 | 读的是什么 |
|---|---|---|
| `PlayerViewModel.kt` | `queueTimelineSource` 属性 | — |
| | `resolveReusablePlaybackTargetIndex`（改为 `Player` 扩展） | `currentMediaItemIndex` / `mediaItemCount` / `getMediaItemAt` |
| | `playLoadedControllerItem` | `currentMediaItemIndex` / `mediaItemCount` |
| | `removeSongFromQueue` / `undoRemoveSongFromQueue` → `QueueUndoStateHolder`（新增 `queueSource: Player` 参数） | `mediaItemCount` / `getMediaItemAt` |
| | `reorderQueueItem` | `mediaItemCount` / `currentMediaItemIndex` |
| | `updateCurrentPlaybackQueueFromPlayer` | `currentTimeline` |
| | `syncDisplayedMediaItemIfChanged`、播放监听里的 `onMediaItemTransition` / `onPlaybackStateChanged` | `currentMediaItemIndex` / `getMediaItemAt` / `mediaItemCount` |
| | `addSongNextToQueue`、`removeFromMediaControllerQueue`、`clearQueueExceptCurrent` | `mediaItemCount` / `currentMediaItemIndex` / `currentTimeline` |
| | 三处「编辑元数据后 `replaceMediaItem`」 | `currentMediaItemIndex` / `mediaItemCount` |
| `PlaybackStateHolder.kt` | `queueTimelineSource` 属性 | — |
| | `updateStablePlayerState`（index == -1 的兜底） | `currentMediaItemIndex`（保留 `mediaController != null` 判空语义） |
| | `reorderQueueInPlace` | `mediaItemCount` / `getMediaItemAt` |
| | `replacePlayerQueuePreservingCurrent` | `mediaItemCount` 校验与末尾的下标复核（写仍用传入的 `player`） |
| | shuffle 切换的 `playerCurrentIndex` | `currentMediaItemIndex` |

**刻意没改**的两处：`playPause` 与 `shouldSampleLocalProgress` 的 `mediaItemCount > 0` / `<= 0` 判空。窗口内降级后计数是 1（有当前曲目）或 0（真的空），与真实值同真同假，改了只是徒增改动面。

`MusicService` 里那些「`mediaSession?.player ?: engine.masterPlayer`」的读取**不需要改**：`mediaSession.player` 就是 `LyricTitlePlayer` 本身，它**没有** no-op 任何 timeline getter（§6.4），读到的仍是真实队列。

## 8. 验证计划

> 完整的可复现步骤、坐标、logcat tag 与坑位已单独成文：**`docs/avrcp-emulator-verification.md`**（模拟器上验证 AVRCP）。本节只留结论。

**L1 单测（JVM）** — `app/src/test/.../LyricTitlePlayerTest.kt`，12 例：初始含 GET_TIMELINE、publish 后两个访问器都撤、其它命令不受影响、inner 的命令集不被改写、inner 本就没有的命令保持不可用、**命令事件先于元数据事件**、恢复真实标题时命令回来且事件再来一次、重复 publish 同一 cue 不再派发、连续 cue 只派发元数据、元数据的覆盖与回落、timeline 读取仍转发 inner、移除的 listener 收不到。

> ⚠️ 坑：`Player.Commands` 不能在本模块的 JVM 单测里**构造** —— 它背后的 `FlagSet` 会走到 `android.jar` 的空实现（本模块开了 `unitTests.isReturnDefaultValues = true`），实测 `Commands.Builder().addAllCommands().build().size() == 0`、`contains()` 恒为 false。所以测试用 mock 的 `Commands` 断言「我们报告了什么」，而不是「`FlagSet` 怎么算」。

**L2 机制验证（模拟器，2026-09-17 已做）** — 这台模拟器的 system image **自带真实 AVRCP target**（`bt_stack` / `AudioMediaPlayerWrapper`），所以能一路量到蓝牙进程内部，不用真车：

```
adb install -r -d pixelplayeross-arm64-v8a-0.4.2-pisces.1-debug.apk
adb shell settings put global pixelplayer_car_lyric_title_force_a2dp 1   # 模拟 A2DP 活跃
# 播放一首带同步歌词的曲目（注入过 40 字母英文行 + 21 汉字行）
```

| 观察点 | 窗口内（歌词生效） | 窗口外（删除标志 / 关开关） |
|---|---|---|
| `dumpsys media_session` 的 `active item id` | **-1**（改动前是 0/9） | 恢复为有效下标 |
| 同上，队列 | `queueTitle=null, size=0` | 恢复 |
| 同上，标题 | 逐段推进（`vwxyzabc…` → `八九十一二三四` → `五六七八九十一`） | 真实曲名 |
| 元数据 `size`（含 `ART_URI` 等封面字段） | 12，与窗口外一致 | 12 |

**端到端延迟（关键证据）** —— 我方发布到蓝牙进程真正 `SendMediaUpdate(track_changed=1)`：

| 我方 `car lyric title:` 时刻 | `bt_stack SendMediaUpdate(track_changed=1)` | 延迟 |
|---|---|---|
| 02:09:44.831 | 02:09:44.847 | 16 ms |
| 02:09:47.672 | 02:09:47.689 | 17 ms |
| 02:09:50.652 | 02:09:50.693 | 41 ms |
| 02:09:52.601 | 02:09:52.645 | 44 ms |
| 02:09:54.589 | 02:09:54.589 | <1 ms |
| 02:09:56.608 | 02:09:56.608 | <1 ms |

全程 `trySendMediaUpdate(): Starting media update timeout`（2s 延迟路径）出现 **0 次**；蓝牙进程侧看到的 PlaybackState 里同样写着 `active item id=-1`。这几段 cue 间隔正好约 2 秒，正是改动前会被闸门饿死的临界情形 —— 与「车机卡住」的主诉吻合。

**未做**：真车复测（本文 §9.1）；以及在真 A2DP 窗口里操作 App 队列 UI 的回归（模拟器 UI 交互不稳定，1B 由「读源等价」论证 + 全量单测覆盖）。

**L2 机制验证（模拟器，可做）** — 只需看车机将要读到的那份数据：

1. 装 debug APK，播放一首带同步歌词的曲目，`adb shell settings put global pixelplayer_car_lyric_title_force_a2dp 1` 打开 A2DP 模拟门控；
2. 覆盖生效时 `dumpsys media_session`：`active item id=` **应为 -1**（改动前是 `9`）；
3. 关闭「标题显示歌词」或删掉 A2DP 标志：`active item id` **应恢复为有效索引**；
4. `logcat` 里 `car lyric title:` 行应保持每段一条，不再出现「长间隔后跳变」。

**L3 真车（唯一判据）** — 车机标题应**每段即时切换**，不再有 2 秒粘滞；同时确认：车机的上一首/下一首/播放暂停、通知栏标题、锁屏标题一切照旧。

## 9. 已知限制与未验证项

1. **真车未验证**：本文所有结论来自源码 + 模拟器 `dumpsys`，闸门在真车上的表现（尤其「摘命令后是否真的即时」）需要一次实车确认。
2. **外部消费者的队列浏览**：车机/Android Auto 若提供「播放列表浏览」，在歌词覆盖期间会看不到队列 —— 与 App 内退化同源。这是本方案的设计代价。
3. **退出窗口的瞬间**：命令恢复后，框架里的队列要等下一次 `onTimelineChanged` 才真正重建（`updateQueue` 只在 `onTimelineChanged` 触发）。而 `setAvailableCommands` 的 `updateLegacySessionPlaybackStateAndQueue` 会在命令变化时刷新一次队列，所以这条大概率不是问题 —— **需要模拟器上确认**（切歌时应恢复完整队列）。
4. **命令谎报的边界**：App 内目前**没有任何 `isCommandAvailable` 调用**（已 grep 全仓确认），所以「命令与实际能力不一致」不会影响 App；但第三方或系统组件若依赖 `controller.isCommandAvailable(COMMAND_GET_TIMELINE)` 做判断，会在窗口内得到「不支持」的答案 —— 这正是我们想要的效果（让车机不去比队列）。
5. **窗口内用户正在 App 里操作队列**：见 §7，未做 1B 时会静默退化。

## 10. 备选方案②：不短路，只节流

若实车验证后认为第一节的代价不可接受，可退到「顺着闸门节流」：

- 把发布节奏压到 ≥2s（丢中间 cue、永远只发最新），使 2s 定时器有到期机会 —— 不再卡死，但保留约 2s 延迟；
- 把 `MAX_CAR_LYRIC_TITLE_LEAD_MS`（现 1000ms）上限提到 ~2500ms，用提前量抵消闸门延迟。

代价：约 15 行、零副作用；效果是「每段都到位、偏差几百毫秒」，密集段（<2s/段）会跳。与上一版把英文段放宽到 30 字母天然合拍（行更少、间隔更大）。

## 11. 源码坐标索引

| 归属 | 文件 | 关键位置 |
|---|---|---|
| AOSP 蓝牙 | `audio_util/MediaPlayerWrapper.java` | `:259-303` 同步判定、`:369-377` 发送去重、`:391-418` 2s 重试、`:444-470` 闸门、`:484-580` 三个入口、`:119-121` `getQueue()` |
| AOSP 蓝牙 | `audio_util/helpers/Util.java` | `:53` `NOW_PLAYING_PREFIX`、`:102-134` 队列项/元数据转换、`:138-158` 强写 trackNum/numTracks |
| AOSP 蓝牙 | `audio_util/helpers/Metadata.java` | `:69-88` `equals` 比较字段 |
| media3 | `MediaSessionLegacyStub.java` | `:290-311` 命令变化重算、`:1253-1266` 队列与 `isQueueEnabled`、`:1626-1685` `updateQueue`、`:1934-1937` `activeQueueItemId`、`:2008-2062` 命令→action |
| media3 | `MediaSessionImpl.java` | `:862-865` 命令交集、`:1590-1596` 命令变化处理、`:2199-2209` listener、`:2388-2397` PlayerInfo 广播 |
| media3 | `PlayerWrapper.java` | `:489-496` timeline 降级、`:769-777` 命令转发、`:924-950` 初始 PlayerInfo |
| media3 | `ForwardingPlayer.java` | `:41-46` 官方「移除命令」三步、`:217-230` 命令方法 |
| media3 | `MediaControllerImplBase.java` | `:830-832` `getCurrentTimeline`（无命令守卫）、`:1437-1440` 写操作守卫 |
| media3 | `Player.java` | `:708` `remove`、`:722` `removeIf`、`:745` `build`、`:761` `buildUpon` |
| AOSP 蓝牙 | `audio_util/helpers/Image.java` | `:54-79` 由 MediaMetadata 取图、`:177-198` URI 同步解码、`:220-235` handle |
| AOSP 蓝牙 | `avrcp/AvrcpTargetService.java` | `:176-193` `ListCallback.run`、`:363-368` 当前曲目封面、`:396-415` 队列项封面 |
| AOSP 蓝牙 | `avrcp/AvrcpCoverArtService.java` / `AvrcpCoverArtStorage.java` | `:152-156` / `:58-89` 按图像 hash 去重下发 handle |
| media3 | `legacy` 元数据出入口 | `MediaSessionLegacyStub:1753-1760` 元数据更新、`PlayerWrapper:759-766` `COMMAND_GET_METADATA` 门禁、`LegacyConversions:736-741` 写 ART_URI |
| 本仓库 | `LyricTitlePlayer.kt` | 全文（方案落点） |
| 本仓库 | 单测 `LyricTitlePlayerTest.kt` | 12 例 |
| 本仓库 | `CarLyricTitleController.kt` | `:146-152`/`:226-252` 恢复出口、`:211-261` tick、`:429-441` publish |
| 本仓库 | `MusicService.kt` | `:309-345` wrapper 链与 player 替换、`:785-803` 控制器启动 |
