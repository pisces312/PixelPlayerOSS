# 音乐库新增 Tab / 排序选项落地清单

> 适用：往「音乐库」加一个新 tab（仿 `YEARS`），或给某个 tab 加一个新排序选项。
> 落地时逐条过一遍。漏项的表现是「tab 显示成裸 key」「compact tab 没图标」这类局部失效，不会编译报错。

## 1. 新增一个 tab

| # | 位置 | 说明 |
|---|---|---|
| 1 | `data/model/LibraryTabId.kt` | 数据层枚举 |
| 2 | `presentation/model/LibraryTabId.kt` | UI 层枚举。**注意 package 是 `presentation.library`，文件却放在 `presentation/model/`** |
| 3 | `DEFAULT_LIBRARY_TAB_ORDER` | tab 顺序 = `stored ∪ DEFAULT` → **只在末尾追加即可**，不必动 `migrateTabOrder()` |
| 4 | 所有**穷尽 when** | `availableSortOptions`、`LibraryScreen` 的 selection / `BackHandler` / sort / locate / `iconRes()`（**compact tab 图标最易漏**）/ pager、`LibraryEmptyState` |
| 5 | 带 `else` 分支的 when | 不用改 |

漏第 2 步 → `ReorderTabsSheet` 会显示裸 key（枚举 name 而不是字符串资源）。

## 2. 新增一个排序选项

排序链路需要同步的地方：

```
SortOption.XXX
  → PreferencesKeys
  → UserPreferencesRepository 的 {x}SortOptionFlow / set{X}SortOption()
  → LibraryStateHolder
  → PlayerUiState
  → 各 sortX() 委托
  → LibraryScreen 的两处 when（排序按钮 + 实际 comparator）
```

单测：补 `{x}SortOptionFlow` 与 `currentXSortOption` 两处 stub，否则既有用例会因 relaxed mock 返回 null 而挂。
