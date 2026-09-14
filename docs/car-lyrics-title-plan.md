# 车机歌词标题（蓝牙 AVRCP）

> 状态：**已完整实现**（真实歌词 + 蓝牙 A2DP 门控 + 设置开关，默认关闭），已在模拟器上端到端验证（见 §9）。
> 相关代码：`data/service/player/LyricTitlePlayer.kt`、`data/service/player/CarLyricTitleController.kt`、`data/service/MusicService.kt`、`utils/LyricsTimelineUtils.kt`

## 1. 需求与来源

**需求**：蓝牙车机播放时，让车机界面上那一行媒体标题跟着歌词滚动，而不是固定显示歌曲名。

**来源观察**：这个交互首次见于**汽水音乐**——其车机端标题栏会随进度滚动当前歌词行。它并不是车机原生能力，而是手机端 App 主动改写了出站媒体标题。本方案是同一思路的独立实现。

## 2. 结论摘要

可行。机制是**动态改写出站 `MediaMetadata` 的 `TITLE`**。

车机上那行文字的唯一来源是 Android `MediaSession` 的 `playerInfo.mediaMetadata`；而蓝牙 AVRCP 协议本身**没有**歌词字段（见 §4），所以所有实现这个效果的 App 都是在借用 Title 字段。

本项目地基很好：歌词子系统、「当前行」算法、出站 metadata 改写层三者都已存在，缺的只是把它们接起来，并解决"**如何让下游知道要更新**"这一问题。

## 3. 机制与源码证据

### 3.1 出站链路

```
MusicService（serviceScope，长生命周期）
   ├─ 采样播放进度 currentPosition
   ├─ 取同步歌词 SyncedLine.time
   └─ 解析当前行 resolveCurrentLineIndex(lines, position)
            ↓
   LyricTitlePlayer（最外层包装）→ 改写 metadata.title
            ↓
   MediaSession.playerInfo.mediaMetadata
       ├─→ 蓝牙 AVRCP → 车机显示
       └─→ 通知栏 / Windows SMTC
```

### 3.2 关键约束：Media3 的 metadata 是事件驱动推送

这是全案唯一的高风险点。`androidx.media3:media3-session:1.11.0`，`MediaSessionImpl.java` L2249：

```java
@Override
public void onMediaMetadataChanged(MediaMetadata mediaMetadata) {
  @Nullable MediaSessionImpl session = getSession();
  if (session == null) return;
  session.verifyApplicationThread();
  @Nullable PlayerWrapper player = this.player.get();
  if (player == null) return;
  session.playerInfo = session.playerInfo.copyWithMediaMetadata(mediaMetadata);
  session.onPlayerInfoChangedHandler.sendPlayerInfoChangedMessage(true, true);
  session.dispatchRemoteControllerTaskToLegacyStub(
      (callback, seq) -> callback.onMediaMetadataChanged(seq, mediaMetadata));
}
```

三点结论：

1. `session.playerInfo.mediaMetadata` **只在 `Player.Listener.onMediaMetadataChanged` 触发时**才更新；
2. 推送目标是 **legacy stub**（`android.media.session.MediaSession` 兼容层），而蓝牙 AVRCP 栈读的正是这条通道；
3. 因此，**只覆盖 `ForwardingPlayer.getMediaMetadata()` 让车机看到新标题是不可行的**——下游根本不会收到刷新通知。

### 3.3 现有可复用资产

| 需要的东西 | 现状 | 位置 |
|---|---|---|
| 带时间轴歌词 | 已有（LRCLIB / 音频内嵌标签 / 本地 `.lrc`/`.ttml`） | `data/repository/LyricsRepositoryImpl.kt` |
| 当前歌词行 | 已有，但只在 Composable 内使用 | `presentation/components/LyricsSheet.kt:1977` `resolveCurrentLineIndex()` |
| 出站 metadata 改写层 | 已有（`ForwardingPlayer`，原本用于 artwork URI） | `data/service/player/MappingPlayer.kt` |
| MediaSession | 已有 | `MusicService : MediaSessionService`，session 建于 `MusicService.kt:703` |
| metadata 构造 | 已有 | `utils/MediaItemBuilder.kt:284` `buildMediaMetadataForSong()` |
| 长生命周期订阅点 | 已有 | `MusicService.serviceScope`（L199）+ `playerListener`（L398） |
| 设置开关样板 | 已有 | `PreferencesKeys` 的 `LYRICS_*` / `IMMERSIVE_LYRICS_ENABLED` |

覆盖面注意：Navidrome 服务端有 `getLyrics` 但**未接入** `LyricsRepository`；Jellyfin 无歌词接口；云端 AI 歌词在 OSS 版本已剥离。功能上限由"本地能拿到多少同步歌词"决定。

### 3.4 包装链

`MusicService.wrapFadingPlayer()` 返回的包装链，以及 MediaSession 实际持有的实例：

```
LyricTitlePlayer(  ← MediaSession 持有这一层，必须是它
  MappingPlayer(   ← 本来就存在的 artwork URI 映射层
    FadingPlayer(
      engine.masterPlayer
    )))
```

两个必须注意的点：

- **`LyricTitlePlayer` 必须在最外层**。MediaSession 注册的 listener 落在它上面，伪造的事件也发给这些 listener；如果它被包在里面，事件到不了 MediaSession。
- **包装链会被重建**。`publishMediaSessionPlayer()` 在交叉淡入淡出切换 display player 时会重新调用 `wrapFadingPlayer()`，所以实例引用要存在字段（`lyricTitlePlayer`）里，不能假设只创建一次。同时 `unwrap*()` 解包链要补上新的一层，否则 `publishMediaSessionPlayer` 里"解包后是否是同一个 player"的判断会失效。

### 3.5 附带结论：发的是 `Now Playing Content Changed`(0x09)，不是 `Track Changed`(0x02)

车主视角的常见顾虑是"改标题会不会被车机当成换曲、把进度条清零"。用 AOSP 源码判定：**不会**——因为我们只换 metadata、从不碰队列项 id。

证据链（`LineageOS/android_packages_modules_Bluetooth`，分支 `lineage-23.2`，即 Android 16 的蓝牙模块；该模块已从 `packages/apps/Bluetooth` 迁出）：

1. `avrcp/AvrcpTargetService.java` L175 `ListCallback implements MediaPlayerList.MediaUpdateCallback` —— Java 层**只有一个**出站入口，没有独立的 track / now-playing 方法：

   ```java
   public void run(MediaData data) {
       boolean metadata = !Objects.equals(mCurrentData.metadata, data.metadata);
       boolean state = !MediaPlayerWrapper.playstateEquals(mCurrentData.state, data.state);
       boolean queue = isQueueUpdated(mCurrentData.queue, data.queue);
       Log.d(TAG, "onMediaUpdated: track_changed=" + metadata + " state=" + state + " queue=" + queue);
       mCurrentData = data;
       mNativeInterface.sendMediaUpdate(metadata, state, queue);
   }
   ```

2. 三个标志位**互相独立**：`metadata` 由 metadata 内容差异驱动，`queue` 由 `isQueueUpdated()` 单独判定。我们的改写只让 `metadata=true`，`queue` 恒为 `false`。
3. `avrcp/AvrcpNativeInterface.java` L143 `sendMediaUpdate(boolean metadata, boolean playStatus, boolean queue)` 把三个布尔转交原生层，由原生层依 `queue` 决定是否发 `TRACK_CHANGED` 并轮换 UID。

两点补充：

- AOSP 自己的日志把 metadata 差异打印成 `track_changed=`，命名有误导性；真正决定"换曲"语义的是 `queue` 这一路。**这正是必须保持 `mediaId` 不变的原因**——一旦走 `replaceMediaItem`（路线 A），`queue` 随之变化，就会真的触发 `Track Changed`。
- **残余风险（无法离线判定）**：对端是否注册了 `EVENT_NOW_PLAYING_CONTENT_CHANGED`(0x09)。没注册的设备收不到这次刷新，标题会停在旧值直到真正换曲。这属于车机侧行为，真机 `btsnoop` 可复核（见 §11.1）。

## 4. AVRCP 到底能不能携带歌词

**不能。**

标准 `GetElementAttributes`（PDU ID 0x20）只定义以下 media attribute ID：

| ID | 含义 | ID | 含义 |
|---|---|---|---|
| 0x0001 | Title | 0x0005 | Total number of tracks |
| 0x0002 | Artist | 0x0006 | Genre |
| 0x0003 | Album | 0x0007 | Playing time |
| 0x0004 | Track number | 0x0008 | Default cover art（AVRCP 1.6 新增） |
| 其余 | Reserved | | |

依据：

- Linux 内核 `monitor/avctp.c` 的 `AVRCP_MEDIA_ATTRIBUTE_*` 定义：`TITLE=0x01`、`ARTIST=0x02`、`ALBUM=0x03`、`TRACK=0x04`、`TOTAL=0x05`、`GENRE=0x06`、`DURATION=0x07`，`default: return "Reserved"`。
- Silicon Labs AN986（A2DP/AVRCP 应用笔记）给出的 attribute_id 表与之完全一致，并列出 event ID：`0x02 Track changed`、`0x09 Now playing changed`。

> 网上有资料称"0x003E 为歌词文本"——查无实据，且与内核实现和规范均不符（该说法连 Title/Artist 的 ID 都对反了）。不要采信。

推论：

1. 想在车机上看到歌词，**只能借用 Title 字段**（或 Artist / Album / Genre 三个文本位）。汽水音乐等 App 走的都是这条路。
2. 真正的"车机端渲染歌词"只有投屏类协议做得到——Android Auto / CarPlay / HiCar / CarLife 走 app 层 UI 投影（USB 或 WiFi），不是蓝牙 AVRCP。
3. **实现约束**：AOSP 蓝牙栈只把 `MediaMetadata` 的固定字段映射成上述 attribute，`MediaMetadata.extras` 里的自定义 key **不会被转发**。所以可用文本位只有 `TITLE` / `ARTIST` / `ALBUM_TITLE` / `GENRE` 四个，别指望私有通道。

## 5. 两条实现路线

### 路线 A：替换播放项触发（确定可行，但有副作用）

```kotlin
player.replaceMediaItem(index, item.buildUpon()
    .setMediaMetadata(md.buildUpon().setTitle(currentLine).build()).build())
```

- 优点：一定能触发 `onMediaMetadataChanged`。
- 代价：同时触发 `onMediaItemTransition`。而 `MusicService.kt:1161` 的该方法**完全不判断 `reason`**，会连带执行：
  - `syncLocalListeningStatsFromPlayer(forceNewSession = true)` → 重复记听歌
  - `reportNavidromePlayback("starting")` → 重复 scrobble
  - `playbackTimerController.handleMediaItemTransition` → 计时器被重置
  - `schedulePlaybackSnapshotPersist()` → 每秒写一次播放快照
  - `replayGainProcessor.apply()` → 可能音量抖动
- 结论：要走 A 必须先给 `onMediaItemTransition` 加 reason / 来源守卫，改动面大，回归风险集中在听歌统计与 Navidrome 上报。

### 路线 B：伪造 metadata 事件（零副作用，本次采用）

自己维护一份 listener 引用，直接对它们调用 `onMediaMetadataChanged`，**完全不碰 inner player 的 playlist**。

- 优点：零副作用，不需要给 `onMediaItemTransition` 加守卫。
- 代价：依赖 Media3 未明确承诺的行为（callback 参数即缓存值、listener 可被直接调用），所以必须先验证——已验证通过（§9）。

## 6. 实现说明

### 6.1 `LyricTitlePlayer`

`data/service/player/LyricTitlePlayer.kt`，`ForwardingPlayer` 子类，三件事：

1. 覆盖 `addListener` / `removeListener`，在转发给 inner player 的同时**额外持有一份 listener 引用**（`CopyOnWriteArrayList`）。
2. 覆盖 `getMediaMetadata()`，返回 `metadataOverride ?: super.getMediaMetadata()`。
3. `publishMetadataOverride(override)`：记录覆盖值，然后对每个 listener 直接调用 `onMediaMetadataChanged(当前 mediaMetadata)`。传入 `null` 表示恢复原始 metadata。

注意点：

- 必须在**应用线程**调用，因为 `MediaSessionImpl` 在回调里会 `verifyApplicationThread()`。
- 直接调用绕过了 Media3 的 `ListenerSet`，这对 `MediaSession` 有效（它实现了 `onMediaMetadataChanged`），但对只实现 `onEvents` 的监听者无效——本项目里没有这种消费者。
- `metadataOverride` 在换歌时必须清空，否则旧歌的歌词会盖到新歌上。
- 内部用 `if (metadataOverride == override) return` 去重，天然满足"仅在文本变化时推送"。

### 6.2 Service 接入

- 新增字段 `lyricTitlePlayer: LyricTitlePlayer?`（最外层包装实例）与 `carLyricTitleController: CarLyricTitleController?`
- `wrapFadingPlayer()` 在最外层再包一层并记录实例
- 新增 `Player.unwrapLyricTitlePlayer()`，并把 `publishMediaSessionPlayer()` 里的解包链改为 `unwrapLyricTitlePlayer().unwrapMappingPlayer().unwrapFadingPlayer()`
- `onCreate` 的 `serviceScope` 里调用 `startCarLyricTitle()`
- `isCarLyricTitleOutputActive()` / `hasBluetoothA2dpOutput()`：输出路由判定（见 §6.4）

### 6.3 运行期驱动：`CarLyricTitleController`

`data/service/player/CarLyricTitleController.kt`。单独成类而不是塞进 `MusicService`，是因为它有自己的跨歌状态（当前歌曲、歌词行、上次推送的行、路由缓存），放进 Service 字段会继续膨胀那个已有 30+ 播放状态字段的类。

两个协程：

1. **订阅开关**（`carLyricTitleEnabledFlow`）：值变化时更新 `enabled`；**关闭时立即** `publishMetadataOverride(null)`，不等下一个 tick——否则车机会残留上一行歌词直到换歌。
2. **轮询**（`POLL_INTERVAL_MS = 500ms`）：每 tick 依次判定，任一不满足就回收覆盖值并记录原因：

| 判定 | 不满足时 |
|---|---|
| 开关已打开 | 恢复原标题 |
| 当前输出是蓝牙 A2DP | 恢复原标题 |
| 已加载歌曲（`mediaId` 能查到 Song） | 恢复原标题 |
| 该歌有**同步**歌词（`synced` 非空） | 恢复原标题 |

全部通过后取 `resolveCurrentLineIndex(lines, position + syncOffsetMs)`，**仅当行文本变化时**才推送。

其他设计点：

- **换歌处理**：`mediaId` 变化时立刻清覆盖值 + 取消上一个歌词加载任务，再异步加载新歌歌词，避免上一首的行短暂盖在新歌标题上。
- **歌词来源**：`musicRepository.getLyrics(song)`，默认 `EMBEDDED_FIRST`（内嵌标签 → 远程 API → 本地 `.lrc`）。冷缓存时可能走网络，与打开歌词页行为一致；结果会持久化，之后离线可命中。
- **同步偏移**：叠加用户为这首歌设置的 `lyricsSyncOffset`，保证车机与 App 内歌词页显示同一行。
- **线程**：tick 全程在 `Dispatchers.Main.immediate`（`publishMetadataOverride` 必须在应用线程）；歌词加载在 IO 上，不阻塞轮询。
- **去抖**：`LyricTitlePlayer` 内部的 `metadataOverride == override` 比较**不可靠**——`MediaMetadata` 内嵌 `Bundle`，新构造的 `Bundle` 不保证相等。所以 `lastPublishedLine` 是必需的。
- **路由判定缓存**：每 2s 重算一次（`AudioManager.getDevices` 是 binder 调用，不必每 500ms 问一次）。
- **异常隔离**：tick 外层 `runCatching`，单次失败不至于杀死循环——否则功能会静默失效，用户侧毫无提示。

### 6.4 门控规则：什么时候才真的启用

三个条件同时满足才改写标题：

1. **设置开关打开**（默认关）；
2. **当前输出是蓝牙 A2DP 设备**（`AudioManager.getDevices(GET_DEVICES_OUTPUTS)` 含 `TYPE_BLUETOOTH_A2DP`）；
3. **当前歌曲有同步歌词**。

第 2 条为什么必要：覆盖值对**所有** `MediaSession` 消费者可见，不只是车机——通知栏、锁屏、Windows SMTC 读的是同一份 `MediaMetadata`。没有这条，插着有线耳机看手机时，歌曲标题的位置也会变成歌词。

关于「**车机是否支持**」：Android 没有公开 API 查询对端的 AVRCP 版本，而且**不需要**——不支持元数据的车机根本不会发 `GetElementAttributes`，覆盖值对它没有任何影响。所以"设备是否支持"在手机侧既无法探测、也不必探测；能探测且必须探测的是"我们自己是否在走蓝牙输出"。

> 调试便利：debug 构建额外识别全局设置 `pixelplayer_car_lyric_title_force_a2dp`
> （`adb shell settings put global pixelplayer_car_lyric_title_force_a2dp 1`），用于在没有 A2DP 消费端的模拟器上验证。release 构建忽略它。

### 6.5 行解析工具提取

`resolveCurrentLineIndex` / `resolveLineEndTimeMs` 原本是 `LyricsSheet.kt` 里的 `internal` 顶层函数，只有 Composable 侧能用。因为 `data` 层也需要，移到 `utils/LyricsTimelineUtils.kt`（`data` 反向依赖 `presentation` 是架构坏味道），逻辑一字未改；`LyricsSheet.kt` 与既有 `LyricsSheetLogicTest` 改为 import。新增 `LyricsTimelineUtilsTest` 覆盖边界：空时间轴、首行之前、行区间映射、末行之后、seek 回跳、逐字时间晚于下一行起点。

> 早期版本曾用 `[SPIKE] m:ss` 占位载荷来验证推送通路（§9.1 记录的就是那一版），现已替换为真实歌词行，占位代码已删除。

## 7. 设置开关（已落地）

按 AGENTS.md 的"新增设置开关五步"接线，**默认关闭**（标题是每个已配对设备都可见的字段，必须显式选择加入）：

| # | 文件 | 变更 |
|---|---|---|
| 1 | `data/preferences/UserPreferencesRepository.kt` | `PreferencesKeys.CAR_LYRIC_TITLE_ENABLED`（key `car_lyric_title_enabled`）→ `carLyricTitleEnabledFlow` → `setCarLyricTitleEnabled()` |
| 2 | `presentation/viewmodel/SettingsViewModel.kt` | `SettingsUiState.carLyricTitleEnabled`、`SettingsUiUpdate.Group2` 字段、combine 流列表**末尾**追加（`values[17]`）、`state.copy()`、`setCarLyricTitleEnabled()` |
| 3 | `presentation/screens/SettingsCategoryScreen.kt` | PLAYBACK 分类新增 `设置子节「车载蓝牙」` + `SwitchSettingItem`，highlight key `item_playback_car_lyrics` |
| 4 | `presentation/settings/search/SettingsRegistry.kt` | `SettingSpec(id = "playback_car_lyrics")`，关键词 `car / bluetooth / avrcp / lyrics / title / head unit / scroll` |
| 5 | `res/values/strings_settings.xml` + `res/values-zh-rCN/strings_settings.xml` | `setcat_car_lyrics_section` / `_title` / `_subtitle`，中英成对 |
| 6 | `res/drawable/rounded_directions_car_24.xml` | 新增图标（Material Symbols Rounded `directions_car`） |

位置：**设置 → 播放 → 车载蓝牙 → 标题显示歌词**。放在 PLAYBACK 而不是 APPEARANCE 的「歌词页面」子节下，因为这是输出通道行为，与「耳机重连续播」同类。

## 8. 测试分层（不需要车机即可覆盖大部分）

车机只是 MediaSession 的一个消费者，所以大部分行为可以在本地验证。

| 层级 | 目的 | 方法 |
|---|---|---|
| L1 单测 | 行解析正确性 | `resolveCurrentLineIndex`：行间空隙、seek 回跳、position 超末行、空列表 |
| L2 状态可见性 | **最接近车机视角，零成本** | `adb shell dumpsys media_session \| grep -A 25 com.lostf1sh`，看 `metadata: ... title=...` |
| L3 AVRCP 协议真相 | 是否被车机误判为换曲 | 开发者选项启用"蓝牙 HCI 信息收集日志" → 连蓝牙设备播放 → `adb pull /sdcard/btsnoop_hci.log` → Wireshark（原生解析 AVRCP）过滤 `btavrcp`，观察 `Track Changed`(0x02) 与 `Now playing changed`(0x09) 的次数与间隔 |
| L4 端到端显示 | 肉眼确认 | Windows SMTC（笔记本当车机）/ Android Auto DHU（走 USB+MediaBrowser，非 AVRCP）/ 真车机 |

L2 最值得先做——**它直接观察的就是车机将要读到的那一份数据**，成本几乎为零。

## 9. 验证记录

### 9.0 测试环境与过程

设备与产物：

| 项 | 值 |
|---|---|
| 目标 | `pixel6` AVD（`x86_64`，android-34），`-no-window -no-snapshot-load -no-boot-anim -no-audio -gpu swiftshader_indirect` |
| APK | `pixelplayeross-universal-0.4.1-pisces.1-debug.apk` |
| 为什么是 universal | 项目默认只出 `arm64-v8a`，`x86_64` 模拟器装不上；`-Ppixelplayer.enableAbiSplits=false` 会把同目录的 arm64 产物一起清掉，所以**先备份、验证后重建** |
| 安装 | `adb install -r -d <apk>`（`-d` 允许同版本号覆盖安装） |

无头交互（没有窗口，全靠 adb）：

1. 启动 App：`adb shell monkey -p com.lostf1sh.pixelplayeross.debug -c android.intent.category.LAUNCHER 1`
2. 定位控件：`adb shell uiautomator dump /sdcard/ui.xml` → `tr '<' '\n<'` → grep `text=` / `checkable="true"` 取 `bounds` → `input tap`。
   **开关的判据是 `checkable="true"` 节点的 `checked` 属性**，不能靠类名——dump 出来是 `android.view.View`。
3. 滚动：`input swipe 540 1600 540 500 300`（上下若干次直到目标可见）。

三个观察点：

| 观察点 | 命令 | 期望 |
|---|---|---|
| 车机视角的数据 | `dumpsys media_session` 的 `metadata: size=…, description=<title>, …` | 与开关状态一致 |
| 队列是否被动过 | 同一份 dump 的 `active item id=` / `queueTitle=null, size=` | 全程不变 |
| 推送频率 | `adb logcat -d -s MusicService_PixelPlayer \| grep "car lyric title"` | 开关关闭时无推送行 |

> `dumpsys media_session` 之所以能代表车机：蓝牙 AVRCP 的 `GetElementAttributes` 由 AOSP 蓝牙栈从 `MediaSession` 的 legacy stub 读**同一份** `MediaMetadata`（§3.1）。

| # | 待验证假设 | 方法 | 结论 |
|---|---|---|---|
| 1 | MediaSession 接受伪造的 `onMediaMetadataChanged`（路线 B 可行性） | 模拟器 pixel6（x86_64）+ universal debug APK，`dumpsys media_session` 观察 | **通过** |
| 2 | 开关默认关闭时标题保持原样、打开后才改写 | 同上，切换设置开关并对比 `dumpsys` | **通过**（见 §9.2） |
| 3 | `replaceMediaItem` 是否真的触发 `onMediaItemTransition`（路线 A 的守卫范围） | 打日志 + 播放观察统计是否被重复记录 | 未验证（走 B 则不需要） |
| 4 | 蓝牙栈在 mediaId 不变、仅 metadata 变化时发的是 `Now playing changed` 还是 `Track changed` | 真机 + 蓝牙设备抓 btsnoop | 未验证（模拟器无蓝牙音频链路） |
| 5 | 真实歌词行按时间轴推进（端到端） | 45s 曲目 + 8 行 LRC（每 3s 一行），连续采样 `dumpsys` 对照 `position` | **通过**（见 §9.3） |
| 6 | 三者同时满足才启用：开关开 **且** 蓝牙输出 **且** 有同步歌词 | 增删 debug 路由标志 / 切换开关 / 有歌词与无歌词曲目对照 | **通过**（见 §9.3） |

### 9.1 假设 1：通过

环境：`pixel6` AVD（x86_64，android-34）、universal debug APK（`-Ppixelplayer.enableAbiSplits=false`）、`-no-window -no-audio`。

`adb shell dumpsys media_session` 抓到的 session：

```
package=com.lostf1sh.pixelplayeross.debug
active=true
controllers: 4
state=PlaybackState {state=STOPPED(1), position=17936, ..., active item id=88}
metadata: size=12, description=[SPIKE] idle #7, <unknown>, 1
queueTitle=null, size=89
```

logcat（`MusicService_PixelPlayer`）同步显示埋点每秒推送，且运行在主线程（tid 与 pid 相同）：

```
09-14 00:51:09.528  4263  4263 D MusicService_PixelPlayer: car lyric title spike: [SPIKE] idle #2
09-14 00:51:10.533  4263  4263 D MusicService_PixelPlayer: car lyric title spike: [SPIKE] idle #3
...
09-14 00:51:14.567  4263  4263 D MusicService_PixelPlayer: car lyric title spike: [SPIKE] idle #7
```

结论与解读：

1. 伪造的 `onMediaMetadataChanged` 被 MediaSession **接受**：`metadata.description` 变成了埋点写入的值，即 `session.playerInfo.mediaMetadata` 确实被更新。这一份数据正是蓝牙 AVRCP 栈的读取源。
2. **队列未被触碰**：`active item id=88`、`size=89` 在推送期间保持不变；`PlaybackState` 也未出现 transition 引发的抖动。代码层面同样可证——`publishMetadataOverride()` 只调用 listener 回调，不对 inner player 做任何 playlist 操作，所以不可能产生 `onMediaItemTransition` 及其副作用。这正是路线 B 相对路线 A 的核心优势。
3. 回调必须在应用线程：埋点日志的 pid/tid 相同（4263/4263），满足了 `MediaSessionImpl` 里的 `verifyApplicationThread()`。
4. **本次验证覆盖不到的部分**：模拟器没有蓝牙音频链路（`BluetoothMediaBrowserService` 的 session 一直是 `error=Bluetooth audio disconnected`），所以「蓝牙栈实际发出的是 `Track changed` 还是 `Now playing changed`」「是否被车机误判为换曲」仍需真机 + 蓝牙设备抓包验证（假设 4）。

### 9.2 假设 2：通过（开关门控）

同一环境，用 UI 自动化（`uiautomator dump` 定位 + `input tap`）在**设置 → 播放 → 车载蓝牙 → 标题显示歌词**上切换开关，每一步都对比 `dumpsys media_session`：

| 步骤 | 开关节点 | `metadata.description` | `car lyric title spike` 日志 |
|---|---|---|---|
| 默认（未动开关） | `checked="false"` | `雨落哗声-安安_豆包_20260620-171126`（真实曲名） | **0 条** |
| 打开开关 | `checked="true"` | `[SPIKE] idle #9` | 每秒 1 条，编号连续递增 |
| 关闭开关（T+3s） | `checked="false"` | `雨落哗声-安安_豆包_20260620-171126` | 停在 `#16` |
| 关闭后 T+8s | `checked="false"` | 仍为真实曲名 | 无新增 |

三条结论：

1. **默认关闭是真的**：全新安装（未触碰开关）时标题是真实曲名，埋点日志一条都没有——发布循环在 `enabled == false` 时整轮跳过，不产生任何推送。
2. **开启后标题被改写**：`metadata.description` 变成 `[SPIKE] ...`，说明开关 → 偏好项 → 控制器标志 → 轮询 → `publishMetadataOverride()` 全链路连通。
3. **关闭立即恢复**：T+3s 时（早于"等下一个 tick 才清理"的做法）标题已恢复为真实曲名，且有日志停在 `#16` 佐证推送已停止。这验证了 §6.3 里"关闭时立刻 `publishMetadataOverride(null)`"的必要性——否则车机会残留上一行歌词直到换歌。

**队列仍然零改动**：整个过程 `active item id=88` 保持不变，与假设 1 的结论一致。

**设置搜索**：在设置搜索框输入 `avrcp`（只在 `SettingsRegistry` 的 `keywordsStatic` 里出现），返回结果为「播放 › 标题显示歌词」+ 其副标题，确认第 4 步注册生效。

### 9.3 真实歌词 + 蓝牙门控：通过

这一轮把占位载荷换成真实歌词行，并补上"A2DP 输出"门控。模拟器**没有 A2DP 消费端**，因此用 debug 专用标志 `pixelplayer_car_lyric_title_force_a2dp` 伪造路由（见 §6.4）。

**准备一首带同步歌词的曲目**。模拟器曲库里的本地文件，歌词有三个可能来源，最终选了最稳的一条：

| 方案 | 结果 |
|---|---|
| 同目录同名 `.lrc` | ✗ App 未声明 `MANAGE_EXTERNAL_STORAGE`，targetSdk 37 的分区存储下读不到非媒体文件（`appops set` 也补不上：未声明的权限无法授予） |
| 内嵌标签 | ✓ ffmpeg 写 ID3v2 `USLT`（`-metadata lyrics="[mm:ss.xx]..."`），TagLib 读出 `LYRICS` 键，`LyricsUtils.parseLyrics` 解析成逐行时间轴 |
| Room `songs.lyrics` 字段 | ✓ 直接 `UPDATE`，不动文件、不改 id（最终采用） |

**踩坑：MediaStore 的 id 不稳定。** 用内嵌标签方案覆盖 mp3 后，MediaStore 把它当成"删除 + 新增"，id 随之变化；而播放队列里的项仍持有旧 id（如 `1000001209`），于是 `getSong(mediaId)` 查不到 → 走"无歌曲"降级。这不是缺陷，而是"恢复历史队列快照 + 曲库重扫"的正常组合，且降级行为正确（保持原标题）。为了把"有歌词"这一环验证稳，最终改为写 Room 字段，避免触碰文件：

```bash
# 给 id=1000001891 的曲目写入 8 行 LRC（每 3 秒一行）
# char(10) 拼换行，绕开 adb shell 的转义问题
adb shell "run-as com.lostf1sh.pixelplayeross.debug sqlite3 databases/pixelplayer_database \
  \"update songs set lyrics = '[00:00.00]CARLYRIC alpha' || char(10) || '[00:03.00]CARLYRIC bravo' ... where id = 1000001891\""
```

**正路径**（开关开 + 路由标志开 + 有同步歌词）：从搜索结果点播该曲目，采样 `position` 与 `metadata.description`：

| `position` | `metadata.description` |
|---|---|
| 3241 | `CARLYRIC bravo` |
| 6162 | `CARLYRIC charlie` |
| 9090 | `CARLYRIC delta` |
| 12241 | `CARLYRIC echo` |
| 15136 | `CARLYRIC foxtrot` |
| 18033 | `CARLYRIC golf` |
| 21225 | `CARLYRIC hotel` |

日志时间戳间隔恰好 ~3.0s，与 LRC 时间轴一致：

```
01:34:55.300  car lyric title: CARLYRIC alpha
01:34:57.819  car lyric title: CARLYRIC bravo
01:35:00.838  car lyric title: CARLYRIC charlie
01:35:03.853  car lyric title: CARLYRIC delta
```

**路由门控实时生效**（播放中直接增删标志，无需重启 App）：

| 操作 | 日志 | `metadata.description` |
|---|---|---|
| `settings delete`（≈蓝牙断开） | `bluetooth output inactive` → `idle: bluetooth output not active` | 恢复为 `Night Tone` |
| `settings put ... 1`（≈蓝牙连上） | `bluetooth output active` → `active: 8 synced lines` | 回到 `CARLYRIC hotel`（当前行） |

重新连上时**没有重新加载歌词**（复用已加载的 8 行），缓存路径也正确。

**开关门控**（在正在播放有歌词曲目的状态下切换）：

| 开关 | 日志 | `metadata.description` |
|---|---|---|
| 关 | `toggle off` → `idle: toggle off` | `Night Tone`（真实曲名） |
| 开 | `toggle on` → `active: 8 synced lines` | `CARLYRIC hotel` |

**无歌词曲目**：切到没有歌词的曲目，日志 `loaded 0 synced lines` → `idle: no synced lyrics`，标题保持真实曲名。

**全程队列零改动**：`active item id` 与队列长度始终不变，与 §9.1 结论一致。

**排查记录（一个值得留下的失败）**：第一版实现把路由缓存哨兵写成 `lastRoutingCheckUptimeMs = Long.MIN_VALUE`，判定式为 `now - lastRoutingCheckUptimeMs < INTERVAL` —— `now - Long.MIN_VALUE` **溢出**成负数，判定恒为真，"缓存值 false"被永久返回，**路由检查一次都没执行过**。表象极具迷惑性：门控"看起来正常工作"（标题确实没被改），实际机制根本没跑。改成 `0L` 初值、并把缓存变量改为可空类型（使首次结果必定打日志）之后才暴露。教训：拿极值当哨兵并参与减法之前，先想溢出。

### 复现步骤

```bash
# 1. 模拟器是 x86_64，arm64-only 的 APK 装不上，必须构 universal
#    （会清掉同目录的 arm64 产物，验证完记得重建 arm64）
./gradlew :app:assembleDebug -Ppixelplayer.enableAbiSplits=false

# 2. 装 & 授权 & 启动
adb install -r -d app/build/outputs/apk/debug/pixelplayeross-universal-*.apk
adb shell pm grant com.lostf1sh.pixelplayeross.debug android.permission.READ_MEDIA_AUDIO
adb shell monkey -p com.lostf1sh.pixelplayeross.debug -c android.intent.category.LAUNCHER 1

# 3. 打开开关：设置 → 播放 → 车载蓝牙 → 标题显示歌词
#    无头环境下先 dump 拿到标题的 bounds，switch 与标题同一垂直位置
adb shell input tap 970 213                   # 右上角齿轮
adb shell input tap 500 1234                  # 「播放」分类
adb shell input swipe 540 1600 540 500 300    # 向下滚 ×3
adb shell input swipe 540 700 540 1300 300    # 回滚一点，让该行进入可视区
adb shell uiautomator dump /sdcard/ui.xml && adb shell cat /sdcard/ui.xml | tr '<' '\n<' \
  | grep -oE 'text="标题显示歌词"[^/]*bounds="[^"]+"'
adb shell uiautomator dump /sdcard/ui.xml && adb shell cat /sdcard/ui.xml | tr '<' '\n<' \
  | grep 'checkable="true"' | grep -oE 'checked="[^"]+"[^/]*bounds="[^"]+"'
adb shell input tap 927 789                   # 与标题同一垂直位置的 switch

# 4. 伪造蓝牙 A2DP 路由（仅 debug 构建识别；模拟器没有 A2DP 消费端，见 §6.4）
adb shell settings put global pixelplayer_car_lyric_title_force_a2dp 1

# 5. 观察车机视角的数据 + 门控日志
adb shell dumpsys media_session | grep -A 12 com.lostf1sh | grep -E "metadata:|active item"
adb logcat -s MusicService_PixelPlayer | grep "car lyric title"

# 6. 给某首歌注入同步歌词（不动文件，id 稳定），再播放它
#    char(10) 拼换行以避开 adb shell 的转义问题
adb shell "run-as com.lostf1sh.pixelplayeross.debug sqlite3 databases/pixelplayer_database \
  \"update songs set lyrics = '[00:00.00]L0' || char(10) || '[00:03.00]L1' || char(10) || '[00:06.00]L2' \
     where id = <songId>\""

# 7. 反向验证：删掉门控标志应立刻恢复真实曲名
adb shell settings delete global pixelplayer_car_lyric_title_force_a2dp
```

## 10. 改动文件清单（截至本文档）

| 文件 | 状态 |
|---|---|
| `data/service/player/LyricTitlePlayer.kt` | 新增（出站 metadata 覆盖层） |
| `data/service/player/CarLyricTitleController.kt` | 新增（门控 + 歌词行 → 标题） |
| `utils/LyricsTimelineUtils.kt` | 新增（`resolveCurrentLineIndex` / `resolveLineEndTimeMs` 从 `LyricsSheet.kt` 移入） |
| `data/service/MusicService.kt` | 改（包装链 + 解包链 + 启动控制器 + A2DP 路由判定 + debug 逃生标志） |
| `data/preferences/UserPreferencesRepository.kt` | 改（偏好项） |
| `presentation/viewmodel/SettingsViewModel.kt` | 改（UiState / Group2 / 开关方法） |
| `presentation/screens/SettingsCategoryScreen.kt` | 改（PLAYBACK 子节 + 开关 UI） |
| `presentation/settings/search/SettingsRegistry.kt` | 改（设置搜索注册） |
| `presentation/components/LyricsSheet.kt` | 改（行解析改为 import utils） |
| `res/values/strings_settings.xml`、`res/values-zh-rCN/strings_settings.xml` | 改（中英成对） |
| `res/drawable/rounded_directions_car_24.xml` | 新增 |
| `test/.../utils/LyricsTimelineUtilsTest.kt` | 新增（行解析边界） |
| `test/.../presentation/components/LyricsSheetLogicTest.kt` | 改（改 import） |

## 11. 未完成 / 后续

1. **真机复核（已降级为可选）**：「改标题会不会被当成换曲」已由 AOSP 源码判定——只改 metadata 时 `queue=false`，不会触发 `Track Changed`(0x02)，故不会重置进度条，见 §3.5。**仍无法离线确认的只有对端行为**：车机是否注册了 `0x09`；没注册则收不到本次刷新。复核方法：开发者选项开启「蓝牙 HCI 信息收集日志」→ `adb pull /sdcard/btsnoop_hci.log` → Wireshark 过滤 `btavrcp`，看是否出现 `Now Playing Content Changed`。**顺带一提**：验证正路径不必等车机——任何蓝牙音频设备（耳机/音箱）都走同一条 A2DP 路径，标题同样会被改写。
2. **通知栏文案**：只改 `TITLE`，`ARTIST` 保持歌手名。曾考虑把 ARTIST 换成原曲名（车机两行都能用上），但通知栏与锁屏读的是同一份 metadata，会显示成「歌名 / 歌名」，得不偿失，故不采用。
3. **播放结束后**：实测标题停在最后一行歌词（歌曲播完 `state=STOPPED` 时仍是 `CARLYRIC hotel`）。若希望播完恢复曲名，在 `STATE_ENDED` 时清一次覆盖值即可。
4. **调试逃生口**：`pixelplayer_car_lyric_title_force_a2dp` 只在 debuggable 构建读取，release 忽略。不建议放开给 release。
5. **云端曲目的歌词**：Navidrome 有 `getLyrics` 但未接入 `LyricsRepository`，Jellyfin 无该接口 → 云端曲目基本拿不到歌词，会走"保持原标题"的降级路径。想支持的话要先把服务端歌词接进 `LyricsRepository`。
6. **无同步歌词的歌**：只有 `plain` 歌词不会启用（没有时间轴就无法定位当前行）。若将来想做"整段歌词滚动"，那是另一套切片机制。
