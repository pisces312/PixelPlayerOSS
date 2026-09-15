# 歌词链路与 MediaSession 事实（实测记录）

> 与 `docs/car-lyrics-title-plan.md`（车机歌词标题的方案 / 门控 / 调度定案）配套：本文记录更底层、跨功能复用的事实。
> 动歌词、通知栏元数据、蓝牙/车机显示之前先读这一份，避免重复踩坑。

## 1. 歌词来源

| 来源 | 实现 |
|---|---|
| LRCLIB | `LyricsRepositoryImpl.kt` |
| 音频内嵌标签 | `LYRICS` / `SYNCEDLYRICS` / `TTML` / `UNSYNCEDLYRICS` |
| 本地同名文件 | 与音频同目录的 `.lrc` / `.ttml` |

- Navidrome 的 `getLyrics` **未接入**；Jellyfin 无歌词；AI 歌词已在 OSS 中剥离。
- 行定位工具在 `utils/LyricsTimelineUtils.kt` —— 原在 `LyricsSheet.kt`，提取出来是为了避免 data → presentation 的反向依赖。
- 模型：`data/model/Lyrics.kt`；解析：`LyricsUtils.parseLyrics` / `TtmlLyricsParser` / `LyricsfileParser`。`StablePlayerState` 里**没有** `currentLine`。

## 2. AVRCP / 蓝牙的硬限制

- **AVRCP 没有歌词字段**：`GetElementAttributes` 只支持 Title / Artist / Album / TrackNo / Total / Genre / PlayingTime / CoverArt。
- 蓝牙栈**不转发** `MediaMetadata.extras`。
- ⇒ 车机想显示歌词，**只能借 TITLE 字段**（`LyricTitlePlayer` 就是这么做的）。

## 3. MediaSession 元数据是「事件驱动推送」

- 只覆盖 `getMediaMetadata()` **不会**刷新下游（通知栏 / 锁屏 / SMTC 仍是旧值）。
- **必须主动造事件** —— 先例：`MappingPlayer`（`ForwardingPlayer` + `buildUpon()`）、`PlayerViewModel.replaceMediaItem`。
- 伪造事件时**不动 `mediaId`** → 不触发 Track Changed，对 playlist 零副作用。

## 4. 包装链约束

- 每加一层 player 包装，**必须同步维护 `unwrap*` 链**：`publishMediaSessionPlayer()` 靠「解包后比较原始 player」判断是否需要重新发布，漏一层发布判定就失效。
- 这是 wrapper 换代的唯一咽喉位置。

## 5. 「零定时器」不可达（已定案，别再重提）

Media3 的 `MediaSessionImpl` **每 3 秒自带一次周期位置刷新**（默认开启，本仓未关闭），门控只看 `isPlaying() || isLoading()` —— 未播放时不会有，且与本功能开关无关。
`Player.Listener` / `AnalyticsListener` / `AudioProcessor` 都拿不到行边界；PCM 数帧方案已否决。
详见 `docs/car-lyrics-title-plan.md` §6.3。

## 6. 相关文件

```
data/service/MusicService.kt                    # : MediaSessionService
utils/MediaItemBuilder.kt                       # buildMediaMetadataForSong()（通知栏 / 锁屏 / SMTC 同源）
data/service/player/LyricTitlePlayer.kt         # 车机歌词标题（借 TITLE）
data/service/player/CarLyricTitleController.kt  # 门控与调度
utils/LyricsTimelineUtils.kt                    # 行定位
presentation/viewmodel/PlaybackStateHolder.kt   # _currentPosition（Service 侧没有 position Flow）
```
