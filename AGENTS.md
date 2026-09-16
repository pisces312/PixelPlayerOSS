# AGENTS.md

PixelPlayerOSS — Android 音乐播放器（100% Kotlin，Jetpack Compose + Material 3）。
本仓库是 `PixelPlayerHQ/PixelPlayerOSS` 的 fork：`pisces312/PixelPlayerOSS`。Gradle 模块：`:app`、`:baselineprofile`。
包名 `com.lostf1sh.pixelplayeross`，许可证 **GPL-3.0-or-later**。

## 仓库历史关系（重要，动手前必读）

| 仓库 | 许可 | 关系 |
|---|---|---|
| `PixelPlayerHQ/PixelPlayer` | **专有**（禁止再分发源码/二进制及衍生作品） | 上游闭源版，含 Telegram / QQ 音乐 / Gemini / GMS 等集成 |
| `PixelPlayerHQ/PixelPlayerOSS` | GPL-3.0-or-later | 官方开源版，**已剥离** Telegram、NetEase、QQ 音乐、Google Drive、Gemini、Cast、Wear OS、Play 计费、Firebase、Crashlytics、GMS；云端仅保留自建 Navidrome/Subsonic 与 Jellyfin |
| `pisces312/PixelPlayer`（`china-only` 分支，本地 `D:\3rd-party-projects\PixelPlayer`） | 专有代码的派生 | 我自己的 fork，包名 `com.theveloper.pixelplay`，在上面开发了一批新功能 |

- **三者 git 历史完全独立**：`git merge-base` 为空 → **不能 cherry-pick / merge**，跨仓库只能手工重新落地。
- **无 AI 子系统**：OSS 用本地 NLP（`data/playlist/nlp/PlaylistIntentEngine`）替代云端 AI 生成播放列表。
- **版权红线**：把 china-only 的功能搬进来时，**原创算法/解析层**（parser、matcher、分桶、日志收集等）可搬，提交注明 `Original work, GPL-3.0-or-later`；**glue 层**（ViewModel / DI / DAO / 设置项 / UI 接线 / strings）必须在 OSS 侧重写；**禁止整文件复制专有 PixelPlayer 的实现**。

## 分支

- `main`：本 fork 唯一的开发与发布分支。仓库只有 `pisces312` 一名维护者，
  新功能**直接提交到 `main`**，不要求先开功能分支（2026-09-11 起取代早先
  「`main` 不日常开发、一律经 `pisces/port` 中转」的做法）。每功能一个 commit，英文 message。
- `pisces/port`：遗留的移植集成分支，提交已全部并入 `main`（`main` 是它的直接后代）。
  仍可用于长期并行开发，但不再是必需的中转站。

## 技术栈与版本

- Gradle 9.6.1（wrapper 已改腾讯云镜像）、AGP 9.3.1、Kotlin 2.4.10、KSP 2.3.10、JDK 21（JBR）。
- compileSdk = targetSdk = 37，minSdk = 30；版本号在 `gradle.properties` 的 `APP_VERSION_NAME` / `APP_VERSION_CODE`
  （**以该文件为准，本文不抄写具体值** —— 每发一版就变，抄在这里必然过期）。
- Media3 1.10.1、Room 2.8.4（**数据库 schema 版本以 `PixelPlayerDatabase.kt` 的 `version` 为准，本文不抄写**；
  任何实体变更都要把 `version` +1 并新增 migration）、Hilt、OkHttp/Retrofit、TagLib + JAudioTagger 元数据。
- 依赖仓库为官方 `google()` / `mavenCentral()`（首次构建较慢，无国内镜像）。

## 目标设备与屏幕尺寸（UI 布局前提）

**只针对一类设备：主流直板手机竖屏。** 参照机 = 本机 AVD `pixel6`（411 × 914 dp）。
**明确不作为目标**（2026-09-16 定；此后不再为它们加兜底）：

- 短屏 —— 横屏、分屏 / 多窗口、小折叠外屏，即竖屏可用高度明显低于 ~640 dp 的情况；
- 极值字体缩放（系统字体 ≥ 1.3）；
- 平板 / 折叠屏展开态。

推论（照此判断，不必逐个重新论证）：

- 布局按参照机的可用高度设计即可，**不为"更小的屏幕还塞不塞得下"预留弹性**；
- 某处布局在小屏被裁 → **默认不算缺陷**：说一句即可，不要顺手改成
  `weight(1f, fill = false)` 之类的自适应；
- **既有兜底保留，不要回退**：`AiMixSheet.InputPhase` 的 `verticalScroll` 同时服务软键盘弹出
  （主流机型也需要），sheet 的 `skipPartiallyExpanded` 与各处 `heightIn(max = …)` 同理 ——
  它们同时改善主流机型的观感，不属于纯小屏适配。

已确认接受的代价：`AiMixSheet.ResultPhase` 是「固定行 + `heightIn(max = 320.dp)` 列表」，
短屏 / 大字号下底部「重新生成」理论上会被裁。**决定不修**；备选方案（滚动区改
`weight(1f, fill = false)`）见 `docs/ai-thinking-persistence-plan.md` §7.6，已否决。

## 常用命令（PowerShell，仓库根目录）

```powershell
.\gradlew.bat :app:assembleRelease              # 默认构建（可交付；无签名环境变量时产出未签名 APK）
.\gradlew.bat :app:assembleDebug                # 仅按需：release 复现不了 / 难定位时
.\gradlew.bat :app:testDebugUnitTest            # 单元测试（JUnit 5，JVM）
.\gradlew.bat :app:lintDebug                    # Lint（release 构建不做）
.\gradlew.bat :app:connectedDebugAndroidTest    # instrumented 测试（需连真机 / 模拟器）
```

- **debug / release 可共存**：`debug` 带 `applicationIdSuffix = ".debug"` + `versionNameSuffix = "-debug"`，应用名 `PixelPlayerOSS [D]`（`app/src/debug/res/values/strings.xml`），
  图标走 `app/src/debug/res` 的红底覆盖（只覆盖 `ic_launcher_background` 与 legacy 方/圆图标，foreground 与 `mipmap-anydpi-v26` XML 沿用 main）。
  改了 main 图标后重跑根目录 `gen_debug_icons.py`（Pillow）重新生成 debug 图标。
- **默认构建 `release`；`debug` 只在按需排查时构建**：R8 只跑在 `release` / `benchmark` 上，
  `debug` 是 `isMinifyEnabled = false` + `isShrinkResources = false`。
  理由：debug 的用途是「release 上复现不了、又难定位」的问题，此时未混淆的栈帧逐行对应源码，
  也不会有 R8 改名 / facade 合并造成的干扰；代价只有体积，而且只在自己点名要时才付。
  体积实测（arm64-v8a、0.4.1、同一份代码）：**release 37.5 MB / debug 无 R8 152.1 MB /
  debug 带 R8 69.2 MB**（差别几乎全在 dex：14.5 MB / 128.0 MB / 45.0 MB）。
  **不要再给 `debug` 打开 R8**：instrumented test 的 test APK 是独立程序，按**未混淆的原名**调用
  app APK 里的类与成员，而被 minify 的 app 恰好把这些改写或删掉（Kotlin facade、`RoomDatabase.close()`、
  app 自己不调用的 DAO 方法），逐个 keep 是无底洞 —— 详见下方「instrumented 测试」。
- **ABI 只构建 `arm64-v8a`**（`pixelplayer.enableAbiSplits` 默认 true）；关掉该 property 时文件名中的 abi 段为 `universal`。
- **模拟器可直接运行 `arm64-v8a` 产物（已实测确认，2026-09-14）**：本机 AVD 安装默认构建的
  `pixelplayeross-arm64-v8a-*.apk` 即可运行，**不要**为了"让模拟器装得上"去构建 universal ——
  `-Ppixelplayer.enableAbiSplits=false` 会覆盖并清掉同目录的 arm64 产物。
  真机/模拟器验证统一用 `.\gradlew.bat :app:assembleRelease`（debug 与 release 的 applicationId 不同，可共存）。
- **instrumented 测试**：`.\gradlew.bat :app:connectedDebugAndroidTest`，跑在 `debug` 变体上
  （`testBuildType` 保持默认；正因 debug 不 minify，这套测试才跑得起来）。
  只跑某几个类：追加 `-Pandroid.testInstrumentationRunnerArguments.class=全限定类名,逗号分隔`。
  唯一跑不了的：`SyncWorkerTest` 需要 MockK inline（JVMTI agent），ART 拒绝从 app cache 加载，
  属既有测试设计问题，与构建配置无关。
- **APK 命名**：`pixelplayeross-<abi>-<APP_VERSION_NAME>-<buildtype>.apk`（`androidComponents.onVariants` 设置 `outputFileName`），
  例 `pixelplayeross-arm64-v8a-0.3.0-debug.apk`。改命名规则改 `app/build.gradle.kts`。
- 签名走 `keystore.properties`（或 `pixelplayer.disableReleaseSigning=true` 跳过），勿提交密钥。详见下方「签名与发布」。
- `GRADLE_USER_HOME=D:\dev\.gradle`；wrapper 的 Gradle 发行版走腾讯云镜像。

## 签名与发布（自用 fork）

**凭据只存在于环境变量**，严禁写入仓库内任何文件（含 `docs/`、release notes、memory）：

| 环境变量 | 用途 |
|---|---|
| `KEY_STORE` | keystore 文件名（备查） |
| `KEY_STORE_LOCATION` | keystore 绝对路径（Windows 风格） |
| `KEY_STORE_PASSWORD` | keystore 口令 |
| `KEY_ALIAS` | 密钥别名（`pisces312`） |
| `KEY_PASSWORD` | 密钥口令 |

`app/build.gradle.kts` 的签名块优先读仓库根的 `keystore.properties`（已 gitignore、不入库），
**文件不存在时回退到上表的环境变量**（走 `providers.environmentVariable(...)`，配置缓存可跟踪）。
因此本机发版**不需要在任何文件里落盘口令**，环境变量在就能直接构建：

```bash
./gradlew :app:assembleRelease --no-configuration-cache
```

只有在环境变量拿不到、又必须用文件时才生成 `keystore.properties`（注意 `storeFile` 必须正斜杠）：

```bash
STORE_FWD=$(printf '%s' "$KEY_STORE_LOCATION" | tr '\\' '/')
printf 'storeFile=%s\nstorePassword=%s\nkeyAlias=%s\nkeyPassword=%s\n' \
  "$STORE_FWD" "$KEY_STORE_PASSWORD" "$KEY_ALIAS" "$KEY_PASSWORD" > keystore.properties
```

四个坑（都踩过）：

- **`storeFile` 必须正斜杠**（写进 `keystore.properties` 时）：`Properties.load()` 把 `\` 当转义符，
  `D:\a\b` 解析成 `D:ab`，路径失效 → `hasReleaseSigningConfig` 为 false → **BUILD SUCCESSFUL
  但 APK 完全没签名**。只有 `tr '\\' '/'` 在本机可靠（bash 的 `${VAR//\\//}` 与 `sed` 都会漏转）。
  走环境变量回退时无此问题。
- **用 `keystore.properties` 时改了它必须 `--no-configuration-cache` 重跑**：`Properties().load(File)`
  是未声明输入，配置缓存不会跟踪其变化，会沿用旧的签名判定。环境变量路径已用 `providers` 声明，无此坑。
- **构建成功 ≠ 已签名**，唯一可信判据是
  `apksigner verify --print-certs -v <apk>` → `Verifies` + `v2 scheme: true` + `CN=pisces312`
  （只有 v2、没有 v1 是正常的）；`aapt2 dump badging` 核对 `versionName` / `versionCode` / `native-code`。
- 本机 build-tools：`D:/dev/android_sdk/build-tools/37.0.0/`。

发版链路：`main` → 版本号提交 → `tag v<版本名>` → `assembleRelease` → `gh release create`。
版本号带 `-pisces.N` 后缀，版本码沿用 `主版本 * 100000 + 序号`。

> 上游的 `.github/workflows/alpha-release.yml`（push `main` 自动造 `v<版本>-alpha.N` 预发布）
> **已在本 fork 删除**：它跑在 `blacksmith-4vcpu-ubuntu-2404` 上，本 fork 没有对应 runner，
> 而 `main` 现在是日常开发分支，留着会让每次代码提交都产生一个挂起/失败的 run。
> 因此本地发布**不再需要**在 message 里写 `[skip ci]`。
> 同一 runner 的 `pr-build.yml` 只在 PR 时触发（本 fork 不开 PR），`gitlab-mirror.yml` 有
> `github.repository == 'PixelPlayerHQ/PixelPlayerOSS'` 守卫会自动跳过，二者均无影响。

## 新增一个设置开关（必读，缺一不可）

OSS 有**设置搜索**，开关不注册就搜不到：

1. `data/preferences/UserPreferencesRepository.kt`：`PreferencesKeys` 加 key → `xxxFlow` → `setXxx()`。
2. `presentation/viewmodel/SettingsViewModel.kt`：`SettingsUiState` 字段 → `SettingsUiUpdate.Group2` 字段 → `init` 里 combine 的 flow 列表**末尾**追加 → `values[N]`（索引按位置对应，只能追加不能插队）→ `state.copy()` → `setXxx()`。
3. `presentation/screens/SettingsCategoryScreen.kt`：对应 `SettingsCategory` 分支里加 `SettingsSubsection` + `SwitchSettingItem`，带 `Modifier.settingHighlight("item_xxx", highlightKey)`。
4. `presentation/settings/search/SettingsRegistry.kt`：注册 `SettingSpec`（`type = SettingType.SWITCH`，带 `getValue` / `onToggle` 与关键词），`itemKey` 与第 3 步的 highlight key 一致。
5. `res/values/strings_settings.xml` 加 title/subtitle，同时加 `values-zh-rCN/` 中文版；其他语言留给翻译流程，不要手写。

## 共享 PlayerViewModel（必读：别加回 `= hiltViewModel()` 默认值）

全应用**只有一个** `PlayerViewModel`：`MainActivity` 以 `by viewModels()` 持有（activity 级），传给
`AppNavigation`，再经 `ScreenWrapper` 与显式参数逐层下传。**screen / component 的参数上不要写
`playerViewModel: PlayerViewModel = hiltViewModel()`** —— 这不是"方便的默认值"，是 bug：

- 在 `composable(route) { }` 内部，`hiltViewModel()` 解析的是**该路由自己的 `NavBackStackEntry`** → 新建**第二个**实例；
- 该实例随路由出栈销毁，其 `onCleared()` 会连带清理 `SearchStateHolder`、`PlaybackStateHolder` 等 `@Singleton` →
  **全局状态被静默拆掉**（不崩溃、无异常日志）。实测：进一次 AI 混音页再返回，搜索从此永远返回空列表，只能重启应用恢复。

三条约束，缺一即回归：

1. `PlayerViewModel` 型参数**一律不写默认值**（现在全仓 `grep 'PlayerViewModel = hiltViewModel()'` 为 0），
   调用点显式传入，让编译器强制接线。
   `PlaylistViewModel` / `SettingsViewModel` / `EqualizerViewModel` 等的默认值**保留** —— 它们没有清理单例状态的行为。
2. **新增屏幕 / 覆盖层要接进传递链**：`AppNavigation` 的 `composable(route)` → `ScreenWrapper` → 目标 composable；
   播放器覆盖层走 `UnifiedPlayerOverlaysLayer.kt` 的 host → `UnifiedPlayerQueueLayer` → `QueueBottomSheet`。
3. **新增 `@Singleton` StateHolder 照抄 `SearchStateHolder` 的所有权校验**：`initialize(owner, scope)` /
   `onCleared(owner)`，**首个 owner 胜出**（"接管"语义无效：第二个 VM 会拿到所有权，销毁时照样清空），
   非 owner 的调用忽略并打 `Timber.w`，调用点传 `this@PlayerViewModel`。回归测试见 `SearchStateHolderTest`。

**探针**：`logcat` / `files/logs/pixelplayeross.log` 里 grep `SearchStateHolder` 的 `Timber.w` 告警 ——
有告警就说明又有第二个 VM 在调 `initialize`/`onCleared`，即传递链又漏了一处。

## 关键架构速查

- **删除歌曲**：所有 UI 的 `onDeleteFromDevice` 最终都走 `PlayerViewModel.deleteSelectedFromDevice`（批量）/ `deleteFromDevice`（单曲）—— 拦截删除只需改这两处。`removeSongFromLibrary` 是"仅移出曲库、不删文件"。
- **五星评分**：入口 `SongInfoBottomSheet`（Info/Edit 两页）→ `EditSongSheet`，写**音频文件内嵌标签**（`SongMetadataEditor`，读键 `RATING` / `POPULARIMETER`），不是数据库字段；云端曲目无法写入。
- **设备能力**：`DeviceCapabilitiesScreen` 展示解码器支持（`supportedCodecs` / `isDecoderAvailable`）。
- **性能诊断**：`data/diagnostics/`（`AdvancedPerformanceDiagnostics`、`MainThreadStallMonitor`）。
- 提示统一走 `PlayerViewModel._toastEvents`；脚本 `run-tests.bat` / `test_summary.py` 尚未移植（china-only 有）。

## 约定

- commit message 用英文；不修改上游 `CHANGELOG.md`（自用分支不提 PR）。
- 新功能字符串同时加中文（`values-zh-rCN/`）与英文（`values/`）两种语言，不手写其他语言。
- 改动后至少跑 `assembleRelease`（默认构建）；UI 类改动装真机验证（**验收范围见「目标设备与屏幕尺寸」**，小屏被裁不算缺陷）。需要逐行调试时才额外构建 `debug`。
