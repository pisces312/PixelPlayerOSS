# 车机歌词标题（蓝牙 AVRCP）

> 状态：**已实现并接入设置开关（默认关闭）**；核心机制已在模拟器验证通过（见 §9）。
> 相关代码：`data/service/player/LyricTitlePlayer.kt`、`data/service/MusicService.kt`（含临时占位载荷）

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

- 新增字段 `lyricTitlePlayer: LyricTitlePlayer?` 与 `@Volatile carLyricTitleEnabled: Boolean`
- `wrapFadingPlayer()` 在最外层再包一层并记录实例
- 新增 `Player.unwrapLyricTitlePlayer()`，并把 `publishMediaSessionPlayer()` 里的解包链改为 `unwrapLyricTitlePlayer().unwrapMappingPlayer().unwrapFadingPlayer()`
- 新增 `startCarLyricTitleSpike()`，在 `onCreate` 的 `serviceScope` 里启动

### 6.3 开关门控（本次新增）

`startCarLyricTitleSpike()` **仅在 debuggable 构建生效**（检查 `ApplicationInfo.FLAG_DEBUGGABLE`），并且受设置开关 `carLyricTitleEnabledFlow` 控制：

```kotlin
serviceScope.launch {
    userPreferencesRepository.carLyricTitleEnabledFlow.collect { enabled ->
        withContext(Dispatchers.Main.immediate) {   // 必须在应用线程
            carLyricTitleEnabled = enabled
            if (!enabled) lyricTitlePlayer?.publishMetadataOverride(null)  // 立即恢复原标题
        }
    }
}
```

- 开关**关闭时立即**清掉覆盖值，而不是等下一个 tick——车机不会残留上一行歌词。
- 发布循环每次 tick 检查 `carLyricTitleEnabled`，关闭时直接跳过，不做任何多余工作。
- `withContext(Dispatchers.Main.immediate)` 是必需的：DataStore 的 flow 不保证在主线程序列上发射，而 `publishMetadataOverride` 必须在应用线程调用。

### 6.4 占位载荷（临时）

当前 tick 推送的是 `[SPIKE] m:ss`（播放中）或 `[SPIKE] idle #n`（未播放），同时打日志：

```
MusicService_PixelPlayer  car lyric title spike: [SPIKE] 0:42
```

用播放进度而不是真实歌词，是为了让"标题是否真的被推送到下游"可被客观观察，并把变量降到最少。

**正式实现时把这段替换成"当前歌词行"即可，推送通路完全一致**（见 §11）。

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
| 推送频率 | `adb logcat -d -s MusicService_PixelPlayer \| grep "car lyric title spike"` | 开关关闭时为 0 条 |

> `dumpsys media_session` 之所以能代表车机：蓝牙 AVRCP 的 `GetElementAttributes` 由 AOSP 蓝牙栈从 `MediaSession` 的 legacy stub 读**同一份** `MediaMetadata`（§3.1）。

| # | 待验证假设 | 方法 | 结论 |
|---|---|---|---|
| 1 | MediaSession 接受伪造的 `onMediaMetadataChanged`（路线 B 可行性） | 模拟器 pixel6（x86_64）+ universal debug APK，`dumpsys media_session` 观察 | **通过** |
| 2 | 开关默认关闭时标题保持原样、打开后才改写 | 同上，切换设置开关并对比 `dumpsys` | **通过**（见 §9.2） |
| 3 | `replaceMediaItem` 是否真的触发 `onMediaItemTransition`（路线 A 的守卫范围） | 打日志 + 播放观察统计是否被重复记录 | 未验证（走 B 则不需要） |
| 4 | 蓝牙栈在 mediaId 不变、仅 metadata 变化时发的是 `Now playing changed` 还是 `Track changed` | 真机 + 蓝牙设备抓 btsnoop | 未验证（模拟器无蓝牙音频链路） |

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

1. **默认关闭是真的**：全新安装（未触碰开关）时标题是真实曲名，埋点日志一条都没有——发布循环在 `carLyricTitleEnabled == false` 时整轮跳过，不产生任何推送。
2. **开启后标题被改写**：`metadata.description` 变成 `[SPIKE] ...`，说明开关 → 偏好项 → `@Volatile` 标志 → 发布循环 → `publishMetadataOverride()` 全链路连通。
3. **关闭立即恢复**：T+3s 时（早于"等下一个 tick 才清理"的做法）标题已恢复为真实曲名，且有日志停在 `#16` 佐证推送已停止。这验证了 §6.3 里"关闭时立刻 `publishMetadataOverride(null)`"的必要性——否则车机会残留上一行歌词直到换歌。

**队列仍然零改动**：整个过程 `active item id=88` 保持不变，与假设 1 的结论一致。

**设置搜索**：在设置搜索框输入 `avrcp`（只在 `SettingsRegistry` 的 `keywordsStatic` 里出现），返回结果为「播放 › 标题显示歌词」+ 其副标题，确认第 4 步注册生效。

### 复现步骤

```bash
# 1. 模拟器是 x86_64，arm64-only 的 APK 装不上，必须构 universal（会覆盖同目录 arm64 产物，先备份）
./gradlew :app:assembleDebug -Ppixelplayer.enableAbiSplits=false

# 2. 装 & 启动
adb install -r -d app/build/outputs/apk/debug/pixelplayeross-universal-*.apk
adb shell monkey -p com.lostf1sh.pixelplayeross.debug -c android.intent.category.LAUNCHER 1

# 3. 看车机视角的数据（默认关闭应为真实曲名，且无 spike 日志）
adb shell dumpsys media_session | grep -A 28 com.lostf1sh | grep -E "metadata|controllers|active item"
adb logcat -d -s MusicService_PixelPlayer | grep -c "car lyric title spike"   # 期望 0

# 4. 打开开关：设置 → 播放 → 车载蓝牙 → 标题显示歌词
#    手动点即可；无头环境下用 uiautomator 定位开关（该页三个 Switch 中的第二个）
adb shell input tap 970 213        # 右上角齿轮
adb shell input tap 500 1234       # 「播放」分类
adb shell input swipe 540 1600 540 500 300; adb shell input swipe 540 1600 540 500 300
adb shell input swipe 540 700 540 1100 300
adb shell uiautomator dump /sdcard/ui.xml && adb shell cat /sdcard/ui.xml | tr '<' '\n<' \
  | grep 'checkable="true"' | grep -oE 'checked="[^"]+"[^/]*bounds="[^"]+"'
adb shell input tap 927 430        # 车载蓝牙那一行的开关
```

## 10. 改动文件清单（截至本文档）

| 文件 | 状态 |
|---|---|
| `data/service/player/LyricTitlePlayer.kt` | 新增 |
| `data/service/MusicService.kt` | 改（包装链 + 门控 + 占位载荷） |
| `data/preferences/UserPreferencesRepository.kt` | 改（偏好项） |
| `presentation/viewmodel/SettingsViewModel.kt` | 改（UiState / Group2 / 开关方法） |
| `presentation/screens/SettingsCategoryScreen.kt` | 改（PLAYBACK 子节 + 开关 UI） |
| `presentation/settings/search/SettingsRegistry.kt` | 改（设置搜索注册） |
| `res/values/strings_settings.xml`、`res/values-zh-rCN/strings_settings.xml` | 改（中英成对） |
| `res/drawable/rounded_directions_car_24.xml` | 新增 |

## 11. 尚未完成 / 后续

1. **接入真实歌词**：把 §6.4 的占位载荷换成
   `resolveCurrentLineIndex(lyrics.synced, position)` 的结果。同时把 `resolveCurrentLineIndex` 从 `LyricsSheet.kt:1977` 提到 `utils/` 并补单测（L1）。
2. **仅 A2DP 输出时启用**：`AudioManager.getDevices(GET_DEVICES_OUTPUTS)` 出现 `TYPE_BLUETOOTH_A2DP` 才改写，避免插耳机/外放时标题也被改。
3. **文案策略**：`TITLE` = 当前歌词行，`ARTIST` 保留原曲名（车机通常两行，用户还能知道在放什么歌）。
4. **降级**：无 synced 歌词 / 加载失败 / 云端曲目 → 保持原标题不动。
5. **换歌清空**：`onMediaItemTransition` 里清 `metadataOverride`，避免上一首的歌词盖到下一首。
6. **移除调试限制**：`startCarLyricTitleSpike()` 的 `FLAG_DEBUGGABLE` 早退与占位前缀一起删掉。
7. **真机验证**：假设 4（是否被误判换曲）必须真机 + 蓝牙设备抓 btsnoop 才能定论。
