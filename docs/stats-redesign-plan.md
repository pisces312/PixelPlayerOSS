# 听歌统计与最近播放重设计计划（PixelPlayerOSS）

> 状态：**已实施并推送**
> 更新：2026-09-10
> 分支：`pisces/port`（`pisces312/PixelPlayerOSS`，GPL-3.0 fork）
> 线框稿：`E:\downloads\听歌统计与最近播放重设计线框.html`
> **最终设计文档：`docs/stats-design.md`**（含 mermaid 线框图与数据流）

---

## 0. 用户最新决策（2026-09-10）

1. **点歌直接播放**：听歌统计里所有界面显示出来的歌曲都能直接点击播放（StatsScreen + RecentlyPlayedScreen）。
2. **Stats 提升为一级底部 Tab**：Home / Search / Library / **Stats**（第 4 个，Library 右侧）。
3. **Stats 加入 LaunchTab 偏好**：允许用户设默认启动页为 Stats。
4. **艺人/专辑行保留跳转详情**：StatsScreen 里的 Top artists / Top albums 行继续导航到 ArtistDetail / AlbumDetail。
5. **移除首页统计预览卡**：Stats 已是一级 Tab，首页 `StatsOverviewCard` 冗余，移除组件 + `StatsViewModel.homeOverview` 死代码。

---

## 1. 现状与线框差距

| 线框要求 | 当前代码状态 | 改动 |
|---|---|---|
| Stats 为一级底部 Tab | 仅首页 StatsOverviewCard 进入 | MainActivity + LaunchTab 调整 |
| 简单标题栏：← 听歌统计 | 可折叠大标题栏（176dp → 62dp） | 改普通 TopAppBar |
| Tab：日 / 月 / 周 / 年 / 全部（小胶囊） | 英文 DAY/WEEK/MONTH/YEAR/ALL | 改中文短标签 + 胶囊样式 |
| 周期选择器：‹ 2026年9月 ›（灰底圆角） | 英文日期，无圆角底，方向键语义反 | 改中文格式 + 圆角底 + 修方向 |
| 2×2 指标卡：次数 / 时长 / 歌曲 / 艺人 | 4 张大 HeroCard | 改 2×2 小卡片 |
| 简洁柱状图 | TimelineBarChart + 双实现 | 简化，去 TimelineMetric 切换 |
| 仅「热门歌曲」+ 次数/时长切换 | 额外 5 个卡片（习惯/分类/艺术家/专辑/集中度） | 删除，只留热门歌曲 |
| 最近播放：中文范围 + 天级 sticky header | 英文范围，header 格式不对齐 | 改中文 + 格式对齐 |
| 所有歌曲行点击播放 | StatsScreen 已支持，RecentlyPlayedScreen 待查/补 | 补/保留 onSongClick |

---

## 2. 实施分阶段 checklist

### Phase 1：Stats 提升为一级 Tab（MainActivity + LaunchTab）

- [x] **1.1 找/加图标**：复用现有 `rounded_monitoring_24.xml`（柱状图+折线，统计语义）。
- [x] **1.2 MainActivity.kt**：`commonNavItems` 追加第 4 项 `BottomNavItem("Stats", rounded_monitoring_24, rounded_monitoring_24, Screen.Stats)`；`routesWithHiddenNavigationBar` 移除 `Screen.Stats.route`。
- [x] **1.3 LaunchTab 偏好**：`LaunchTab` 加 `STATS`；`AppNavigation.toRoute()` 加 `STATS -> Screen.Stats.route`；设置页默认 Tab 选项加「听歌统计」。
- [x] **1.4 AppNavigation**：Stats 路由改用 mainRoot 转场；`MainRootRoutes` 加 Stats（index 3）。

**验证**：`:app:assembleDebug` 通过。

### Phase 2：StatsScreen 按线框精简

- [x] **2.1 标题栏**：改普通 `TopAppBar`（无返回键，一级 Tab）。
- [x] **2.2 范围 Tab**：中文短标签（日/月/周/年/全部）+ 圆角胶囊。
- [x] **2.3 周期选择器**：中文格式 + 灰底圆角 + 修正方向键（← 上一周期 / → 下一周期）。
- [x] **2.4 指标区**：2×2 小卡片（播放次数/总时长/不同歌曲/不同艺人）。
- [x] **2.5 时间线**：简化为纯柱状图，去掉 TimelineMetric 切换。
- [x] **2.6 热门歌曲**：只保留一个列表 + 次数/时长切换；删除收听习惯/热门分类/曲目集中度。
- [x] **2.7 歌曲行**：封面 36dp + 歌名 + 艺人 · ×N + 时长，全部点击播放。
- [x] **2.8 艺人/专辑行**：保留 TopArtistsCard / TopAlbumsCard 跳详情。

**验证**：`:app:assembleDebug` + `:app:testDebugUnitTest --tests "*Stats*"` 通过。

### Phase 3：RecentlyPlayedScreen 按线框调整

- [x] **3.1 范围选择**：改中文（复用 `displayNameRes()`，今日/本周至今/本月至今/本年至今/全部时间）。
- [x] **3.2 天 header**：格式「今天」「昨天」「9月7日 周一」（中文 `M月d日 EEE`）。
- [x] **3.3 歌曲行**：`MergedRecentlyPlayedSongItem` 对齐线框（艺人 · 时间 + 右侧 ×N 徽章），点击播放已有。

**验证**：`:app:assembleDebug` 通过。

### Phase 4：字符串与收尾

- [x] **4.1 字符串**：已加中文短标签、指标卡、热门歌曲、排序切换等字符串。
- [x] **4.2 单测**：`:app:testDebugUnitTest --tests "*Stats*" --tests "*PlaybackStats*"` 通过。
- [x] **4.3 提交**：已提交并推送（`29e39595` / `6a19ba02`），英文 message。

---

## 3. 文件清单

### 修改

```
MainActivity.kt
presentation/navigation/Screen.kt
presentation/navigation/AppNavigation.kt
data/preferences/UserPreferencesRepository.kt      # LaunchTab 加 STATS
presentation/screens/StatsScreen.kt                # 精简 + 中文 + 可点播放
presentation/screens/RecentlyPlayedScreen.kt       # 中文 + 天 header + 可点播放
presentation/stats/StatsTimeRangeUi.kt             # 中文短标签
res/values-zh-rCN/strings_presentation_batch_g.xml
res/values/strings_presentation_batch_g.xml
res/drawable/rounded_query_stats_24.xml            # 若不存在则新增
presentation/components/MergedRecentlyPlayedSongItem.kt  # 行布局/点击调整（如需）
```

### 参考（线框，只读）

```
E:\downloads\听歌统计与最近播放重设计线框.html
```

---

## 4. 风险与备注

- **数据层不动**：`PlaybackStatsRepository` / `StatsPeriod` / `ListeningStatsTracker` 保持现状。
- **LaunchTab 默认值**：新增 STATS 枚举值，默认仍 HOME，避免改变现有用户启动行为。
- **底部 Tab 图标**：若新增 drawable，需确保与现有 `rounded_*_24` 风格一致（24dp，stroke 2）。
- **RecentlyPlayedScreen 点击播放**：先查代码确认是否已接 `onSongClick`，若无则补。
- **删除多余卡片**：StatsScreen 里删除的 5 个卡片对应 Composable 可保留在文件里（不再引用），或一并删除减少维护。倾向一并删除。
- **commit 顺序**：Phase 1 → Phase 2 → Phase 3 → Phase 4，便于二分定位问题。
