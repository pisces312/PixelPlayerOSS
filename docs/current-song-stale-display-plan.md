# 底部播放器短暂显示旧歌 —— 真相源改造方案

状态：**已实施**（2026-09-18，方案 C 落地于 `PlayerViewModel.syncDisplayedMediaItemIfChanged`，未 commit）。§6 为实际落地的改动。

## 1. 症状

偶发（单次观察到一次）：点击播放列表另一首歌，新歌正常开始播放，但底部播放条仍显示旧歌；下一次点歌后恢复。

## 2. 结论：与「标题直进详情 tab」改动无关

那次改动（`SongInfoTab` enum + `initialTab` 参数 + controller 状态）全部是点击标题那一刻才执行的 UI 状态，不写任何播放状态，不在播歌路径上。本 bug 的数据链路是 `stablePlayerState.currentSong`（`UnifiedPlayerSheetV2.kt:154` 直接收 `playerViewModel.stablePlayerState`），与该改动零交集。

## 3. 根因：两个状态写入者乱序打架

正常路径 `internalPlaySongs`（`PlayerViewModel.kt:3084`）会**同步**置 `currentSong = 新歌`，底部条立即更新。要退回旧歌，只能是之后被覆写。覆写来自两个 listener 回调的交错：

1. **`onMediaItemTransition`（`:2655`）**：新歌切换事件 → `transitionSchedulerJob = viewModelScope.launch { ... currentSong = 新歌 }`（`:2660/2694`）——**异步**协程，状态写入要等下一拍主循环。
2. **`onPlaybackStateChanged`（`:2739`）**：**同步**执行，第一行调 `syncDisplayedMediaItemIfChanged(playerCtrl)`（`:2741`）。该函数（`:2503`）拿 `playerCtrl.currentMediaItem`（controller 快照）与状态比对，不一致就 `transitionSchedulerJob?.cancel()`（`:2517`）再**自己写 `currentSong = 快照里的歌`（`:2533`）**。

致命组合：Media3 controller 的事件由 session 异步分发，`playerCtrl.currentMediaItem` 快照可能仍停在旧歌。交错序列：

```
t0  点歌：optimistic 写 currentSong=新歌（:3084，进程内立即生效）
t1  新歌 transition 事件到达 → 启动异步 job（尚未写状态）
t2  某个 playbackState 事件同步到达，controller 快照仍是旧歌
    → syncDisplayedMediaItemIfChanged 判定"不一致"
    → cancel 掉 t1 的正确写入
    → 把 currentSong 覆写回旧歌          ← 卡死在这
t3  新歌的 transition 事件已发完，不会有第二个
    ⇒ 底部条停留在旧歌，音频播的是新歌
```

下一次点歌触发新一轮事件风暴，正确写入落地，故"后来又好用了"。发生概率低：需要 playbackState 事件恰好落在 t1 与 transition job 完成写入之间的窗口、且快照恰好滞后一拍。

## 4. 为什么「读最新状态」可行

VM 与 `MusicService` 同进程，`PlayerViewModel` 直接持有引擎并直接调 `dualPlayerEngine.masterPlayer.setMediaItem(...)`（`:3099`）：

1. **引擎就是播放真相源**：`masterPlayer.currentMediaItem` 是进程内同步属性；controller 是 session 的镜像，只能滞后、不会超前。
2. **ExoPlayer 的 `setMediaItem` 同步更新 `currentMediaItem`**（播放异步）⇒ optimistic 写入后立刻读引擎得到的就是新歌，竞态窗口消失。
3. **crossfade 语义正确**：引擎保证 `playerA`（`masterPlayer` getter）恒为生效 player，淡出结束才 swap（`DualPlayerEngine.kt:1436-1437`）；淡出窗口内读到的"当前歌"= 正在出的那首，与听觉一致。
4. **无兼容问题**：`resolveSongFromMediaItem`（`:2243`）按 mediaId 查库，engine 与 controller 的 mediaItem 的 mediaId 一致。

## 5. 方案对比

| 方案 | 判定 |
|---|---|
| B：去掉 `transitionSchedulerJob?.cancel()`（`:2517`） | **否决**。只堵住"cancel 掉正确写入"的交错；反向乱序（正确写先落、陈旧写后落）依然会打回状态 |
| A：覆写前交叉验证 controller 快照 | 备选。能堵住本例，但 sync 仍把快照当数据源，同类问题换个交错还会回来 |
| **C（采纳）：真相源换成引擎** | 从类上消灭这类竞态 |

## 6. 实施方案（C，已落地）

`syncDisplayedMediaItemIfChanged`（`PlayerViewModel.kt:2503`）内部把真值换成引擎：

```kotlin
val enginePlayer = dualPlayerEngine.masterPlayer
val mediaItem = enginePlayer.currentMediaItem ?: player.currentMediaItem ?: return
```

实际落地内容（2026-09-18）：

- `mediaItem` 真值源：`enginePlayer.currentMediaItem`，controller 视图仅作引擎无 item 时的兜底；
- `currentPosition` / `duration` / `isPlaying` / `playWhenReady` 一并改为读 `enginePlayer`（它才是真正在响的实例，语义统一）；
- 函数头附注释说明为何不能用 controller 快照当数据源；
- 三个调用点（`:2741`、`:2792`、`:2807`）未改——继续只当触发信号。

## 7. controller 快照的去留

**能去掉的只是 sync 函数里"把快照当数据源"这一个用法**，controller 本体不能删：

1. **事件触发源**：VM 的所有播放回调（`mediaControllerPlaybackListener`）挂在 controller 上，覆盖 session 层面的全部变化（系统媒体按钮、蓝牙 AVRCP、focus 引发的 pause 等最终也经 session 传导）。
2. **操作通道**：`loadAndPlaySong`（`:3166-3172`）走 `controller.setMediaItem`，seek / 自定义命令（shuffle 状态 `:3181`）/ 连接生命周期（`pendingPlaybackAction`、重连）都走 controller。
3. **兜底数据源**：引擎未初始化或刚重建时，controller 是唯一可读视图。

彻底移除 controller = 把 VM 的整条监听与操作链路改成直连引擎（外加 session 命令仍需保留），大重构、低收益、高风险，**不做**。

## 8. 验证

1. `assembleRelease` 编译通过。
2. 模拟器复现压力测试：播放中快速连点播放列表的不同歌曲（最易触发原竞态），确认底部条每次立即跟随新歌。
3. 回归：自动切歌（播完下一首）、蓝牙耳机切歌、crossfade 开启时的切歌、通知栏/车机显示的曲目一致性。
