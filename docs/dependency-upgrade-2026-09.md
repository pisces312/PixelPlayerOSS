# 依赖升级方案（media3 除外）

> 日期：2026-09-18
> 基线：`main` @ `b7f3c140`，`APP_VERSION_NAME=0.4.2-pisces.1`
> 范围：`gradle/libs.versions.toml` 中除 `media3*` 外的第三方依赖 + 构建工具链
> 状态：**仅调研，未改任何代码或配置**。等确认后执行。

## 1. 结论摘要

1. **大部分升级是「补差」，不是「跨代」**：逐一查询了 70+ 个坐标在 Google Maven / Maven Central /
   JitPack 上的最新版本，其中**只有 Coil 一家需要动代码**（2.x → 3.x），
   其余全部是 patch / minor，改 `libs.versions.toml` 即可。
2. **一个必须先纠正的认知**：`compose.ui/foundation/animation` 在 toml 里写着 `1.11.4` +
   BOM `2026.06.01`，但 `releaseRuntimeClasspath` **实际解析到 `1.12.0-beta01`** ——
   被 `material3:1.5.0-alpha25` 的传递依赖顶上去的。也就是说当前跑的是「beta 版 Compose + 声明值说谎」。
   本次升级的目标之一就是把这两者重新对齐到 **1.12.1 stable**。
3. **material3 是全栈锚点，不能单独动**：`1.5.0-alpha25` 拉 Compose `1.12.0-beta01`，
   而 `1.5.0-alpha28` 拉 **Compose `1.13.0-alpha01`**。升 material3 的 alpha 号 = 整条 Compose 栈
   掉进 alpha。**建议本次保持 `1.5.0-alpha25` 不动**（详见 §3）。
   > **2026-09-18 复核后修订**：这个结论只对了「不能随便动」这一半。逐版读源码后找到了
   > **`alpha27`** 这条中间路 —— 它请求的仍是 Compose `1.12.0-beta01`（被显式声明顶住，栈留在 stable），
   > 所以**不必停在 alpha25**。已按 §9.4 的档 A 升到 `alpha27`，执行结果见 §10。
   > 同理「§3 里 material3 那一行」以 §9.3 的逐版实测表为准。
4. 6 个纯 CVE 约束库（netty / bouncycastle / commons-lang3 / jdom2 / jose4j / httpclient）
   在 `app/src/main` 里 **零引用**（`grep` 实测），升它们对代码零影响。
5. 建议拆 **3 个提交**（Compose+库 / 工具链 / Coil 3），Coil 3 可以独立评估、独立延后。

## 2. 现状事实（实测，非推断）

| 项 | 值 | 来源 |
|---|---|---|
| Gradle wrapper | 9.6.1 | `gradle/wrapper/gradle-wrapper.properties` |
| AGP | 9.3.1 | `libs.versions.toml` |
| Kotlin / KSP | 2.4.10 / 2.3.10 | 同上 |
| Compose 声明值 | 1.11.4（ui / foundation / animation）+ BOM 2026.06.01 | 同上 |
| **Compose 实际解析值** | **1.12.0-beta01** | `:app:dependencies --configuration releaseRuntimeClasspath` |
| material3 | 1.5.0-alpha25（alpha，非 BOM 管理） | toml 显式声明 |
| Coil | 2.7.0（2.x 最后一个版本） | Maven Central metadata |
| 无 dynamic feature 模块 | 是 | `app/build.gradle.kts` 无 `dynamicFeatures` |

## 3. 分级清单

### Tier 1 — 改版本号即可（无代码改动）

| 组件 | 现值 | 目标 | 备注 |
|---|---|---|---|
| `composeBom` | 2026.06.01 | **2026.09.00** | 对齐后 Compose = 1.12.1，adaptive = 1.3.0 |
| `composeUi` / `foundation` / `animation` | 1.11.4 | **1.12.1** | 必须与 BOM 同步改，否则又是「声明值 ≠ 生效值」 |
| `room*`（compiler/ktx/runtime） | 2.8.4 | **2.8.5** | 三处同改；无 schema 变更 |
| `paging` | 3.5.0 | **3.5.1** | |
| `navigationCompose` / `navigationRuntimeKtx` | 2.9.8 | **2.10.1** | 见 §4.2 风险 |
| `appcompat` | 1.7.1 | **1.8.0** | |
| `okhttp` | 5.4.0 | **5.5.0** | |
| `ktor` | 3.5.2 | **3.6.0** | 用于 `CloudStreamProxy` 本地服务 |
| `kotlinxCollectionsImmutable` | 0.5.1 | **0.5.2** | |
| `snakeyaml` | 2.4 | **2.7** | `LyricsfileParser` 在用 |
| `netty`（约束） | 4.2.16.Final | **4.2.18.Final** | 零引用，纯 CVE |
| `bouncycastle`（约束） | 1.85 | **1.86** | 零引用 |
| `jose4j`（约束） | 0.9.6 | **0.9.7** | 零引用 |
| `junitJupiter`（api/engine/params/launcher/vintage） | 6.1.2 | **6.1.3** | 测试 |
| `orgJson`（测试） | 20260522 | **20260814** | 测试 |
| `benchmarkMacroJunit4` | 1.4.1 | **1.5.0** | 仅 `:baselineprofile` 模块 |
| `baselineprofile`（插件） | 1.5.0-beta01 | **1.5.0** | 已转正 |

### Tier 2 — 工具链（单独一个提交，风险中等）

| 组件 | 现值 | 目标 | 依据 |
|---|---|---|---|
| `agp` | 9.3.1 | **9.4.0** | AGP 9.4 要求 Gradle ≥ 9.6.0，本机 wrapper 9.6.1 已满足，**无需动 wrapper**；最高支持 API 37 = 本项目 compileSdk |
| `kotlin` | 2.4.10 | **2.4.20** | Compose 编译器插件版本 = Kotlin 版本，联动 |
| `ksp` | 2.3.10 | **2.3.12** | KSP2 独立版本号；2.3.12 最低 AGP 要求 8.12.0，满足 |

AGP 9.4 新增的 `android.enforceDynamicFeatureVariantMatching` **默认警告模式**，本项目无 dynamic feature
模块 ⇒ 不产生任何输出。无其他需要新增/删除的 `gradle.properties` 开关。

> Tier 2 可单独跑：如果只想稳，可以先只做 Tier 1，工具链留到下次。但 KSP 2.3.12 与 Kotlin 2.4.20
> 建议同批（版本号交叉支持，分开升反而要验两次）。

### Tier 3 — 需要代码迁移：Coil 2 → Coil 3

| 组件 | 现值 | 目标 |
|---|---|---|
| `coilCompose` | `io.coil-kt:coil-compose:2.7.0` | `io.coil-kt.coil3:coil-compose:3.6.3` |
| 新增 | — | `io.coil-kt.coil3:coil-network-okhttp:3.6.3` |

**为什么值得做**：Coil 2 发布线止于 2.7.0（metadata 实测），已进入维护模式；3.x 是唯一活跃线。

**工作量实测**（`grep` 计数）：

| 面 | 量 | 迁移动作 |
|---|---|---|
| 引用 `coil.*` 的文件 | 38 个 | `coil.*` → `coil3.*`（import 批量替换） |
| `AsyncImage` / `rememberAsyncImagePainter` 调用点 | 40 处 | 仅 import 变化，调用签名不变 |
| 自定义 Fetcher | 3 个（LocalArtwork / Navidrome / Jellyfin） | `FetchResult`+`SourceResult` → `SourceFetchResult`；`Fetcher.Factory` 签名带 `PoolContext`；`ImageSource` → `coil3.decode.SourceImage`（okio `FileSystem` 参数） |
| `ImageLoader` 配置 | `di/AppModule.kt:256` | `.okHttpClient()` 移除 → 改 `components { add(OkHttpNetworkFetcherFactory(...)) }`；`respectCacheHeaders` 移到网络工厂；`MemoryCache`/`DiskCache`/`Precision`/`Size`/`CachePolicy` 换包名 |
| 其他 | `ImageCacheManager`、`SharedArtworkContentProvider`、`CoilBitmapLoader`、`MusicService` | `coil.imageLoader` → `SingletonImageLoader.get(context)`；内存缓存类型换包 |

**风险**：封面是核心链路（通知 / 车机 / 锁屏 / 小组件 / 内容提供者都读它），回归面广。
**建议**：单独提交、单独真机验证；若本次只想先吃掉低垂果实，**Tier 3 可延后**。

### Tier 4 — 明确不动（及理由）

| 项 | 理由 |
|---|---|
| `media3Session` / `media3Transformer` / `media3-ffmpeg-decoder` | 用户指定排除。注：`org.jellyfin.media3:media3-ffmpeg-decoder:1.9.0+1` 也是 media3 系，一并冻结 |
| `material3` 1.5.0-alpha25 → alpha28 | **升它 = 整条 Compose 栈掉进 1.13.0-alpha01**（§1.3）。除非明确要上 alpha28 的新组件，否则保持 |
| `lifecycleRuntimeKtx` 2.11.0 | 2.12.0-alpha03 才是最新，无 stable 可升 |
| `datastore` 1.2.1 | 1.3.0-alpha11 才是最新，无 stable 可升（且仅 `:baselineprofile` 用） |
| `workRuntimeKtx` 2.11.2 | 2.12.0-rc01 尚未 GA |
| `paletteKtx` 1.0.0 | 1.1.0-alpha01 未 GA |
| `glance` 1.3.0-alpha02 | 已是该线最新 |
| `kotlinxSerializationJson` 1.11.0 | 1.12.0-RC 未 GA |
| `netty` → 5.0.0.Alpha2 | alpha 跨大版本，且本项目只在约束里用 |
| `material-icons-extended` 1.7.8 | 上游冻结（该线最后一版），已被 painted icons 取代 |
| `accompanist` 0.37.3 | 已是最新（上游同样进入维护） |
| `taglib` / `jaudiotagger` / `vorbis-java-core` / `kuromoji` / `pinyin4j` | 均已是各自最新，久未维护 |
| `smooth-corner-rect` / `capturable` / `reorderable` / `wavy-slider` | 均是最新 |
| `timber` / `gson` / `retrofit` / `mockk` / `turbine` / `truth` | 均是最新 |
| `securityCrypto` 1.1.0 | 已是最新 stable。**另注**：Jetpack Security 上游已进入弃用轨道，将来需要另作打算，本次不动 |

## 4. 需要留意的三处风险

### 4.1 Compose 1.11.4 → 1.12.1（实质是 beta01 → stable）

`material3:1.5.0-alpha25` 已经把你顶到 `1.12.0-beta01`，所以这不是「跨越一个新版本」，
而是**从 beta 收口到 stable**。理论风险低于纸面。需要回看的点：`ExpressiveScrollBar`
（依赖 `LazyListState` 的 `canScrollForward/canScrollBackward`）、`Modifier.clip()` 语义、
以及 project 自定义的 `compose_stability.conf` 是否仍在编译期生效（构建日志里应有 stability 配置被读取）。

### 4.2 Navigation 2.9.8 → 2.10.1

上游标注的 behavior change 有两条：

1. `handleDeepLink` **只处理可识别的深链**，未识别的会被忽略；
2. `NavHost` 新增 `predictivePopEnterTransition` / `predictivePopExitTransition` 重载；
3. minSdk 提到 24（本项目 30，无影响）。

本项目 `AppNavigation.kt` 全部走 `composable(route)` 字符串路由，**没有 `navDeepLink`**，
第 1 条不适用。需要实测的只有：底部导航切换、`Transitions.kt` 的转场、返回手势。

### 4.3 六个 CVE 约束库

只改约束号，源码零引用，但它们是**为覆盖传递依赖的漏洞版本而存在**的。升级后建议跑一次
`:app:dependencies` 确认**没有出现比约束更低的解析结果**（即约束仍然生效）。

## 5. 执行顺序与提交拆分

| # | 提交 | 内容 | 验证 |
|---|---|---|---|
| A | `chore(deps): bump Compose to 1.12.1 and the rest of the stable line` | Tier 1 全部 + BOM 对齐 | 编译 + 单测 + 全量 smoke（§6） |
| B | `chore(build): bump AGP to 9.4.0, Kotlin to 2.4.20, KSP to 2.3.12` | Tier 2 | 编译 + 单测（R8 走 release 已覆盖） |
| C | `refactor(image): migrate Coil 2 to Coil 3` | Tier 3 | 封面专项 smoke（§6.3） |

A 与 B 可以合成一个提交（都不动源码），但不建议和 C 混 —— C 的回归面是独立的。
按仓库约定：中间提交不单独验证，**只在最终 commit 上构建一次**。

## 6. 验证清单

### 6.1 构建与静态

```bash
./gradlew :app:testDebugUnitTest      # 含 38 例歌词单测、ChangelogParserTest
./gradlew :app:assembleRelease
./gradlew :app:lintDebug              # 语种家族已降 warning，核对 errors 数没反弹
```

产物核对（AGENTS.md 的既有流程）：
`aapt2 dump badging | grep ^package` + `apksigner.bat verify --print-certs -v`（应 `CN=pisces312`）。

### 6.2 运行时 smoke（模拟器 pixel6 或真机）

1. 播放 / 暂停 / 切歌 / 队列与通知
2. 车机歌词标题链路（debug 包，日志有 `car lyric title` 系列 `d` 级）
3. 统计落盘（`finalizeCurrentSession` → 进 Stats 页看数字）
4. AI mix 流式生成（含思考过程展示）
5. 云盘流播放（`CloudStreamProxy`，覆盖 ktor 3.6.0）
6. 小组件（Glance）与快捷设置

### 6.3 Coil 3 专项（仅提交 C）

本地封面 / 内嵌封面 / Navidrome 封面 / Jellyfin 封面 四条 Fetcher 路径各点一次；
再看通知、锁屏、车机、`SharedArtworkContentProvider` 的封面是否正常；
`ImageCacheManager` 的清缓存与内存缓存命中。

## 7. 回滚

改动集中在 `gradle/libs.versions.toml`（A / B）与 8 个文件 + 若干 import（C），
`git revert <sha>` 即可回到当前状态。无 DB schema 变更、无签名配置变更。

---

## 8. 执行结果（2026-09-18，Tier 1 + Tier 2 已落地；Tier 3 按裁定延后）

改动面：**只有 `gradle/libs.versions.toml`，26 行**（无任何源码改动）。

### 8.1 生效确认（`releaseRuntimeClasspath` 解析值）

| 组件 | 升级前解析 | 升级后解析 |
|---|---|---|
| compose ui / foundation / animation | **1.12.0-beta01**（声明 1.11.4） | **1.12.1** |
| compose material3 | 1.5.0-alpha25 | 1.5.0-alpha25（按裁定不动） |
| material3.adaptive | 1.2.0 | **1.3.0** |
| navigation-compose / runtime | 2.9.8 | **2.10.1** |
| room-runtime | 2.8.4 | **2.8.5** |
| paging-runtime | 3.5.0 | **3.5.1** |
| appcompat | 1.7.1 | **1.8.0** |
| okhttp | 5.4.0 | **5.5.0** |
| ktor-server-core | 3.5.2 | **3.6.0** |
| kotlin-stdlib | 2.4.10 | **2.4.20** |
| kotlinx-collections-immutable | 0.5.1 | **0.5.2** |
| snakeyaml | 2.4 | **2.7** |
| coil | 2.7.0 | 2.7.0（延后） |

### 8.2 验证结论

| 项 | 结果 |
|---|---|
| `:app:testDebugUnitTest` | **786 例，0 失败 / 0 错误 / 0 跳过**（132 个测试类） |
| `:app:assembleRelease` | **BUILD SUCCESSFUL**，8m52s，88 个任务全执行 |
| 产物 | `pixelplayeross-arm64-v8a-0.4.2-pisces.1-release.apk`，37.7 MB（AGENTS.md 记录的 0.4.1 release 为 37.5 MB，同一量级） |
| `aapt2 dump badging` | `versionCode=400201` / `versionName=0.4.2-pisces.1` / `compileSdk=37` / `native-code='arm64-v8a'` |
| `apksigner verify` | `Verifies`，v2 scheme **true**（v1 false 属正常） |
| `:app:lintDebug` | **4 errors**（= 既有基线，见 §8.3） |
| 模拟器 smoke（pixel6，release 包） | 启动无崩溃；Library / Settings / Now Playing 三屏渲染正常；`ExpressiveScrollBar` 正常显示；封面 + Palette 动态取色正常；播放进入 `state=PLAYING(3)` 且 position 推进；media3 媒体通知（`category=transport`, `media3_group_key`）正常下发；全程 logcat 无 `FATAL` / `AndroidRuntime` 异常 |

### 8.3 需要知道的两件事

1. **本次升级引入 1 条新弃用告警**：`MainActivity.kt` 的 `currentWindowAdaptiveInfo` 被弃用，
   提示改用 `currentWindowAdaptiveInfoV2`。来源是 adaptive **1.3.0-alpha10 起**的 API 变更
   （1.2.0 → 1.3.0 正好踩到）。仅告警、不阻塞；**V2 版本默认支持 L/XL 宽度档，而本项目已明确
   不以平板/折叠屏为目标**，因此迁移的收益约等于零 —— 建议保持现状，或在下次清理弃用项时一起处理。
   lint 的 4 个 error 与既有基线一致，未被升级改变：
   `SerendipityContextCollector`(MissingPermission)、`HomeScreen`(NonObservableLocale)、
   `CarLyricTitleController`(UnstableApi ×2)。
   > 其余 50 条编译告警（`EncryptedSharedPreferences`、`DockedSearchBar`、`hiltViewModel`、
   > `LocalClipboardManager`、`Icons.Rounded.Sort` 等）指向的库本就已是升级前的版本或本次未改，
   > 属既有弃用噪音。**注意**：无法用旧日志逐条 diff —— 之前的日志来自增量编译，只重编了改动文件，
   > 只报出 1 条告警。

2. **CVE 约束库当前是「空转」**：约束块覆盖 6 个库共 10 条（netty ×4、bouncycastle ×2、
   commons-lang3、jdom2、jose4j、httpclient），它们 **在 `releaseRuntimeClasspath` 里一个都不存在**
   （升级前后都是 0 次命中，实测）。也就是说 `app/build.gradle.kts` 的 `constraints { }` 块
   只在「有别的传递依赖把它们拉进图」时才生效，而现在没有任何依赖拉它们
   （ktor 服务端走的是 CIO 引擎，不依赖 netty）。**本次对这几个数字的改动对产物零影响**；
   那 10 条约束要不要留是独立话题（建议保留 —— 价值在于将来某个依赖把它们带进来时兜底）。

3. **`libs.versions.toml` 与 BOM 的同步关系**：本次把 `composeUi/foundation/animation` 与
   `composeBom` 一起改到同一套（1.12.1 / 2026.09.00），是因为**只改其中一个没有意义** ——
   Gradle 在「显式声明」与「BOM 约束」之间取较高者，任何一方落后都会被另一方顶掉。
   以后升 Compose，**两者必须同批改**。

---

## 9. 预发布版专项核查（2026-09-18，写于「先给方案」阶段 —— 当时的执行结论见 §10）

> 触发：核对「除 media3 外，其他原本是 alpha/beta 的库是否都升到了正式版」。
> 结论：**§3/§8 的 26 行改动只收口了 Compose 栈的 beta**，其余预发布项分文未动 ——
> 其中 1 项确实可升（material3）、1 项已是最新（glance）、1 项是死条目（composeTesting）、
> 2 项可加约束升（compose-remote / graphics-path）、1 组由第三方库固定（jetbrains compose）。

### 9.1 两个问题的直接回答

**① material3 现在跑哪版？** —— `1.5.0-alpha25`，**本次没升**（`gradle/libs.versions.toml:33`）。
上游最高可用 `1.5.0-alpha28`（Google Maven 的 `<latest>` / `<release>` 均指向它）。

**② 其他预发布库升了吗？** —— 没有，一个都没动。`releaseRuntimeClasspath` 上共 5 组
「最终解析即为预发布」的坐标（用 `:app:dependencies` 输出的箭头值判定，非 toml 声明值）：

| # | 坐标 | 实际解析 | 上游最新 | 来源 | 判定 |
|---|---|---|---|---|---|
| 1 | `androidx.compose.material3:{material3, -android, -ripple, -ripple-android}` | `1.5.0-alpha25` | `1.5.0-alpha28` | **自身声明**（`material3`） | **可升**，见 §9.4 |
| 2 | `androidx.glance:{glance, -appwidget, -appwidget-proto, -appwidget-external-protobuf, -material3}` | `1.3.0-alpha02` | `1.3.0-alpha02` | **自身声明**（`glance`） | **已是最新**（1.3.0 线至今只有 alpha01/alpha02，无 stable） |
| 3 | `androidx.compose.remote:{remote-core, remote-creation, -creation-android, -creation-core}` | `1.0.0-alpha14` | `1.0.0-alpha19` | 传递：**glance-appwidget** | 可升（需加约束） |
| 4 | `androidx.graphics:graphics-path` | `1.1.0-rc01` | **`1.1.0`（正式版已发布）** | 传递：glance-appwidget → compose-remote | 可升（需加约束） |
| 5 | `org.jetbrains.compose.material3:material3`、`org.jetbrains.compose.ui:ui-backhandler(-android)` | `1.9.0-beta03` | — | 传递：**`wavy-slider:2.2.0`**（该库已最新） | **不可单独升** |

另有 **1 项已经解决**：Compose 栈（`ui` / `foundation` / `animation` / `runtime` / `ui-text` /
`ui-util` / `foundation-layout` / `material-ripple`）升级前**实际解析为 `1.12.0-beta01`**，
本次已收口到 **`1.12.1` stable** —— 这是上次唯一真正「beta → stable」的项。

### 9.2 预发布为什么会在 classpath 上（来源链，实测）

```
androidx.glance:glance-appwidget:1.3.0-alpha02
  ├── androidx.compose.remote:remote-creation → -creation-android → remote-core   1.0.0-alpha14
  └── androidx.compose.remote:* → androidx.graphics:graphics-path                 1.1.0-rc01
ir.mahozad.multiplatform:wavy-slider:2.2.0
  ├── org.jetbrains.compose.material3:material3                                   1.9.0-beta03
  └── org.jetbrains.compose.ui:ui-backhandler(-android)                           1.9.0-beta03
```

- **`glance-appwidget` 是唯一的「预发布放大器」**：它把 `compose-remote (alpha)` 和
  `graphics-path (rc)` 一起拖进 release classpath。而 glance 自身最新只有 `1.3.0-alpha02`
  ⇒ **靠升 glance 消化不掉这两项**，只能加显式约束。
- `graphics-path` 是「被动卡在 rc」的典型：**stable `1.1.0` 已发布**，但图里没有任何一方请求它
  （`ui-graphics:1.12.1` 只请求 `1.0.1`，`compose-remote` 请求 `1.1.0-rc01`，取较高者得 rc01）。
  Gradle 不会自动升到 stable，只会取图中最高请求值。

### 9.3 material3 各候选版本的实测差异

> 方法：下载 `androidx.compose.material3:material3-android:<v>-sources.jar` 逐行核对 + 读 AAR 的 pom
> 传递依赖。**不是 changelog 推断**。

| 项 | alpha25（当前） | alpha26 | alpha27 | alpha28 |
|---|---|---|---|---|
| 自身请求的 Compose | `1.12.0-beta01` | `1.12.0-beta01` | `1.12.0-beta01` | **`1.13.0-alpha01`** |
| 本项目 Compose 栈实际解析 | **1.12.1 stable** | 1.12.1 stable | 1.12.1 stable | **1.13.0-alpha01**（整栈进 alpha） |
| `ToggleButtonDefaults.toggleButtonColors()` | 存在 | 存在 | **改名 `colors()`**（旧名 0 处） | 同 27 |
| 旧 member `ExposedDropdownMenu` | 正常 member（3 重载） | **HIDDEN 弃用** + 顶层扩展函数 | 同 26 | 同 26 |
| `TopAppBarDefaults.enterAlwaysScrollBehavior(state=)` | 存在 | — | **存在 ✓** | **存在 ✓** |
| `TopAppBarDefaults.exitUntilCollapsedScrollBehavior(state=)` | 存在 | — | **存在 ✓** | **存在 ✓** |
| 基础 `ToggleButton(checked, onCheckedChange, …)` | 存在 | — | — | **存在 ✓**（仅移除其它旧重载） |
| 无状态 `Slider` / `RangeSlider` | — | — | — | **弃用**（稳定重载=普通告警；`@ExperimentalMaterial3Api` 旧重载=HIDDEN） |
| 对本项目的收益 | — | BottomAppBar 转 stable、ExposedDropdownMenu 小屏高度崩溃修复 | + `TimeInputDefaults`、`SelectableDropdownMenuItem`、ToggleButton 新 overload | + expressive TimePicker 大字体 AM/PM 截断修复、ScrollField focus ring |

**受影响的代码点（已实测定位，共 3 行）**：

| 改动 | 文件:行 | 规模 |
|---|---|---|
| `ToggleButtonDefaults.toggleButtonColors(...)` → `colors(...)`（alpha27 起） | `SearchScreen.kt:1235` | 1 行 |
| 新增 `import androidx.compose.material3.ExposedDropdownMenu`（alpha26 起为顶层扩展函数） | `PlaylistCreationDialogs.kt`、`AiSettingsScreen.kt` | 2 行（调用点共 5 处，均在 `ExposedDropdownMenuBox` scope 内） |

**已排除、不受影响的项**（逐条 grep 实测）：`LocalMotionScheme`（0 引用，项目用
`MotionScheme.expressive()`）、`SearchBarScrollBehavior` 的 offset 变量（`SearchScreen` 未访问）、
`BottomAppBar`（3 文件，仅注解变冗余）、`Carousel`（项目为 standalone 自研实现
`RoundedParallaxCarousell.kt`，不引 material3 版本）、`SecureTextField` / `ScrollField`（0 引用）、
`rich→vibrant` 重命名（项目只用基础 `TimePicker`）、`ShortNavigationBar` / `WideNavigationRail`（未用）、
`ExposedDropdownMenu` 被删的 2 个重载（项目用的是保留签字形式）。

### 9.4 方案

**档 A（推荐）—— 只动 material3：`alpha25` → `alpha27`**

- Compose 栈**保持 `1.12.1` stable**（alpha27 请求的 `1.12.0-beta01` 被显式声明顶住）
- 改动面：`libs.versions.toml` **1 行** + **3 行代码**（1 处改名 + 2 行 import）
- 拿到 alpha26 + alpha27 的全部修复与新 API，且**不把 Compose 拖进 alpha**
- 不选 alpha28：它会把整条 Compose 栈顶到 `1.13.0-alpha01`，等于把刚收口到 stable 的
  Compose 重新打开（要上就必须同批改 `composeUi`/`foundation`/`animation` 声明，
  否则 toml 与实际解析再次「说谎」—— 见 §8.3 第 3 条）

**档 B（可选，与 A 独立）—— 消化「被动卡住」的项**

在 `app/build.gradle.kts` 的 `constraints { }` 中加：

```
androidx.graphics:graphics-path:1.1.0                  // rc01 → stable（纯收益）
androidx.compose.remote:remote-core:1.0.0-alpha19      // alpha14 → alpha19（仍是 alpha，收益有限）
```

建议**只加 `graphics-path` 那一条**；`compose-remote` 只是「换成更新的 alpha」，
留到 glance 出 stable 时一并解决。

**档 C（不推荐）—— material3 → `alpha28`**：代价见 §9.3 第 1/2 行 + 12 个文件新增 `Slider`
弃用告警；收益（TimePicker 大字体修复等）对本项目均为边缘项。

**档 D（顺手澄清）—— `composeTesting = "1.0.0-alpha03"` 是死条目**

该 version 声明在 `libs.versions.toml:72`，**全文件仅此一处、无任何 library 引用**
（实测）。测试依赖实际走 `platform(libs.androidx.compose.bom)` +
`androidx-ui-test-junit4` / `androidx-ui-test-manifest`（`version.ref = "composeUi"` = 1.12.1）。
⇒ 它既不是「待升级的 alpha」也不影响产物；建议删除以免后续误判（独立话题，可单独处理）。

### 9.5 若执行档 A，验证清单

1. `./gradlew :app:compileDebugKotlin` —— 先看编译是否只剩弃用告警
2. `:app:testDebugUnitTest` —— 786 例基线不变
3. `:app:lintDebug` —— 仍为 4 errors 基线
4. 模拟器 smoke 重点：**搜索页筛选 `ToggleButton`** 与 **AI 设置 / 歌单创建弹窗的
   `ExposedDropdownMenuBox`**（两处即改动点）
5. 复核 `:app:dependencies --configuration releaseRuntimeClasspath`：Compose 仍 `1.12.1`、
   material3 为 `1.5.0-alpha27`

### 9.6 明确不动的项

| 项 | 理由 |
|---|---|
| `glance 1.3.0-alpha02` | 已是上游最新（`<latest>` = `<release>` = 1.3.0-alpha02），无 stable 可落 |
| `org.jetbrains.compose.*:1.9.0-beta03` | 由第三方 `wavy-slider:2.2.0`（已最新）固定，非本项目可控 |
| Coil 2.7.0 | 见 §3 Tier 3，另立任务 |

## 10. 档 A + 档 B 执行结果（2026-09-18，已落地）

§9 是「先给方案」阶段写的，**本节是执行后的实测结论**。执行范围 = 档 A（material3
`alpha25` → `alpha27`）+ 档 B（只加 `graphics-path`，未加 `compose-remote`）。

### 10.1 改动面（5 个文件，代码仅 5 行）

| 文件 | 改动 |
|---|---|
| `gradle/libs.versions.toml` | `material3 = "1.5.0-alpha25"` → `"1.5.0-alpha27"`；新增 `graphicsPath = "1.1.0"` 与 `androidx-graphics-path` 条目 |
| `app/build.gradle.kts` | `constraints { }` 追加 `implementation(libs.androidx.graphics.path)`（附 3 行注释说明为什么必须显式抬版） |
| `presentation/screens/SearchScreen.kt` | `:1235` `ToggleButtonDefaults.toggleButtonColors(` → `ToggleButtonDefaults.colors(`（alpha27 起旧名已移除） |
| `presentation/components/PlaylistCreationDialogs.kt` | 新增 `import androidx.compose.material3.ExposedDropdownMenu`（**注意实际路径是 `components/`，§9.3 写的 `screens/` 是笔误**） |
| `presentation/screens/AiSettingsScreen.kt` | 同上，新增该 import（覆盖 2 处调用） |

改动的**逐条签名核实**（下载 `material3-android-<v>-sources.jar` 读 Kotlin 源码，不用 changelog）：

- alpha27 的 member `ExposedDropdownMenu` 确为 `DeprecationLevel.HIDDEN` —— HIDDEN 级别
  **在源码层完全不可解析**，所以必须 import 顶层扩展函数 `ExposedDropdownMenuBoxScope.ExposedDropdownMenu`
- 该扩展函数的参数名与本项目写法一致（`expanded` / `onDismissRequest`）
- `ToggleButtonDefaults.colors()` 保留了项目用到的 4 个命名参数
  （`containerColor` / `contentColor` / `checkedContainerColor` / `checkedContentColor`）
- `ExposedDropdownMenuAnchorType`（仍为 value class）、`ExposedDropdownMenuDefaults.TrailingIcon(expanded, modifier)`、
  `Modifier.menuAnchor(type, enabled = true)` 均未变

### 10.2 生效确认（`releaseRuntimeClasspath` 最终解析值）

| 模块 | 改动前 | 现在 | 说明 |
|---|---|---|---|
| `androidx.compose.material3:material3`(+`-android`/`-ripple`) | 1.5.0-alpha25 | **1.5.0-alpha27** | 档 A |
| `androidx.compose.ui:ui` / `foundation` / `animation` / `runtime` | 1.12.1 | **1.12.1（未变）** | alpha27 只请求 `1.12.0-beta01`，被显式声明顶住 ✅ |
| `androidx.graphics:graphics-path` | 1.1.0-rc01 | **1.1.0** | 档 B，`(c)` 约束生效 |
| `androidx.compose.material3.adaptive:adaptive` | 1.3.0 | 1.3.0 | 未变 |

`graphics-path` 的来源链（`dependencies` 实测，两个请求者都被抬到 1.1.0）：

```
ui-graphics:1.12.1           → graphics-path:1.0.1     -> 1.1.0   (ui-graphics-android-1.12.1.pom 实测有此依赖)
glance-appwidget:1.3.0-alpha02
  └─ compose.remote:remote-creation:1.0.0-alpha14 → graphics-path:1.1.0-rc01 -> 1.1.0
\--- androidx.graphics:graphics-path:1.1.0 (c)      ← 本项目的 constraint
```

**档 B 的功能增量其实只有 `1.1.0-rc01` → `1.1.0` 正式版**：改动前图里已经有 rc01 请求
（`glance-appwidget` 带进来的），所以 Compose 的 `PathParser` **在改动前也跑在 1.1.0 线上**。
换句话说档 B 不会引入新的运行时行为，只是把「rc」收成「final」——
这也是它被归为「纯收益」的原因。再次印证 §8.3 第 3 条：**判某库跑哪版只能看解析结果。**

### 10.3 验证结论（全绿）

| 项 | 结果 |
|---|---|
| `:app:testDebugUnitTest` | **786 例，0 失败 0 错误**（132 个类），与升级前基线一致 |
| `:app:assembleRelease` | BUILD SUCCESSFUL，4m19s，37.6 MB |
| 产物核对 | `0.4.2-pisces.1` / `400201` / `arm64-v8a`；`apksigner` → `Verifies` + **v2 true** + `CN=pisces312` |
| 编译告警 | **51 条，唯一集合与 material3 alpha25 时逐条 diff 完全一致（0 新增 0 消失）** |
| `:app:lintDebug` | **4 errors = 既有基线**（SerendipityContextCollector 权限、HomeScreen 非观察式 locale、CarLyricTitleController ×2 `UnstableApi`），未被本次改动影响 |
| 模拟器 smoke | 启动无崩溃；Home / Search / Library / Settings / AI 设置 / Now Playing 全部正常；播放进 `PLAYING(3)`、position 推进；media3 会话正常；logcat **无 FATAL、无本应用 W/E** |

改动的两处 UI 是**运行时真点过**的（不只是编译过）：

- **搜索页筛选 `ToggleButton`**：查询后 5 个筛选按钮（All/Songs/Albums/Artists/Playlists）正常渲染，
  点 `Songs` → 选中态切到 `checkedContainerColor`（primary）+ check 图标出现在选中项、
  未选中项回落默认容器色；再点 `Albums` 亦然。`colors()` 改名后配色语义无回归。
- **AI 设置 `ExposedDropdownMenu`**：Provider 下拉展开正常（菜单在锚点下展开、trailing 箭头 ▼→▲）、
  选中 `Volcano Engine (Ark)` → Provider 与 Endpoint 联动切到 `ark.cn-beijing.volces.com/api/v3`，
  **再切回 `Xiaomi MiMo` 完整还原**（Provider/Endpoint/Model 全部回到原值）。
  已验证该切换可逆：`AiPreferencesRepository` 的 key/model/url 全部按 provider 分槽存储，
  `setProvider` 只写 provider 名，不销毁其他 provider 的数据。
  `Model` 字段是「可编辑 + 有待取列表才弹菜单」形态，`availableModels` 为空时只聚焦输入，属预期。

### 10.4 仍未收口的预发布项（本次按裁定不动）

| 项 | 现状 | 何时能落 |
|---|---|---|
| `androidx.compose.remote:*` | 1.0.0-alpha14（上游 alpha19） | 等 `glance` 出 stable |
| `androidx.glance:*`（5 坐标） | 1.3.0-alpha02 = 上游最新 | 等上游 |
| `org.jetbrains.compose.*` | 1.9.0-beta03 | 被 `wavy-slider:2.2.0` 固定，非本项目可控 |
| `composeTesting = "1.0.0-alpha03"` | 死条目（见档 D） | 可随手删，独立话题 |
