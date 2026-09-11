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
- compileSdk = targetSdk = 37，minSdk = 30；版本在 `gradle.properties` 的 `APP_VERSION_NAME` / `APP_VERSION_CODE`（当前 0.3.0 / 3）。
- Media3 1.10.1、Room 2.8.4（**schema 仅到 v6**，任何实体变更都要新增 migration）、Hilt、OkHttp/Retrofit、TagLib + JAudioTagger 元数据。
- 依赖仓库为官方 `google()` / `mavenCentral()`（首次构建较慢，无国内镜像）。

## 常用命令（PowerShell，仓库根目录）

```powershell
.\gradlew.bat :app:assembleDebug            # 增量构建
.\gradlew.bat :app:testDebugUnitTest        # 单元测试（JUnit 5）
.\gradlew.bat :app:lintDebug                # Lint（release 构建不做）
```

- **debug / release 可共存**：`debug` 带 `applicationIdSuffix = ".debug"` + `versionNameSuffix = "-debug"`，应用名 `PixelPlayerOSS [D]`（`app/src/debug/res/values/strings.xml`），
  图标走 `app/src/debug/res` 的红底覆盖（只覆盖 `ic_launcher_background` 与 legacy 方/圆图标，foreground 与 `mipmap-anydpi-v26` XML 沿用 main）。
  改了 main 图标后重跑根目录 `gen_debug_icons.py`（Pillow）重新生成 debug 图标。
- **debug 也过 R8**：`isMinifyEnabled` + `isShrinkResources` 与 release 一致（产物更接近 release），
  仍可调试（proguard 保留 `SourceFile,LineNumberTable`，mapping 在 `app/build/outputs/mapping/debug/`）。
- **ABI 只构建 `arm64-v8a`**（`pixelplayer.enableAbiSplits` 默认 true）；关掉该 property 时文件名中的 abi 段为 `universal`。
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

发版链路：`main` → 版本号提交（**message 里带 `[skip ci]`**，抑制
`.github/workflows/alpha-release.yml` 自动造 alpha 预发布）→ `tag v<版本名>` → `assembleRelease`
→ `gh release create`。版本号带 `-pisces.N` 后缀，版本码沿用 `主版本 * 100000 + 序号`。

> `main` 现在是日常开发分支，而 `alpha-release.yml` 在 push `main` 时触发、`paths-ignore`
> 只排除 `**.md` / `docs/**` / `assets/**` / `fastlane/**` / `metadata/**` / `LICENSE` /
> `.github/**` —— **代码提交会真的触发它**。该 workflow 跑在 `blacksmith-4vcpu-ubuntu-2404`
> 上，本 fork 没有对应 runner，所以代码提交若不带 `[skip ci]` 就会留下一个挂起/失败的 run。

## 新增一个设置开关（必读，缺一不可）

OSS 有**设置搜索**，开关不注册就搜不到：

1. `data/preferences/UserPreferencesRepository.kt`：`PreferencesKeys` 加 key → `xxxFlow` → `setXxx()`。
2. `presentation/viewmodel/SettingsViewModel.kt`：`SettingsUiState` 字段 → `SettingsUiUpdate.Group2` 字段 → `init` 里 combine 的 flow 列表**末尾**追加 → `values[N]`（索引按位置对应，只能追加不能插队）→ `state.copy()` → `setXxx()`。
3. `presentation/screens/SettingsCategoryScreen.kt`：对应 `SettingsCategory` 分支里加 `SettingsSubsection` + `SwitchSettingItem`，带 `Modifier.settingHighlight("item_xxx", highlightKey)`。
4. `presentation/settings/search/SettingsRegistry.kt`：注册 `SettingSpec`（`type = SettingType.SWITCH`，带 `getValue` / `onToggle` 与关键词），`itemKey` 与第 3 步的 highlight key 一致。
5. `res/values/strings_settings.xml`（英文）加 title/subtitle；其他语言留给翻译流程，不要手写。

## 关键架构速查

- **删除歌曲**：所有 UI 的 `onDeleteFromDevice` 最终都走 `PlayerViewModel.deleteSelectedFromDevice`（批量）/ `deleteFromDevice`（单曲）—— 拦截删除只需改这两处。`removeSongFromLibrary` 是"仅移出曲库、不删文件"。
- **五星评分**：入口 `SongInfoBottomSheet`（Info/Edit 两页）→ `EditSongSheet`，写**音频文件内嵌标签**（`SongMetadataEditor`，读键 `RATING` / `POPULARIMETER`），不是数据库字段；云端曲目无法写入。
- **设备能力**：`DeviceCapabilitiesScreen` 展示解码器支持（`supportedCodecs` / `isDecoderAvailable`）。
- **性能诊断**：`data/diagnostics/`（`AdvancedPerformanceDiagnostics`、`MainThreadStallMonitor`）。
- 提示统一走 `PlayerViewModel._toastEvents`；脚本 `run-tests.bat` / `test_summary.py` 尚未移植（china-only 有）。

## 约定

- commit message 用英文；不修改上游 `CHANGELOG.md`（自用分支不提 PR）。
- 新字符串只加英文 `values/`，不写死中文。
- 改动后至少跑 `assembleDebug`，UI 类改动装真机验证。
