# 听歌统计加载方案（按需计算 + 内存缓存 + 手动刷新）

> 状态：**已实施（2026-09-14）** —— 定案见 §8，实际改动与偏离见 §9；真机蓝牙爆音对比待接设备后补
> 更新：2026-09-14
> 前置：`docs/stats-design.md`（§4 数据流、「开页即落盘」的既定决策）、`docs/listening-stats-redesign.md`
> 起因：蓝牙播放中切换到「听歌统计」Tab 会出现一次爆音
> 本轮追加：§3.8（「一直不落盘」与「进页面内存累加」的可行性与边界）、§3.9（JSON vs Room 事件表：为什么本次不改 SQL）、§1.2 目标 5-6、§2 数据流图、§5 行为矩阵、§8 待确认

---

## 1. 问题（代码可证实部分）

打开统计页那一刻会同时发生四件事（详见 §1.3 证据）：

1. **整文件重写**：`flushCurrentSession()` → `recordPlayback()` → `updateEventsAtomically()` 把整个
   `playback_history.json` 读 2 次、Gson 全量解析 1 次、全量序列化 1 次、重写并 `fd.sync()`；写完后
   `cachedEvents = null` → 紧接着的 `loadSummary` 又把同一文件**全量解析一遍**。
2. **同一份 DAY 聚合算两遍**：`recordPlayback` 末尾的 `notifyStatsChanged()` 触发
   `StatsViewModel.observeStatsRefreshFlow` 再次 `refreshRange`；两次通过 `rangeJob.cancel()` 互抢，
   但 `buildSummaryFromEvents` 不响应取消 → 先跑那次的 CPU 已经花掉、结果被丢弃。
3. **一次无人使用的 WEEK 聚合**：`refreshWeeklyOverview()` 的结果 `_weeklyOverview` 全仓无人 collect
   （首页概览卡已在 `stats-design.md` §3 移除）→ 纯浪费。
4. **全量扫描**：`buildSummaryFromEvents` 对任何 range（含 DAY）都先 `allEvents.map { expandImportedSpan }`
   展开**全部**历史再按边界过滤 → 「看今天」的成本 = 全部历史，且随历史无界增长（历史永不裁剪）。

爆音机理中「音频缓冲 underrun（CPU/IO 突发 + GC 抢占 `ExoPlayer:Playback` 线程）」为**推断**，需真机实测确认（§6.2）。

### 1.1 证据（file:line）

| # | 位置 | 事实 |
|---|---|---|
| E1 | `StatsViewModel.kt:57-65` | `init` 里 `flushCurrentSession()` 后立刻 `refreshRange(DAY)` |
| E2 | `PlaybackStatsRepository.kt:937-966` | `updateEventsAtomically` 读文件 2 次（锁外 + 锁内 `latestRaw`）、解析 1 次、序列化 1 次 |
| E3 | `PlaybackStatsRepository.kt:982` | `outputStream?.fd?.sync()`（写完整文件后 fsync） |
| E4 | `PlaybackStatsRepository.kt:951, 963` | 写成功后 `cachedEvents = null` → 下一次读又要全量解析 |
| E5 | `PlaybackStatsRepository.kt:221-223` | `recordPlayback` 成功即 `notifyStatsChanged()` |
| E6 | `StatsViewModel.kt:154-170` | `refreshFlow.drop(1).collectLatest { refreshRange(...) ; refreshWeeklyOverview() }` |
| E7 | `StatsViewModel.kt:46-47, 102-116` | `weeklyOverview` 只有定义与赋值，全仓无 collect |
| E8 | `PlaybackStatsRepository.kt:305-306` | 先展开全部事件，再 `resolveBounds` |
| E9 | `StatsPeriod.kt:23-32, 35-44` | 仅 `ALL` 的 `startDate`/`endDateExclusive` 为 null → 只有 ALL 需要事件来算边界 |
| E10 | `PlaybackStatsRepository.kt:1136-1150` | 边界上界被 `now` 截断 |
| E11 | `ListeningStatsTracker.kt:320-322` | `flushCurrentSession` 落盘后把 `accumulatedListeningMs` 归零 → 片段不会重复计数 |
| E12 | `NavControllerImpl.kt`（androidx-main）| `popEntryFromBackStack` 中 `if (!saveState && !transitioning) viewModelStoreProvider?.clear(entry.id)`；`markTransitionComplete` 同样以 `!savedState` 守卫 → 底部 Tab 用的 `saveState = true` **不会**清 destination 级 ViewModelStore |

### 1.2 目标（用户要求）

1. **按需计算**：默认只算「今日」；点「本周」才算本周，以此类推。
2. **只算一次**：App 存活期间每个统计只算一次，来回切换不重算。
3. **手动刷新**：提供刷新按钮，点击后才重新统计。
4. 「统计都加载到内存」——见 §3.1 的答复：**事件本来就在内存**，真正缺的是「结果层」缓存。
5. **一直不落盘**——见 §3.8 的答复：统计路径可做到零磁盘写；进程存活期间完全不落盘不可取。
6. **进页面就地更新、只更新选中时段**——见 §3.8.3：用「廉价重算」达到等价效果，不引入增量累加器。

### 1.3 不变量（改动不得破坏）

- **I1 数据完整性**：当前会话的收听时长仍会落盘（换歌 / 播完 / 服务销毁 / 点刷新），不因本次改动丢失。
- **I2 首屏不为空**：正在播放时打开统计页，「今日」必须包含当前这首歌已听的时长（`stats-design.md` §4 修复的正是这点）。
- **I3 统计口径不变**：`buildSummaryFromEvents` 的过滤 / 合并 / 排名语义完全不动，仅改变「何时算、算什么」。
- **I4 导入与备份**：Poweramp 导入、备份恢复、排序上限变更后，统计数字必须能看到新数据。

---

## 2. 设计总览

```
        ┌─ 结果层 summaryCache（本次新增，键 = 周期身份）─┐
        │                                                │
        ├─ 命中（且周期内无在途片段）→ 直接返回（0 计算） ─┤
        │                                                │
        └─ 未命中 → 粗筛 O(N) → 展开命中项 O(k) → buildSummaryFromEvents ─┘
                          ↑              ↑                    ↑
                   边界先行（新增）  展开缓存（新增）   cachedEvents（已有）
                                                              +
                                            extraEvents = 在途片段（本次新增，§3.7）
```

三层各自有独立的失效条件：事件层随写入变化，展开层随「事件 + 歌曲时长表」变化，结果层只随「显式刷新 / 周期身份变化 / I4 类事件」变化。

**`buildSummaryFromEvents` 本身不改**（口径零风险）：粗筛与 `extraEvents` 都只改变「喂给它的输入集合」，过滤 / 合并 / 排名语义原样保留。

---

## 3. 设计细节

### 3.1 三层缓存

| 层 | 内容 | 现状 | 改动 |
|---|---|---|---|
| 事件层 | `cachedEvents`（Gson 解析结果） | 已有（`readEvents`），但**每次写完被置空** | `updateEventsAtomically` 复用缓存（§3.6） |
| 展开层 | `expandImportedSpan` 后的列表 | 无（每次 summary 重算） | 新增 `expandedEventsCache`，键 = `(cachedEvents === , songs ===)` 身份对；更关键的是先粗筛再展开（§3.5） |
| 结果层 | `PlaybackStatsSummary` / period | 无 | 新增 `summaryCache`（§3.2） |

**关于「都加载到内存」**：「事件」已经在内存里（`cachedEvents` 就是全量 `List<PlaybackEvent>`；一万条约
1 MB 量级，可忽略）。真正每次都重做的是「展开 + 多趟排序分组」和「明明算过又丢掉」。所以本方案加的是
**结果层缓存**，而不是把事件搬进内存。

### 3.2 结果层缓存：键与失效

```kotlin
// 概念签名，非最终代码
private data class SummaryCacheKey(
    val range: StatsTimeRange,
    val startDate: LocalDate?,   // ALL 时为 null
    val endDateExclusive: LocalDate?
)
private val summaryCache = object : LinkedHashMap<SummaryCacheKey, CachedSummary>(16, 0.75f, true) {
    override fun removeEldestEntry(eldest: Map.Entry<...>) = size > MAX_SUMMARY_CACHE_ENTRIES
}
private class CachedSummary(
    val summary: PlaybackStatsSummary,
    val computedAtMs: Long
)
```

**为什么键里放「日历周期身份」而不是 `now` 截断后的边界**：`StatsPeriod(range, anchor = null)` 的当前区间上界被
`min(now, 周期末日)` 截断（E10）。若用截断后的边界做键，时钟每走一分钟缓存就失效一次，「只算一次」不成立。
用「周期身份」后：

- 同一周期内时间流逝 → **不重算**（页面显示的是「截至上次计算时刻」的窗口，符合要求 2）。
- 跨天 / 跨周 / 跨月（周期身份变化）→ 新键未命中 → 自动算一次新的；旧键保留，往回翻页仍可命中。
- 给缓存设上限（建议 12 项，LRU），避免长期翻页累积内存。

| 触发 | 事件层 | 展开层 | 结果层 | 依据 |
|---|---|---|---|---|
| 播放产生新数据（`recordPlayback`） | 失效（追加后即最新） | 失效 | **保留**（不主动清） | 要求 2：「只算一次，来回切换不变」 |
| 当前周期且存在在途片段（播放中进页面） | — | 失效 | 该周期视为未命中 → 重算 | 「进页面就地更新」；成本见 §3.8.3 |
| 点刷新按钮 | 保留（缓存已最新） | 失效 | **清空** | 要求 3 |
| Poweramp 导入 / 备份恢复 | 失效 | 失效 | **清空** | I4 |
| 统计排序上限设置变更 | 保留 | 保留 | **清空** | I4（现由 `refreshFlow` 承担） |
| 歌曲库重扫（时长变化） | 保留 | 失效 | **保留**（需点刷新） | §7 已知限制 |
| 周期身份变化 | 保留 | 保留 | 该键未命中 → 算一次 | 要求 1 |

失效入口集中为一个 `PlaybackStatsRepository.invalidateStatsCache(clearEvents: Boolean = false)`：
- 刷新按钮 → `invalidateStatsCache()` + `flushCurrentSession()`（先落盘再算）。
- `importEventsFromBackup` / 备份恢复 / 排序上限变更 → `invalidateStatsCache(clearEvents = true)`。

**`recordPlayback` 不再调用 `notifyStatsChanged()`**（E5），这是「来回切换不变」的前提。`refreshFlow` 保留，
但语义收窄为「外部数据变更」，消费者从「立即重算」改为「清缓存 + 重算当前周期（若页面可见）」。

### 3.3 按需计算（要求 1）

现有实现**已经**只计算选中 period（`refreshRange(period)`），要删的是无条件多算的部分：

1. 删 `refreshWeeklyOverview()` + `_weeklyOverview`（E7）及其两处调用点。
2. 删 `observeStatsRefreshFlow()`（E6）——自动重算的唯一来源。
3. `onRangeSelected` / `onPeriodShift` / `onPeriodReset` 改为：**先查缓存，命中则同步 emit（`isLoading = false`，不闪 loading）**；未命中才 `isLoading = true` 并计算。
4. 同一 `StatsPeriod` 重复选中直接 return（现有逻辑保留）。

### 3.4 刷新按钮（要求 3）

> **2026-09-15 修订**：本节初稿让刷新先 `flushCurrentSession()` 落盘，实测会**每次多计 1 次播放**
> （根因与修法见 §10）。修订后**不落盘** —— 在途片段已由 `pendingFragment()` 以只读内存事件叠加，
> 落盘对「看到最新数字」这个目的毫无必要。

`ListeningStatsScreen` 的 `StatsTopBar` 已有 `FilledIconButton` + `Icons.Rounded.Refresh`（`enabled = !isRefreshing`），
不需要新增 UI，只改行为：

```kotlin
fun requestStatsRefresh() {
    viewModelScope.launch {
        _uiState.update { it.copy(isRefreshing = true) }
        playbackStatsRepository.invalidateSummaryCache()   // 丢弃结果缓存（§3.2）
        cachedSongs = null                                 // 顺带重查歌曲表（排名/艺人数依赖它）
        loadRange(_uiState.value.selectedPeriod, showLoading = false)  // 在途片段由 loadRange 内部叠加
    }
}
```

### 3.5 先粗筛再展开：O(N) → O(k)

`expandImportedSpan` 只把 `start` 向过去回溯，**`end` 不变**（`PlaybackStatsRepository.kt:267-274`）。因此：

- 粗筛条件 `event.endMillis() >= lowerBound` 对展开前后都成立（保守但正确，不丢事件）。
- 实现顺序改为：① 非 ALL 的 period 直接 `resolveBounds(emptyList(), now, zone)` 拿边界（E9）；
  ② 用 `endMillis` 粗筛出候选；③ 只对候选展开；④ 复用现有 `mapNotNull` 裁剪逻辑。
- ALL 仍需先展开（`startDate == null`，起点取最早 `start`，注释 `:302-304` 说明了原因）——但 ALL 是低频操作，可接受。
- 为 `songs.associateBy { it.id }` 建立按 `songs` 列表身份缓存，避免每次重建全库 Map。

预期：`StatsTimeRange.DAY` 从「扫描全部历史」变成「扫描 + 计算命中区间内的 k 条」。

### 3.6 写入路径：不再重读重解析

`updateEventsAtomically` 改为以内存为基准：

```kotlin
val base = synchronized(fileLock) { cachedEvents } ?: parseEvents(readRawHistoryLocked())
val updated = serializeTransform(base)          // transform 只读，不原地改缓存
val payload = serializeEvents(updated)          // 内部已 sanitize
synchronized(fileLock) {
    writePayloadLocked(payload).also { if (it) cachedEvents = sanitizedUpdated }
}
```

- 进程内所有写入都经过本方法、且都持 `fileLock`，因此「锁外解析 + 锁内重读比对」的防丢更新机制不再必要；
  仍保留 `MAX_FILE_UPDATE_RETRIES` 的外层结构以防将来出现第二个写入者。
- 保存 sanitize 后的列表，保证缓存与磁盘内容一致（`serializeEvents` 内部做 `sanitizeEvent`）。
- 收益：每次落盘少 2 次整文件读取 + 1 次全量解析。

### 3.7 开页不落盘：把「当前片段」作为内存事件叠加（推荐方案 A）

`stats-design.md` §4 记录「开页即落盘」是为了修「播放时打开统计页 → 页面全空」。要既保住 I2 又去掉
那次 O(N) 写入，改为：

```kotlin
// ListeningStatsTracker：只读暴露未落盘片段
fun pendingFragment(now: Long = System.currentTimeMillis()): PlaybackEvent? {
    val session = currentSession ?: return null
    val listened = /* accumulateRealtimeListening 后的等价计算 */
    if (listened < MIN_SESSION_LISTEN_MS) return null
    return PlaybackEvent(songId = session.songId, timestamp = now,
        durationMs = listened, startTimestamp = now - listened, endTimestamp = now)
}
```

- `StatsViewModel` 首次加载时把它作为 `extraEvents` 传给 `loadSummary`（区间过滤会自动排除它落在 period 之外的情况，**无需特判**）。
- **不重复计数的不变量（I1 相关）**：点刷新时先 `flushCurrentSession()`（落盘并把累加器归零，E11），
  再叠加 → 此时 `pendingFragment()` 返回 null 或极短片段，不会与刚写入的事件重叠。需单测锁住。
- 该片段参与结果层缓存的命中判定：只要它仍在增长，其所属周期（今天是当前周期时）就视为未命中 →
  每次进页面重算该周期（成本见 §3.8.3），**数字因此随播放实时更新**；不含当前时刻的周期不受影响。
- 代价：`flushCurrentSession()` 在「开页」路径上消失；它仍在换歌 / 播完 / 服务销毁 / 点刷新时调用，因此**没有数据丢失**。

**方案 B（保守退路）**：不做 §3.7，保留 `init` 里的 `flushCurrentSession()`；其余（§3.1-3.6）照做。
此时开页仍有 1 次整文件写 + fsync，但少了重复解析与两次多余聚合。

### 3.8 「一直不落盘」与「进页面内存累加」的答复（2026-09-14 追加）

三个问题的结论先行：

1. **「统计路径零磁盘写」——可以做到**，且是本方案的核心收益。打开统计页、来回切周期都不再触碰
   `playback_history.json`（消除 E1-E4 那次整文件写 + fsync）。
2. **「进程存活期间完全不落盘」——不可取**。`playback_history.json` 是统计唯一的事实来源，
   进程被系统回收 / 崩溃 / 覆盖安装后就地丢失；`DailyMixManager.recordPlay`（Room `song_engagements`）
   与导入导出也依赖它。落盘时机可以挪，但不能取消（§3.8.1）。
3. **「每次进页面按字段增量累加」——技术上可行，但不值得做**。真正精确的实现要维护一整套中间累加器，
   而收益可以被更便宜的手段等价拿到（§3.8.2 / §3.8.3）。

#### 3.8.1 落盘的真实边界（改后）

| 路径 | 是否落盘 | 说明 |
|---|---|---|
| 打开 / 切换统计页 | **不落盘** | 现状落盘（E1），本方案消除 |
| 换歌 / 播放停止 | 落盘（异步，`Dispatchers.IO`） | `finalizeCurrentSession` → `persistPlayback`（`ListeningStatsTracker.kt:343-356`） |
| 服务销毁 / VM 清理 | 落盘（同步） | `onCleared()` → `forceSynchronousPersistence = true`（`:288-291`） |
| Poweramp 导入 / 备份恢复 | 落盘 | 数据源本身 |
| 点刷新按钮 | 落盘（推荐） | 先 flush 再重算，锁定「截至点击时刻」的口径 |
| 定时落盘 | 现状无 | **不做** —— 实施后撤回，原因见 §9.1 第 5 条 |

「一直不落盘」的合理落地形态 = **落盘只发生在切歌 / 退出 / 显式刷新 / 导入导出，统计页面永不落盘**。

#### 3.8.2 为什么不做「逐字段增量累加」

「已缓存 summary + 新事件的小 summary」只有在**每个字段都可加**时才等价于全量重算。逐项清点：

- **可加**：`totalDurationMs`、`totalPlayCount`、`timeline` 各桶、`dayListeningDistribution` 各桶；
  `peakTimeline` / `peakDayLabel` / `peakDayDurationMs` 可由桶重导。
- **需要额外中间态才能加**：`uniqueSongs`（须留 `Set<songId>`）、`activeDays`（须留日期集合）、
  `longestStreakDays`（在日期集合上重扫连续段）。
- **不可加**：`totalSessions` / `averageSessionDurationMs` / `longestSessionDurationMs` —— session 由
  「间隔 > `sessionGapThresholdMs`(30min) 即切分」得来，而新片段**通常是接续**上一个 session 的，
  正确做法是改最后一个 session 的时长，不是新增一个。
- **截断陷阱**：`songs` 已按 `maxRankingCount`（默认 100，`:367`）截断；若新片段对应的歌原本排在
  截断线之外，叠加会漏掉它。

**唯一有利的前提**：`SEGMENT_JOIN_TOLERANCE_MS = 0L`（`:1187`）→ `mergeSongEvents` / `mergeSpans` 只合并
**重叠或首尾相接**的区间，不吞空隙。flush 产生的碎片（`[t0,t1]`、`[t1,t2]` 首尾相接）合并后时长与
分别累加**相等**，所以时长维度确实可加 —— 但这只覆盖 `totalDurationMs` 一个字段。

要做到精确，必须把 `buildSummaryFromEvents` 拆成「增量维护的累加器 + 从累加器导出 summary」，
上述每个派生字段都要单独对齐口径、单独补测试。**投入产出比不成立。**

#### 3.8.3 替代：用「廉价重算」换「增量复杂度」

把在途片段当 `extraEvents` 参与计算（§3.7），并接受一个事实：**只要片段还在增长，它所属的那个周期
就应该重算** —— 而这次重算被 §3.5 压到几乎免费：

```
粗筛 O(N) 线性比较（无分配）→ 只展开命中的 k 条 → 聚合 O(k log k)
```

N = 全部历史条数（量级 10⁴）、k = 周期内条数（今日通常 10¹）。1 万次 `Long` 比较 ≈ 微秒级，
几十条的排序分组 ≈ 亚毫秒。**结论：重算「今天」的成本与「增量叠加」同量级，因此不必叠加。**

行为矩阵（这就是对「每次进页面就地更新、但不要重复算」的落地回答）：

| 场景 | 行为 | 计算成本 |
|---|---|---|
| 播放中进统计页（默认今日） | 重算今日 + 在途片段 → **数字实时准确** | O(N) 粗筛 + O(k log k) |
| 今日停留后切走再回来（无新播放） | 缓存命中 | 0 |
| 点「本周」/「本月」/「今年」 | 各算一次并缓存 | 首次一次 |
| 往回翻页（上周 / 上个月） | 首次算一次，之后命中 | 0 / 首次 |
| 播放中切到「上周」 | 缓存命中（片段不落在该周期） | 0 |
| 点刷新 | 清缓存 + 重算当前周期 | 一次 |
| 跨天（页面开着） | 周期身份变化 → 今日自动算一次新的 | 一次 |

即：**「只算一次」对一切「不含当前时刻的周期」严格成立；对「当前周期」用低成本重算保证数字新鲜。**
两个诉求同时满足，且 §1.3 的 I3（统计口径不变）零风险 —— 因为**根本没有新增合并逻辑**，
`buildSummaryFromEvents` 一字未动。

> 严格版备选：若坚持「连今天也只算一次」，只能接受「数字在刷新前不随播放增长」（帧冻结）。
> 这与「进页面就更新」直接冲突，故不作为默认；可作为设置项（待定）。

### 3.9 存储层选型：为什么本次不改用 SQL 聚合（2026-09-14 追加）

**先澄清一个前提性误解**：代码里**不存在**「启动时从 DB 读出播放记录再写进 JSON」这条路径。
两个存储是**并列双写**，粒度与用途都不同（`stats-design.md:126` 已写明「统计页只读 JSON 文件，不读 Room」）。

| 存储 | 粒度 | 字段 | 服务对象 |
|---|---|---|---|
| `playback_history.json`（`PlaybackStatsRepository.kt:51`） | **事件级明细**：每次播放一条 | `songId` / `timestamp` / `durationMs` / `start` / `end` / `playCount` | 听歌统计页、最近播放 |
| Room `song_engagements`（`SongEngagementEntity.kt`） | **歌曲级累计**：每首歌一行 | `song_id`(PK) / `play_count` / `total_play_duration_ms` / `last_played_timestamp` | DailyMix 推荐、AI 播放列表、Poweramp 导入、备份 |

**唯一的写入点**：`ListeningStatsTracker.finalizeCurrentSession`（及 `flushCurrentSession`）**同时**调用
`dailyMixManager.recordPlay`（→ Room，`DailyMixManager.kt:244`）与 `playbackStatsRepository.recordPlayback`
（→ JSON）。导入 / 恢复备份同样是两边分别写（`engagementDao.replaceAll/upsert` +
`importEventsFromBackup`）。**是一次触发、两次落盘，不是 A 导出到 B。**

#### 3.9.1 「用 SQL 查统计」在当前 schema 下不可行

`song_engagements` **没有事件级时间戳**，只有「这首歌总共播了多少次 / 总共多少时长 / 最后一次何时」。
以下统计量都无法由它还原：任意时间窗内的时长（今日 / 上周 / 上个月）、时间轴分布、session 划分
（依赖每次播放的起止 + 30min 间隔）、连续收听天数。**要走向 SQL 聚合，前提是先建事件明细表**：

```sql
CREATE TABLE playback_events (
  id           INTEGER PRIMARY KEY AUTOINCREMENT,
  song_id      TEXT    NOT NULL,
  start_ts     INTEGER NOT NULL,
  end_ts       INTEGER NOT NULL,
  duration_ms  INTEGER NOT NULL,
  play_count   INTEGER NOT NULL DEFAULT 1
);
CREATE INDEX idx_events_end_ts ON playback_events(end_ts);
```

#### 3.9.2 有了明细表之后：SQL 确实更快，但收益分布不均匀

| 维度 | JSON（现状） | Room 事件表 | 差距 |
|---|---|---|---|
| **写入** | 读全文 ×2 → Gson 全量解析 → 全量序列化 → **整文件重写 + `fd.sync()`**（E2/E3） | `INSERT` 一行（WAL） | **数量级**（爆音最可能的根源） |
| **读取** | 全量读文件 + Gson 全量反序列化（万条 ≈ 1MB，几十 ms + 大量对象分配与 GC 压力） | 按索引只扫命中区间，聚合在 C 层，无 JVM 对象 | 数倍 ~ 一个数量级 |
| 增量查询（今日） | 必须先全量解析才能过滤 | `WHERE end_ts >= ?` 走索引 | 明显 |
| 区间合并去重 | Kotlin `mergeSongEvents` / `mergeSpans` | SQL 需 gaps-and-islands（窗口函数） | **SQL 反而更复杂、不更快** |
| session / streak | Kotlin（`sessionGapThresholdMs` / 日期集合） | 同上，仍需回 Kotlin | SQL 无优势 |
| 迁移成本 | — | Room schema 已到 v6，需 migration；备份 section + `LegacyPayloadAdapter` 需双轨兼容 | 一次性成本 |

#### 3.9.3 结论

- **本次不做**。§3.7 + §3.8.3 已把读路径压到近零（O(N) 廉价粗筛 + 结果缓存），而写路径在
  「开页不落盘」后已彻底移出 UI 路径 —— **SQL 能解决的两个问题，本方案都用更小的改动解决了**。
- **长期若做，优先理由是写路径，不是读路径**：`AtomicFile` 整文件重写 + `fsync` 是 O(N) 写放大，
  历史越长越明显，且与查询无关。读路径的收益（几十 ms）需要历史涨到 10 万级才显著。
- **务实形态**：SQL 只承担「时间窗 + 歌曲 `GROUP BY`」的粗聚合；区间合并 / session / streak 仍在 Kotlin 里
  对**已过滤的少量事件**（k ≈ 10¹）执行。
- **本仓库已有先例**：`SongEngagementEntity` 的注释写明它 *"replaces the JSON-based storage in
  DailyMixManager for better performance and structured querying"* —— 上游已经对 DailyMix 做过一次
  「JSON → Room」迁移，路径可循。
- **彻底方案（L3）**：以 Room 事件表为唯一来源、废弃 JSON 文件。可顺带消除双写不一致的风险；
  障碍是备份格式与旧版导入的兼容（`playback_history` 是备份的一个 section）。

---

## 4. 改动清单

| 文件 | 改动 |
|---|---|
| `data/stats/PlaybackStatsRepository.kt` | 新增 `summaryCache`（LRU）+ `invalidateStatsCache()`；`loadSummary` 新增 `extraEvents: List<PlaybackEvent> = emptyList()` 与「当前周期有在途片段时视为未命中」的键判定；**新增**提取出的「先算边界 → `endMillis` 粗筛 → 只展开候选」；`updateEventsAtomically` 复用 `cachedEvents`；**`recordPlayback` 去掉 `notifyStatsChanged()`**；`buildSummaryFromEvents` 的聚合语义**一行不动** |
| `presentation/viewmodel/ListeningStatsTracker.kt` | 新增只读 `pendingFragment(now): PlaybackEvent?` —— 基于 `currentSession` 计算当前片段，**不修改任何字段**（与 `flushCurrentSession` 的区别：后者会把 `accumulatedListeningMs` 归零并前移 `startedAtEpochMs`，见 E11） |
| `presentation/viewmodel/StatsViewModel.kt` | `init` 去掉 `flushCurrentSession()`，改为取 `pendingFragment()` 作为 `extraEvents`；删 `refreshWeeklyOverview` / `_weeklyOverview` / `observeStatsRefreshFlow`；`refreshRange` 改为「命中缓存 → 直接 emit（不闪 loading）」；新增 `refreshStats()`（flush → 失效 → 重算，§3.4）；`loadSongs` 复用已有的 `cachedSongs` |
| `presentation/screens/ListeningStatsScreen.kt` | 刷新按钮回调改 `refreshStats()`；可选：`rememberRecentlyPlayedHomeState` 的重活移出主线程（§7） |
| `presentation/screens/SettingsCategoryScreen.kt:1601` | `forceRegenerateStats()` 改为走 `invalidateStatsCache(clearEvents = true)` |
| `docs/stats-design.md` §4 | 同步数据流：「开页即落盘」→「开页读内存片段；切歌 / 退出 / 刷新才落盘」 |
| `app/src/test/.../PlaybackStatsRepositoryTest.kt` | 现有 15 例签名不变、必须全绿；新增：缓存命中不重算 / 周期身份失效 / 刷新失效 / `pendingFragment` 与 `flushCurrentSession` 组合不重复计数 |

**明确不改**：`buildSummaryFromEvents` 的聚合语义与全部 `PlaybackStatsSummary` 字段；JSON 存储格式；
`DailyMixManager` 双写；`loadPlaybackHistory`（最近播放）链路；统计页布局与视觉。

---

## 5. 预期效果

| 场景 | 现状 | 改后 |
|---|---|---|
| 播放中进统计页（默认今日） | 整文件读×2+解析+序列化+fsync，2-3 次全量聚合 | **0 磁盘写**；1 次 O(N) 粗筛 + O(k log k)，数字含当前在途片段 |
| 无新播放时来回切周期 | 每次全量重算 | 缓存命中，0 计算 |
| 播放中切到「上周 / 上个月」等非当前周期 | 每次全量重算 | 缓存命中，0 计算（在途片段不落在该周期） |
| 播放中再次进页面（今日） | 每次落盘都触发重算 | 仅重算「今日」一次，结果含最新片段（§3.8.3） |
| 点刷新 | 2 次全量聚合（重复触发） | 1 次 |
| 跨天（页面开着） | — | 周期身份变化 → 今日自动重算一次 |

---

## 6. 验证计划

### 6.1 单测

- `./gradlew :app:testDebugUnitTest --tests "*PlaybackStatsRepository*" --rerun`
- 新增用例：
  1. 同一 `StatsPeriod` 调两次 `loadSummary`，第二次不触发文件读取（可删掉文件后仍返回缓存，或注入读取计数）。
  2. 周期身份变化（模拟跨天）→ 缓存未命中 → 返回包含新事件的结果。
  3. `invalidateStatsCache()` 后结果反映磁盘最新内容。
  4. `pendingFragment()` 与 `flushCurrentSession()` 组合不重复计数（先 flush 再叠加，总时长不变）。
  5. 现有 12 例（边界裁剪 / 超长播放 / 多艺人 / 权重事件 / 排名上限）全部保持通过 → 证明口径未变。

### 6.2 真机（蓝牙 + 爆音）

1. 蓝牙耳机播放中，从首页切到统计 Tab，重复 5 次。
2. 同时抓：
   - `adb logcat | grep -E "underrun|XRUN|glitch|WaitForGcToComplete|Background concurrent"`
   - `adb shell dumpsys media.audio_flinger | grep -iE "underrun|xrun"`（切换前后计数差）
3. 改造前 / 改造后各跑一遍，对比是否仍有爆音与计数增量。
4. 可选：用仓库自带的 `PerformanceMetrics.recordTiming` / `MainThreadStallMonitor` 记录 `loadSummary` 耗时。

> 注：本机当前 `adb devices` 为空，需接上设备。

---

## 7. 风险与已知限制

| 项 | 说明 |
|---|---|
| 数字「陈旧」 | 只对**非当前周期**成立（往回翻页/历史周期在刷新前不变）。当前周期在播放中进页面会就地重算，数字是最新的（§3.8.3） |
| 歌曲时长变化 | 重扫曲库后，导入事件的展开区间可能变化，但结果层不失效 → 需点刷新。判定标准：影响仅限「导入的零长度事件」 |
| 缓存驻留 | 结果层上限 12 项（LRU）；单条 summary 含 `timeline` 列表，量级为 KB |
| 内存缓存不跨进程 | 进程被杀后重新计算，符合预期 |
| ViewModel 生命周期 | 底部 Tab 用 `popUpTo(saveState = true) + restoreState = true`，源码证实 destination 级 ViewModelStore **不被清空**（E12）→ 缓存放 VM 或 repository 都可行；**本方案放 repository 单例**，以免将来某条导航路径改成不带 `saveState` 的 pop 时静默失效 |
| A 方案的边界 | 若用户「听同一首歌 2 小时不换歌」，刷新时才落盘那 2 小时；与现状一致（现状也是累计到 flush 才写） |

---

## 8. 定案（2026-09-14）

1. **开页不落盘** → 采纳**方案 A**（§3.7）：`pendingFragment()` 作为内存事件叠加，保住 I2。
2. **当前周期允许就地重算** → 采纳（§3.8.3）：播放中进页面数字实时准确，成本约等于零。
3. **缓存放 repository 单例** → 采纳（§3.2）：防将来某条导航路径改成普通 pop 时静默失效。
4. **定时落盘** → **实施后撤回**：`recordPlayback` 每次落盘都会 `playCount += 1`，按 5 分钟切段会把一首长歌计成多次播放，违反 I3。理由见 §9.1 第 5 条。
5. **存储层迁移到 Room 事件表** → **不做**（§3.9）。

---

## 9. 实施记录（2026-09-14）

| 文件 | 实际改动 |
|---|---|
| `data/stats/PlaybackStatsRepository.kt` | ① `summaryCache`（LRU 12，键 = `range` + 起止日期）与 `invalidateSummaryCache()`；② `loadSummary` 新增 `extraEvents`，落在周期内时既不读缓存也不写缓存；③ `buildSummaryFromEvents` 改为「非 ALL 先算边界 → `endMillis` 粗筛 → 只展开候选」（ALL 仍全量展开）；④ `updateEventsAtomically` 以 `cachedEvents` 为基准，写成功后缓存换成刚写下的那份（删掉只此一用的 `serializeEvents`）；⑤ `recordPlayback` 去掉 `notifyStatsChanged()`；⑥ `importEventsFromBackup` 与 `requestRefresh()` 改为「清结果缓存 + 通知」 |
| `presentation/viewmodel/ListeningStatsTracker.kt` | 新增只读 `pendingFragment(nowMillis)`（**不新增任何落盘入口**，见 §9.1 第 5 条） |
| `presentation/viewmodel/StatsViewModel.kt` | `init` 不再 `flushCurrentSession()`；删 `refreshWeeklyOverview()` / `_weeklyOverview` 及其调用点；`refreshRange` → `loadRange`（带 `extraEvents`）；`requestStatsRefresh()` = flush → 清缓存 → 重查歌曲 → 重算当前周期；`observeStatsRefreshFlow` 收窄为「外部数据变更」 |
| `docs/stats-design.md` §4 / §6 | 数据流图、关键点与文件说明同步 |
| `app/src/test/.../PlaybackStatsRepositoryTest.kt` | 新增 8 例 |
| `app/src/test/.../ListeningStatsTrackerTest.kt` | 新增 1 例 |

### 9.1 与方案的偏离（均有意为之）

1. **命中缓存时仍走 `showLoading = true`**（§3.3.3 原写「命中则同步 emit，不闪 loading」）。缓存在 repository 内部，VM 在调用前无从知道是否命中；判定只有一份、放在 repository 才不会两处走偏。命中时 `loadSummary` 微秒级返回，`isLoading` 存在不到一帧，且只影响刷新按钮的 `enabled`。
2. **未做 `songs.associateBy` 的身份缓存**（§3.5 末行）。只有缓存未命中才需要重建全库 Map，而这条路径已被结果缓存与粗筛压到低频；代价是单例长期强引用整个歌曲列表，不划算。
3. **刷新按钮保留方法名 `requestStatsRefresh`**（未改 `ListeningStatsScreen` 接线）。行为已换成「flush → 清缓存 → 重查歌曲 → 重算当前周期」，名字仍贴切，少改一个文件。
4. **`extraEvents` 直接追加进 `allEvents`**，没有新增合并逻辑 —— `buildSummaryFromEvents` 一行未改，过滤 / 合并 / 排名语义原样复用，I3（口径不变）零风险。
5. **定时落盘：做了又撤回。** 第 4 点要求「把一首歌播很久时进程被杀的丢失窗口缩到 N 分钟」。实现方式（每 5 分钟 `flushCurrentSession()`）**会改变统计口径**：`recordPlayback` 的 `playCount` 默认为 1 且 `coerceAtLeast(1)`，而每次 `flush` 都会独立追加一条带权重的事件 → 一首播 30 分钟的歌会被算成 6 次播放，`totalPlayCount` 与「热门歌曲按次数排序」双双被污染。要正确实现必须让「续传片段」不计次数，而 `PlaybackEvent.weight` 无法区分「JSON 缺字段」（Gson Unsafe → 0）与「显式 0」，改动会波及数据模型与全部聚合路径 —— 属独立立项。**故撤回，丢失窗口与改动前完全一致（不构成回归）。**

### 9.2 验证

- `./gradlew :app:testDebugUnitTest` 全绿：`PlaybackStatsRepositoryTest` 22 例（原 14 + 新 8）、`ListeningStatsTrackerTest` 3 例（原 2 + 新 1）。
- 新用例覆盖：同周期复用同一实例 / 键用日历周期而非 `now` / `invalidateSummaryCache` 后重算且内容一致 / 在途片段叠加且不进缓存 / 周期外片段不影响命中 / `recordPlayback` 不再通知 / 导入通知并清缓存 / DAY 粗筛与跨零点裁剪。
- `./gradlew :app:assembleDebug` 通过。
- **未做**：真机蓝牙爆音对比（§6.2 的 `dumpsys media.audio_flinger` 前后计数差）。本机 `adb devices` 为空，需接设备后补。

### 9.3 顺带修正的既有缺陷

`flushCurrentSession()` 每次调用都会把当前歌切成一段、各计 1 次播放。旧代码在 `StatsViewModel.init` 里调用它 —— 也就是**每次打开统计页**都会触发。改后该调用点消失，只剩「点刷新按钮」这一处，触发频率显著下降（同一首歌被反复「进页面」计成多次播放的问题因此缓解）。**§10 进一步把刷新这一处也移除，该函数整体删除。**

### 9.4 遗留的已知副作用

**点刷新按钮会 `flushCurrentSession()`**，若此刻正在播放同一首歌，会把它切成新的一段（多计 1 次播放）。这是「锁定截至点击时刻的口径」的代价；连续快速点刷新会累加。**根因与修法见 §10。**

---

## 10. 刷新重复计数：根因与修法（2026-09-15）

> 状态：**方案 A 已实施（2026-09-15）**；方案 B 未做（独立立项，见 §10.4）
> 现象：每次点刷新，当前正在播放的那首歌「播放次数」多算 1 次
> 起源：§9.4 记录的副作用；本章给出根因链、两种修法与推荐

### 10.1 根因链（全部可查证）

| # | 位置 | 事实 |
|---|---|---|
| 1 | `StatsViewModel.requestStatsRefresh():92` | 刷新按钮的**第一件事**就是 `listeningStatsTracker.flushCurrentSession()`（全仓唯一生产调用点，已 grep 确认） |
| 2 | `ListeningStatsTracker.flushCurrentSession():302-340` | 把当前播放的累计片段作为**一条独立事件**落盘 |
| 3 | 同上 `:319-322` | 落盘后 `accumulatedListeningMs = 0`、`startedAtEpochMs = nowEpoch` → 下一段与刚落的这段**首尾相接** |
| 4 | `:332-336` → `PlaybackStatsRepository.recordPlayback(playCount = 1)` | 新事件 `weight = 1`（`PlaybackEvent.weight:98-99`） |
| 5 | `:327-331` → `DailyMixManager.recordPlay` → `EngagementDao:52` | 同一次落盘还写了 Room：`play_count = play_count + 1` |
| 6 | `PlaybackStatsRepository.mergeSongEvents():798` | 合并条件是 `start <= currentEnd + SEGMENT_JOIN_TOLERANCE_MS`，而该常量为 **0** → 首尾相接必然被合并 |
| 7 | 同上 `:800` | 合并时 `currentPlayCount += event.weight` → **次数逐条累加** |
| 8 | `:440` / `:455` | `totalPlayCount = sumOf { it.playCount }`；`SongPlaybackSummary.playCount` 同源 → 双双随刷新次数线性增长 |

**一句话根因**：`flushCurrentSession()` 把「一次连续播放」人为切成多段，而聚合逻辑对区间的处理是「**区间取并集（去重）+ 次数求和**」——并集让时长正确，求和让次数错误。所以现象精确地表现为「**时长对、次数多**」。

注意 `#3` 的衔接设计本身没错（正因如此时长才不会重复），错的是「切分」这个动作没有对应的「不计次」标记。

### 10.2 为什么不能靠「写入时合并到上一条」来修

一个看起来更小的修法是：写入时若末尾已有同歌且首尾相接的事件，就合并进去（时长相加、次数不变）。

**不可行**，因为写入方无法区分两种首尾相接：

- 同一次播放被 flush 切开 → 应当合并且**不计次**；
- 单曲循环 / 手动立刻重播同一首歌 → 两条事件同样首尾相接，但**必须计 2 次**。

在数据层面这两者完全同构。因此「是否续传」必须由**产生方**声明，而不是由写入方猜测。这直接导出下面的两个方案。

### 10.3 方案 A（推荐）：刷新不落盘 —— 删除 `flushCurrentSession`

**论证**：`flushCurrentSession` 存在的唯一理由是「让尚未落盘的在途数据变得可见」（这正是 `docs/stats-design.md` §4 记录它的原因）。而「打开统计页」这一用途在 §9.3 已改为 `pendingFragment()` 只读叠加，不再需要落盘。刷新按钮想达到的目的与开页**完全相同**（看到最新数字），因此同样不需要落盘。

关键性质：`pendingFragment()` 是**幂等只读**的——它不改动 session 任何字段，返回「本 session 从开始至今的整段」区间。不落盘的前提下，连点 N 次刷新返回的是同一个区间，**不会累加**；而播放继续推进时它自然变长，数字跟着增长，正是期望行为。

于是 `flushCurrentSession` 失去全部调用者，可直接删除。落盘路径收敛为**唯一一处** `finalizeCurrentSession`（换歌 / 播放停止 / `onCleared`），「一次连续播放 = 一条事件 = 一次计数」于是在代码结构上成立。

**改动（均为删除，3 个文件）**

| 文件 | 改动 |
|---|---|
| `presentation/viewmodel/StatsViewModel.kt` | `requestStatsRefresh()`（`:90-98`）删掉 `flushCurrentSession()` 及其 `runCatching`，保留 `invalidateSummaryCache()` + 重查歌曲表 + 重算当前周期 |
| `presentation/viewmodel/ListeningStatsTracker.kt` | 删 `flushCurrentSession()`（`:293-340`）与只服务它的 `flushMutex`（`:44`、`:306`）；`pendingFragment` 的 KDOC 改为「本 session 至今的整段，不落盘」 |
| `app/src/test/.../ListeningStatsTrackerTest.kt` | 改写依赖 flush 的第 3 例（`:104-141`）为「不落盘时 `pendingFragment` 随播放增长且幂等；`finalizeCurrentSession()` 之后归零」 |

**口径影响**

| 维度 | 影响 |
|---|---|
| 时长 | **不变**。是否切分不影响区间并集。 |
| 播放次数（JSON `totalPlayCount` / `SongPlaybackSummary.playCount`） | **修正为正确**（现在每刷新 +1）。`totalPlayCount` 的定义就是「播放次数」，同一次播放不该被算多次 —— 这是 bug 修复，不是口径变更。 |
| Room `song_engagements.play_count`（DailyMix 评分依据） | **同样修正**。它的加一来自同一个 flush，一并消失。 |
| 磁盘写入 | 刷新不再是落盘时点。注意这并不缩小丢失窗口 —— 改造前丢的就是「当前这一整首」，flush 只在用户手点刷新时才发生，从来不是保险机制。 |

**残留的语义收窄（需接受）**：不播放时点刷新，从文件读到的数据没变 → 数字看起来「没反应」；播放中点刷新，数字本来就已经是最新的（`extraEvents` 非空 → 不走缓存）→ 同样看起来没反应。也就是说这个按钮的实际作用收窄为「强制丢弃可能陈旧的缓存并重查歌曲表」。这属于**正确**而非缺陷，但若希望有可感知反馈，可参考 §7 待定项（标题栏显示「截至 HH:mm」）。

### 10.4 方案 B（可选，独立立项）：让「续传片段」可表达

只有当确实需要「中途落盘」（缩小进程被杀的丢失窗口）时才有必要。

- **不能用 `playCount = 0` 表达续传**：`weight:98-99` 把 `<= 0` 兜底为 1，而这个兜底是为了兼容旧 JSON 缺字段（Gson 用 `Unsafe` 分配实例 → `Int` 字段落 0，见 `:779-781`）。两者在数据上不可区分。
- **正确做法**：新增独立布尔位，例如 `isContinuation: Boolean = false`（旧数据缺字段 → false，天然兼容），`weight` 改为
  `if (isContinuation) 0 else playCount.coerceAtLeast(1)`。
  （把 `playCount` 改成可空 `Int?` 亦能让「缺失」区别于「显式 0」，但显式 0 仍会被旧兜底折成 1，所以仍需要一个明确的续传标记 —— 布尔位更直白。）
- **影响面 5 处**：`expandImportedSpan:345`、`mergeSongEvents:800`、`mergeSpans:826`、bucket 折算 `:1116`、`totalPlays:440`。令续传片段 `weight = 0` 后这些点自然不计次，**时长仍按区间合并计入**。
- **收益**：① 中途落盘变安全（可恢复定时落盘，把丢失窗口从「整首」缩到 N 分钟）；② 「续传不计次」由数据自身声明，不再依赖「从不切分」这一行为约束。
- **成本**：数据模型 + 序列化兼容 + 5 处聚合点 + 新测试。故属独立立项。

### 10.5 与 §9.1 第 5 条的关系

§9.1 第 5 条判断「`PlaybackEvent.weight` 无法区分 JSON 缺字段与显式 0」——**这个判断正确**。但方案 A **绕开了这个问题**：既然不再切分，就无需表达「续传」。因此当时撤回定时落盘的处理不需要推翻，方案 A 与它并不冲突。

### 10.6 验证计划

- 新增用例：连续多次刷新后，同一 period 的 `totalPlayCount` **不变**、`totalDurationMs` **单调不减**。可用 `extraEvents` 直接构造（不依赖真实文件 I/O —— 见 §9.2 关于 `android.util.AtomicFile` 在 JVM 单测下为空实现的说明）。
- 回归：`PlaybackStatsRepositoryTest` 现有 22 例、`ListeningStatsTrackerTest` 3 例（其中 1 例改写）全绿。
- 真机：播放中连点刷新 5 次 → 统计页「播放次数」保持不变（改造前为 +5）。

### 10.7 实施记录（方案 A，2026-09-15）

| 文件 | 实际改动 |
|---|---|
| `presentation/viewmodel/StatsViewModel.kt` | `requestStatsRefresh()` 删掉 `flushCurrentSession()` 调用及其 `runCatching`，只留「清结果缓存 → 重查歌曲表 → 重算当前周期」；KDOC 写明不落盘的理由 |
| `presentation/viewmodel/ListeningStatsTracker.kt` | 删 `flushCurrentSession()` 与只服务它的 `flushMutex`（连带 `Mutex` / `withLock` import）；`pendingFragment` 的契约由「上次落盘之后的增量」改为「本 session 从开始至今的整段」，KDOC 与内部注释同步 |
| `app/src/test/.../ListeningStatsTrackerTest.kt` | 改写原第 3 例（它依赖 `flushCurrentSession` 制造「部分落盘」状态）为 `pendingFragment reports the whole unpersisted session and stays idempotent`：断言整段而非增量、固定 `now` 两次取值相同、`finalizeCurrentSession()` 落盘的就是同一整段、落盘后返回 null |
| `app/src/test/.../PlaybackStatsRepositoryTest.kt` | 新增 `looping the same song counts every repetition`：两条首尾相接的同歌事件必须计 2 次、时长合并为 6 分钟。用途是**防误修** —— 阻止将来有人把「同歌合并时次数取 max」当成刷新重复计数的修法（§10.2 已论证其不可行） |
| `docs/stats-design.md` §4 | 数据流图去掉 `flushCurrentSession` 节点；落盘时机改为「只有 `finalizeCurrentSession`」；首屏不为空与结果缓存的说明同步 |

**与验证计划的差异**：§10.6 原设想在 repository 层断言「连点刷新后次数不变」，实际改为在 tracker 层断言 `pendingFragment` 的**幂等性**。理由：次数虚高的产生方是 tracker（它决定落不落盘、落什么区间），repository 只是照单聚合并无状态 —— 在 repository 层重复喂同一 `extraEvents` 只能证明「纯函数是纯的」，证明不了「刷新路径不再产生新事件」。

**未做**：方案 B（续传标记 + 定时落盘）；真机蓝牙爆音对比（本机 `adb devices` 为空，方法见 §6.2）。
