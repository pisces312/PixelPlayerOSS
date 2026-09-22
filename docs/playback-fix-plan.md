# 播放子系统修复实施方案（五点）

> 用途：新会话按本文直接改代码。背景与评审依据见 `docs/playback-review.md`。
> 约定：每功能一个 commit，英文 message；改完跑 `.\gradlew.bat :app:assembleRelease` + `:app:testDebugUnitTest`。
> 决策已定（2026-09）：External 保持独立试听、不进历史/统计；外部音频**去掉整文件拷贝**，改为权限探测 + 直接播 URI。

---

## 点 1 — P0：External 试听不得拆共享播放状态

### 问题

- `ExternalPlayerActivity.kt:33` 用 `by viewModels()` 新建完整 `PlayerViewModel`。
- `PlayerViewModel.onCleared()`（约 4133–4155）会调用各 `@Singleton` Holder 的 `onCleared()`。
- `PlaybackStateHolder` / `QueueStateHolder` / `LibraryStateHolder` **没有 owner 守卫**；试听页退出后主界面 `stopProgressUpdates` / 状态被清。

### 方案（对齐 `SearchStateHolder` 的 first-owner）

**1.1 Holder 加 owner 协议**（照抄 `SearchStateHolder.kt:71–91, 218–231`）：

```kotlin
private var owner: Any? = null

fun initialize(owner: Any, scope: CoroutineScope) {
    val current = this.owner
    if (current != null && current !== owner) {
        // Timber.w + return：后到的 VM 不得抢
        return
    }
    this.owner = owner
    // 原 initialize 逻辑
}

fun onCleared(owner: Any) {
    val current = this.owner
    if (current != null && current !== owner) {
        // Timber.w + return：非 owner 不得拆
        return
    }
    this.owner = null
    // 原 onCleared 逻辑
}
```

对下列 Holder 全部加上（方法签名改为带 `owner: Any`）：

- `presentation/viewmodel/PlaybackStateHolder.kt`
- `presentation/viewmodel/QueueStateHolder.kt`
- `presentation/viewmodel/LibraryStateHolder.kt`
- 其他 `PlayerViewModel.init` / `onCleared` 里成对调用的 `@Singleton` Holder（以 `PlayerViewModel` 实际调用列表为准）

`SearchStateHolder` 已有协议，只需把调用点改成传 `this@PlayerViewModel`（若尚未传）。

**1.2 调用点**：`PlayerViewModel` 的 `init` → `holder.initialize(this, viewModelScope)`；`onCleared` → `holder.onCleared(this)`。

**1.3 External 语义**（可选加强，非必须）：试听场景若短命，可让 External 的 VM 在 `init` 里不 `initialize` 主 Holder；但 1.1 的 owner 守卫已足够防拆。优先做 1.1。

**1.4 回归**：主界面播放中 → 文件管理器打开一首试听 → 退出试听 → 主界面进度条/播放/搜索仍正常。加单测：双 VM 时非 owner `onCleared` 不影响 holder 状态（对齐 `SearchStateHolderTest`）。

---

## 点 2 — P1：`resolveDataSpec` 去 `runBlocking` + 快照移出主线程

### 2a. Cloud URI 解析阻塞 loading 线程

**问题**：`DualPlayerEngine.kt` `resolveDataSpec` 内 `runBlocking`（约 1079、1103），proxy `ensureReady(5_000)`（约 1247、1253）会卡住 Exo 加载线程。

**方案**：

1. 优先：缓存命中路径保持同步返回（现状已短路）。
2. 未命中：不要在 `resolveDataSpec` 里 `runBlocking` 等网络/磁盘。可选实现（择一）：
   - **A（推荐，改动小）**：进入 loading 前由 engine 在 `prepareNext` / setMediaItem 阶段异步预解析（`Dispatchers.IO`）并写入 `resolvedUriCache`；`resolveDataSpec` 只读 cache，miss 时返回原始 `DataSpec`（或抛 `IOException` 走重试），不再阻塞 5s。
   - **B**：`runBlocking` 改为短超时（如 200ms）+ 失败回退原始 URI，避免长时间卡死。
3. 保持 `resolveCloudUri` 本身在 `Dispatchers.IO`（约 1222，已正确）。

### 2b. 播放快照在主线程扫全队列

**问题**：`MusicService.kt` `capturePlaybackSnapshot` 强制 `Dispatchers.Main.immediate`（约 1394–1397），`buildPlaybackSnapshotItems`（约 1445–1473）遍历整条 timeline。

**方案**：

1. `capturePlaybackSnapshot` 改为在 `Dispatchers.Default` 构建 items；完成后回主线程写 `_playbackSnapshot` StateFlow。
2. 若必须读主线程-only 的 player 状态：先在主线程拷贝最小不可变数据（mediaId 列表），再在 Default 组装 `Song`/snapshot items。
3. `PlaybackSnapshotItemCache` 保留，失效条件不变。

---

## 点 3 — P1：Metadata 事件不做 Widget 全量重建

### 问题

`MusicService.playerListener.onMediaMetadataChanged`（约 1249–1251）：

- `requestWidgetFullUpdate(force = true)` — 主题色、封面、Room 队列预览等 Glance 全量重建；
- `refreshMediaSessionUiWithFollowUp(session)` — force 刷 media button。

`LyricTitlePlayer` 合成标题会频繁打此事件；与车机 AVRCP 标题链路无关的 widget 工作量被反复触发。

### 方案（不影响车机蓝牙标题/歌词）

**不动**：`LyricTitlePlayer` → `onMediaMetadataChanged` → MediaSession / legacy stub 的元数据发布（AVRCP 读这里）。

**只改** `MusicService` 的 `playerListener`：

```kotlin
override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
    // 保留：ReplayGain 等跟标题相关的轻量逻辑
    replayGainProcessor.onMediaMetadataChanged(activePlayer.currentMediaItem)
    // 去掉 force 全量 widget 重建；改为去抖的非 force 更新
    requestWidgetFullUpdate(force = false)
    // media button 签名与标题无关：保留现有 debounce 即可，不必 followUp 强刷
}
```

若 widget 标题展示需要更新：只更新 `PlayerInfo` 的 title 字段走轻量 diff，不触发 theme/artwork/Room 重建（可在 `requestWidgetFullUpdate(force=false)` 路径上用签名去重；标题变化本身改变签名仍会刷一次）。

**回归门禁**：真机连车机/蓝牙 AVRCP，确认逐行歌词标题仍更新（见 `docs/car-lyrics-avrcp-gate.md`）。

---

## 点 4 — P2：`release()` / `MediaController` 内存清理

### 4a. DualPlayerEngine.release()

**问题**：`release()`（约 1559–1573）未清 `queueSnapshot` 与 callback 列表（约 266–268）。

**方案**：

```kotlin
fun release() {
    // ...现有 player 释放
    queueSnapshot = emptyList()   // 或 null
    // 清空注册的回调列表（与 266–268 字段一一对应）
}
```

### 4b. PlaybackStateHolder MediaController 栈

**问题**：`setMediaController` / `clearMediaController`（约 157–176）pop 不 `release()`。

**方案**：

```kotlin
fun clearMediaController(...) {
    val popped = mediaControllerStack.removeLastOrNull()
    popped?.release()   // 或在替换时 release 旧的
}
```

注意：仅 release 本 Holder 拥有的 controller；与 Service session 断开的 controller 才释放。

---

## 点 5 — 外部试听：删除整文件拷贝，改为权限探测 + 直接播

### 决策（用户 2026-09 确认）

- 不需要 `persistExternalAudioForPlayback` 整文件拷贝逻辑；
- 每次 app 启动检查文件访问权限，及时发现失效；
- 简化实现。

### 方案

**5.1 删除 / 旁路拷贝**

- `ExternalMediaStateHolder.persistExternalAudioForPlayback`（`:310–334`）：删除调用；`buildExternalSongFromUri`（`:232–245`）改为：
  - `resolveDirectFilePath` 有路径 → 用文件路径（现状）；
  - 否则 **直接用原始 `content://` URI** 播，不再 copy 到 `cacheDir/external_audio`。
- 封面 `persistExternalAlbumArt` 可保留（体积小）；若要一并简化也可改为内存/直接 URI，非必须。

**5.2 文件夹 sibling：不预拷、能读一个播一个**

- sibling 解析循环（`:128–135`）保持逐个 `buildExternalSongFromUri`，但单曲失败就 skip，不做整目录 persist。

**5.3 启动时权限/有效性检查**

- 在 `PlayerViewModel.init` 或 Application 启动协程中：
  1. 清理残留 `cacheDir/external_audio`（历史遗留，一次性）；
  2. 对仍存在的试听 URI（若有持久化列表）做 `contentResolver` 可读探测；不可读则丢弃并打日志。
- `ExternalPlayerActivity.persistUriPermissionIfNeeded`（`:124–144`）保留：能 `takePersistableUriPermission` 就拿，降低过期概率。

**5.4 失败策略**

- 打开/下一首时 `openInputStream` 或 Exo 读失败 → toast「无法读取该文件」并跳过；
- 不回退拷贝。

**5.5 回归**

- 文件管理器「打开方式」单曲试听；
- 同目录多曲连播（授权仍有效时）；
- 授权过期后点下一首 → 明确失败提示，不卡死。

---

## 建议实施顺序与提交

| 顺序 | 内容 | 建议 commit |
|---|---|---|
| 1 | 点 1 owner 守卫 | `Guard singleton playback holders with first-owner protocol` |
| 2 | 点 5 去拷贝 + 启动探测 | `Stop copying external audio; probe permission and play URI directly` |
| 3 | 点 2a/2b | `Resolve cloud URIs off the loading thread; build snapshots off main` |
| 4 | 点 3 | `Avoid full widget rebuild on every media metadata change` |
| 5 | 点 4 | `Release engine queue snapshot and MediaController references` |

每步 `assembleRelease`；点 3 后真机车机回归；点 5 后文件管理器试听回归。

## 涉及文件清单

- `presentation/viewmodel/PlaybackStateHolder.kt`
- `presentation/viewmodel/QueueStateHolder.kt`
- `presentation/viewmodel/LibraryStateHolder.kt`
- `presentation/viewmodel/PlayerViewModel.kt`
- `presentation/viewmodel/SearchStateHolder.kt`（仅核对调用是否传 owner）
- `ExternalPlayerActivity.kt`
- `presentation/viewmodel/ExternalMediaStateHolder.kt`
- `data/service/player/DualPlayerEngine.kt`
- `data/service/MusicService.kt`

## 明确不做

- 不合并 External 到主播放历史；
- 不恢复外部音频整文件预拷贝；
- 不改 `LyricTitlePlayer` 的 AVRCP 元数据发布；
- 不动 `EXTRA_QUICK_PLAY` 听歌统计拦截语义。
