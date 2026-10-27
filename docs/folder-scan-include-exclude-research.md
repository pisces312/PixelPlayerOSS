# 文件夹扫描：排除 / 白名单调研与最终决策（2026-09 调研，2026-10-27 定案）

> 状态：**已决策，不做**。白名单 / 双模式**不排期**，产品继续维持纯排除模式。
> 当天实现已全部撤回、从未提交。本文只作决策存档：记录为什么不做，以及真要做时的前置条件，
> 避免以后重新论证一遍。**看到这里的实现方案不等于待办事项。**

## 1. 现状（调研时的实现）

| 存储 | 角色 | UI |
|---|---|---|
| `BLOCKED_DIRECTORIES` | 黑名单：挖掉一棵树 | 「已排除」 |
| `ALLOWED_DIRECTORIES` | **不是白名单**，只是被排除树内的嵌套回填 | 「已纳入」徽章 |
| 默认 | 全盘扫描 | 文件夹默认「纳入」 |

解析器 `DirectoryRuleResolver`：最深祖先优先；同深度 allow 胜（`deepestBlockLen > deepestAllowLen`）。
扫描过滤共用它：`SyncWorker` / `MediaStoreSongRepository` / `FolderTreeBuilder` / `DirectoryFilterUtils`。
入口：设置 → 曲库 →「排除目录」→ `FileExplorerBottomSheet`（进目录 + 行尾 Radio 切换）。

## 2. 业界惯例（归纳）

| 产品 | 模式 | 形态 |
|---|---|---|
| Poweramp | 包含根 + 树内挖洞 | 全屏文件夹树，勾选；父级可「部分」 |
| Auxio / Retro / Metro | 白名单 | 多选文件夹，Done 提交 |
| Plex / foobar2000 | 纯白名单根 | Add folder，无精细黑名单 |
| MusicBee / GoneMAD | 白名单 + 排除掩码 | 两张列表，exclude 在 include 内生效 |
| BlackPlayer / UAPP | 白名单 | 进目录勾选 |
| Samsung / Apple | 基本不管文件夹 | 系统媒体库 |

结论：

1. 主流是「选要扫的」（白名单心智）；纯黑名单是少数，但 Android 上「默认全扫 + 挖掉几个」仍常见。
2. **树形勾选 / 进入式浏览器**远多于两张平行名单表；业界几乎不做「黑名单页 + 白名单页」并列维护。
3. 层级用三态（全选 / 全不选 / 部分），**更深规则覆盖父级**。
4. 范围模式开关放在同一屏顶部；提交前给摘要（约 N 首）。
5. 勾选控件几乎都用 Checkbox，**不用 RadioButton**（现排除/纳入 Radio 是反惯例）。

## 3. 考虑过的交互方案

### 3.1 双模式 + 对称路径规则（曾实现，已撤回）

- `scanMode`: `EXCLUDE_DEFAULT` | `INCLUDE_ONLY`
- `includeRoots` / `excludeRoots` 语义对称（强制包含 / 强制排除）；**模式只决定「无规则命中时的默认」**
- 判定：最深规则优先；无规则 → `EXCLUDE_DEFAULT` 扫描，`INCLUDE_ONLY` 跳过
- 切换模式**不清空**两套路径，只换默认值

### 3.2 入口即生效模式（曾实现，已撤回）

设置曲库下两个入口并列：

- 「全部扫描（可排除）」→ 写 `EXCLUDE_DEFAULT` 并进共享 FileExplorer
- 「仅扫描所选目录」→ 写 `INCLUDE_ONLY` 并进共享 FileExplorer（空名单先确认「曲库会变空」）

用户只感知「扫 / 不扫」；写入哪套名单由系统按模式 + 祖先命中决定。

### 3.3 卡片并列双入口

「歌曲扫描设置」一框，下一行排除 / 包含并列。可做，但**不要做成两个独立 Switch**（会暗示可同时开）；应做互斥双选项并高亮当前模式。手机上并列触控偏窄，文案要短。

### 3.4 三态：包含 / 排除 / 未指定

单文件浏览 + 行尾三态点按。**「未指定」必须有全局默认**（扫 / 不扫）——若默认=扫，三态就退化成纯排除模式。两套名单可同时生效（最深指定优先），但逃不掉给未指定定默认。

## 4. 数据完整性：切换模式清空曲库再导入

路径：规则收紧 → `SyncWorker` 把「MediaStore 过滤后消失」的 id 当删除 → `deleteSongsAndRelatedData`。

| 数据 | 移出库后再导入 |
|---|---|
| **收藏、歌词** | **丢失**（删除时一并清 favorites / lyrics） |
| **歌单、播放记录（engagements）** | **有机会接回去**：无 FK 级联，行留下；MediaStore ID 不变可对上 |
| 文件内评分 | 不丢（在标签里） |

根因：**「目录规则滤掉」与「文件真删」共用同一条删除路径**，分不清。

若要做白名单 / 双模式，必须先拆删除路径：

- 规则隐藏：只藏不删（查询期过滤，或删 songs 时保留 favorites / lyrics / engagements / playlist_songs）
- 真删文件：才清关联

否则至少在切到空包含名单时提示先备份。

## 5. 最终决策（2026-10-27）

1. **不做。** 纯排除模式保留现状；白名单 / 双模式**不排期、不实现**。理由是收益不足以抵消代价：§4 的数据完整性风险要求先拆「规则隐藏」与「真删文件」两条删除路径，改动集中在同步与删除的核心路径上；而业界主流的白名单心智在 Android 上并不比「默认全扫 + 挖掉几棵树」更省事。
2. 当天实现（`DirectoryScanMode`、resolver 双模式、设置双入口、Switch、备份 `directory_scan_mode` 等）**未提交、已全部撤回**，工作区与仓库都不含相关代码。
3. 如将来重新评估，前置条件是先完成 §4 的删除路径拆分（规则隐藏只藏不删，真删才清关联数据），再上双模式；UI 参考 §3.2 入口即生效 + 空名单确认。
4. 可独立改进的小项（与白名单无关，仍可单独评估）：文件夹行 Radio → Checkbox；设置摘要带排除数量。

## 6. 涉及到的代码锚点（撤回前）

- `utils/DirectoryRuleResolver.kt`、`utils/DirectoryFilterUtils.kt`、`utils/DirectoryScanMode.kt`（新，已删）
- `data/preferences/UserPreferencesRepository.kt`（`DIRECTORY_SCAN_MODE` / `invalidateDirectoryRules` / 备份恢复 bump version）
- `data/worker/SyncWorker.kt`、`data/repository/{MediaStoreSongRepository,MusicRepositoryImpl,FolderTreeBuilder}.kt`
- `presentation/viewmodel/{FileExplorerStateHolder,SettingsViewModel}.kt`
- `presentation/components/FileExplorerBottomSheet.kt`、`presentation/screens/SettingsCategoryScreen.kt`
- `presentation/settings/search/SettingsRegistry.kt`
- 字符串：`strings_settings.xml` / `strings_presentation_batch_g.xml` / `strings_components.xml`（en + zh-rCN）
- 测试：`DirectoryRuleResolverTest`、`MusicRepositoryImplTest`、`SyncWorkerTest`
