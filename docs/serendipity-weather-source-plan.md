# Serendipity 天气来源：可选城市 / 国内接口 / 离线反查 — 调研与方案

> 状态：**D1–D6 已定，全部已实现**（见 §5、§6、§8）。目标是在不牺牲功能的前提下，让「天气信号」不再强制依赖定位权限、也不再上传设备经纬度。
>
> 最终的架构是一条干净的界线：**「经纬度 → 地点名」完全离线**（内置城市库最近邻，无任何地图 API），**只有「地点名 → 天气」走网络**（Open-Meteo 预报端点，零 key，发的是所选地点的公开坐标而非设备坐标）。

## 1. 背景与现状

Serendipity（不期而遇）用「时间 + 城市 + 天气 + 步数」拼提示词。天气来源为 Open-Meteo（零 key），由 `SerendipityContextCollector.resolveCity()` 决定坐标：

1. **用户填城市**（`ai_serendipity_city`，自由文本）→ Open-Meteo 地理编码 → 经纬度 → 预报；
2. 否则**设备粗略定位** → Android `Geocoder` 反查城市名 → 经纬度 → 预报。

### 问题（本机 2026-09-13 实测）

| 端点 | 结果 | 影响 |
|---|---|---|
| `api.open-meteo.com/v1/forecast`（预报） | **HTTP 200**，北京返回真实 22.5°C | 国内可达、含中国数据 —— 可用 |
| `geocoding-api.open-meteo.com`（地理编码） | **TLS 握手失败**（HTTP 000/exit 35），连 Berlin 都空 | 「填城市名」路径脆弱 |
| Android `Geocoder` 反查 | 中国 ROM 上常无后端（`Geocoder.isPresent()==false`） | 定位路径拿不到城市名 |

即：**主路径（定位→坐标→预报）在国内可用**，但「填城市名」脆弱、且**要定位权限 + 会把设备坐标发给境外服务**。

### 目标

- **G1**：用户可**不授予定位权限**。
- **G2**：**不上传设备经纬度**。
- **G3**：国内可用、默认零 key（OSS 分发不夹带私钥）。

## 2. 调研结论

### 2.1 国内地图/天气厂商公开接口（都可用，但都要 key）

| 厂商 | 端点 | 查询维度 | 要坐标吗 | 免费额度 | 凭证 |
|---|---|---|---|---|---|
| **高德** | `restapi.amap.com/v3/weather/weatherInfo` | `adcode` **或中文城市名** | 否 | 有（个人开发者） | 需注册 web 服务 key |
| **百度** | `api.map.baidu.com/weather/v1/` | `district_id`(adcode)；**经纬度查询属付费高级权限** | 基础版否 | 有 | 需注册 ak（实名可提额） |
| **和风 QWeather** | `*.qweatherapi.com`（新 host） | 城市名 / LocationID / 坐标 | 否（city-based） | 有免费订阅 | 需注册 key |

- 实测连通性：高德返回 `{"status":"0","info":"INVALID_USER_KEY"}`、百度返回 `AK有误` —— 均 **HTTP 200，证明可达且接口形态正确**，仅缺 key。
- 和风旧域名 `geoapi.qweather.com` 已 **404**（迁移至项目专属 `*.qweatherapi.com`）。

**结论**：国内厂商**都能按城市名查、不必传坐标**，**但都需要开发者 key**。对 OSS 自用 fork：
- 硬编码 key 会随 APK 泄露、有配额被刷风险、也可能违反 ToS；
- 若要采用，应设计为**用户自带 key**（设置里填），默认仍走零 key 方案。

### 2.2 零 key 的备选源

| 方案 | 传输 | key | 国内可达 | 风险 |
|---|---|---|---|---|
| **Open-Meteo 预报** | 坐标（可传**所选城市**的公开坐标） | 否 | ✅ | 发送的是城市坐标而非设备坐标 |
| **wttr.in** | **纯城市名** | 否 | ✅ 实测 `Shanghai` 返回真实天气 | 第三方个人服务、无 SLA、数据源 WorldWeatherOnline（非国内） |

### 2.3 离线「经纬度 ↔ 城市」

- 数据源：**`world_cities_5000.json`**（GitHub `joelacus/world-cities`，**CC-BY 4.0**）
  - 全球 69,695 城、7.0 MB；**中国子集 2,894 城 ≈ 212 KB**（未压缩，进 APK 后更小）。字段 `country/name/lat/lng`。
- 备选：GeoNames `CN.zip`（CC-BY 4.0，字段更全：中英名/行政区/时区），本机下载慢（超时），可换镜像。
- 应用：把库作为 `assets/` 打进包，加载后建**网格索引 + haversine 最近邻**，O(1)~O(k) 本地查询，**零网络、坐标不出设备**。

## 3. 方案设计

### 3.1 设置项：把「天气来源」升级为三选一

新增 `ai_serendipity_weather_mode`：

| 模式 | 定位权限 | 上传内容 | 说明 |
|---|---|---|---|
| **自动（设备定位）** | 需要（粗略） | 设备坐标 → Open-Meteo | 现状，覆盖差旅场景 |
| **指定城市**（默认） | **不需要** | 仅所选城市（公开数据） | 从**内置离线城市列表**选择 |
| **关闭** | 不需要 | 无 | 只用时间生成 |

### 3.2 「指定城市」下 城市→天气 的三条路径

| 路径 | 发送 | key | 精度/来源 | 成本 | 评价 |
|---|---|---|---|---|---|
| **(a) 坐标法**：内置库→城市坐标→Open-Meteo | 所选城市坐标（非设备） | 零 | Open-Meteo/CMA 模型 | 最小改动 | ✅ **推荐默认** |
| **(b) 城市名法**：→ wttr.in | **无坐标** | 零 | WorldWeatherOnline | 小 | 严格零坐标，但依赖第三方 |
| **(c) 国内接口**：→ 高德/和风（按城市名） | 无坐标 | **用户自带 key** | 国内权威 | 中 | 最准，需用户配置 |

### 3.3 离线反查（可选增强，服务于「自动」模式）

用内置城市库做**离线经纬度→城市名**，替换中国 ROM 上不可靠的 Android `Geocoder`：坐标不出设备、无 GMS 也能得到城市名。

## 4. 改动清单（预估）

- `data/preferences/AiPreferencesRepository.kt`：新增 `SERENDIPITY_WEATHER_MODE`（enum）+ flow/setter；`SERENDIPITY_CITY` 语义由「自由文本」升级为「城市条目引用」；加入 `allAiPreferenceKeyNames()` 以便备份。
- 新增 `data/ai/serendipity/CityCatalog.kt`：加载 asset、按名检索 / 最近邻。
- 新增 `app/src/main/assets/cities_*.json`：由 `world_cities_5000.json` 脚本裁剪生成（中国 2,894 城 ≈212KB，可选加全球主要城市）。
- `data/ai/serendipity/SerendipityContextCollector.kt`：`resolveCity()` 按 `weatherMode` 分支。
- `presentation/screens/AiSettingsScreen.kt`：城市自由文本框 → 模式下拉 + **可搜索城市选择器**。
- `res/values/strings_ai.xml` + `values-zh-rCN/`：模式标签/说明、选择器文案（中英成对）。
- `THIRD_PARTY_NOTICES.md`：加 GeoNames / world-cities 的 **CC-BY 4.0** 归属声明。
- `AndroidManifest.xml`：权限沿用「声明但运行时按需请求」，无需改；仅「自动」模式触发请求。

## 5. 决策记录

| 项 | 决策 | 状态 |
|---|---|---|
| **D1 天气引擎** | **(a) 坐标法 Open-Meteo**：内置库给出城市坐标 → 预报 | ✅ 已定 |
| **D2 自动（定位）模式** | **保留**（覆盖差旅） | ✅ 已定 |
| **D3 城市库范围** | **中国（全量省市县区）+ 全球主要城市** | ✅ 已定 |
| **D4 离线反查** | **做**：内置库最近邻替代 Android `Geocoder`，坐标不出设备 | ✅ 已定 |
| **D5 区级是否落地** | **(b) 显示 + 提示词都进**：chip 显示「黄浦区 · 上海」，提示词写 `Huangpu, Shanghai` | ✅ 已定 |
| **D6 中国市级是否换 AreaCity 数据** | **做**：省市县区全用 AreaCity，粒度统一、名称/拼音权威 | ✅ 已定 |

### 5.2 D4 的附带选项：不采纳「坐标吸附」

自动模式下把设备坐标吸附到最近的内置城市，可以让自动模式也「不上传设备经纬度」。**不采纳**：那样天气精度从精确点位降到城市级，而自动模式本身就是用户主动选择要「跟着我走」的场景。指定城市模式已经覆盖了「不想给坐标」这个诉求。

## 6. 落地记录（已实现）

### 6.1 与 §4 预估的差异

| 项 | §4 预估 | 实际 | 原因 |
|---|---|---|---|
| 城市数据源 | `world_cities_5000.json` | **GeoNames `cities15000`** | 前者无人口字段、无中文名；后者有 `population`（可筛「全球主要城市」）与 `alternatenames`（中国城市带中文名，实测 1,618/2,121 命中） |
| 城市资源体积 | 中国 2,894 城 ≈212KB | **7,690 城 ≈304KB**（中国 2,121 + 全球 ≥10 万人城市 5,569） | 中国全量城市 + 全球主要城市；比预估包含更多国家（171 个） |
| 城市存储 | 「城市条目引用」+ 坐标 | **只存城市名**，坐标在采集时由内置库反解 | 少两个 preference key、备份里仍是可读的名字、不会留下过期坐标 |
| Android `Geocoder` | 保留为备用 | **完全移除** | 它是唯一会把设备坐标发给第三方（谷歌）的路径，与「坐标不出设备」直接冲突；内置库覆盖全球，无它可用 |
| 最近邻 | 纯 haversine 最近 | **25km 内有区级 → 用区级；否则 30km 内优先更「有名」的城市** | 见 §7.5 / §8.3。25km 这个上限是被香港逼出来的：深圳的区中心离元朗只有 9km |

### 6.2 改动清单（实际）

| 文件 | 改动 |
|---|---|
| `gen_city_catalog.py`（新增，仓库根） | 从 `cities15000.txt` 生成 `assets/cities.json`；中文名用「行政后缀前缀」规则选（`上海` 而非 `沪`/`上海市`），外文城市保留本地名 |
| `app/src/main/assets/cities.json`（新增） | 并行数组格式；按「本国优先 → 行政级别 → 人口」预排序，选择器空查询直接取表头 |
| `data/ai/serendipity/CityCatalog.kt`（新增） | `City` 模型（`label` 给人看 / `promptName` 给英文提示词）、`CityIndex`（纯函数：`search` / `findByName` / `nearest`）、`CityCatalog`（asset 懒加载）、`parseCities`（`internal`，可被单测直接调用） |
| `data/ai/serendipity/SerendipityWeatherSource.kt`（新增） | 三选一枚举，默认 `SPECIFIC_CITY` |
| `data/ai/serendipity/SerendipityContext.kt` | 新增 `cityLabel`（展示用本地化名；`city` 仍是提示词用的拉丁名） |
| `data/ai/serendipity/SerendipityContextCollector.kt` | 按 `weatherMode` 分支；定位路径改用内置库反查城市名；删除 `Geocoder` |
| `data/preferences/AiPreferencesRepository.kt` | 新增 `ai_serendipity_weather_source` + flow/setter，纳入 `allAiPreferenceKeyNames()` |
| `presentation/viewmodel/PlaylistViewModel.kt` | 新增 `serendipityWantsLocation`（决定要不要申请定位权限） |
| `presentation/screens/HomeScreen.kt` | 权限拆成「步数」与「步数+定位」两组，按模式选择；城市 chip 改用 `cityLabel` |
| `presentation/viewmodel/AiSettingsViewModel.kt` | 注入 `CityCatalog`；新增 `cityResults` 搜索流（`Dispatchers.Default`）与 `setWeatherSource` |
| `presentation/screens/AiSettingsScreen.kt` | 天气来源下拉 + 城市选择器（`AlertDialog` + 搜索框 + 列表），替换原自由文本输入 |
| `res/values{,-zh-rCN}/strings_ai.xml` | 新增 12 条中英文案（来源标签/说明、城市选择器） |
| `THIRD_PARTY_NOTICES.md` + `assets/licenses/THIRD_PARTY_NOTICES.md` | GeoNames CC-BY 4.0 归属 |
| `app/src/test/.../CityCatalogTest.kt`（新增） | 13 例：索引逻辑 + **真实 asset** 解析/检索/反查 |

### 6.3 复现城市资源

```bash
curl -L -o cities15000.zip https://download.geonames.org/export/dump/cities15000.zip
unzip cities15000.zip
python gen_city_catalog.py cities15000.txt
```


### 5.1 D4：「离线反查」是什么

「反查」= 反向地理编码 = **经纬度 → 城市名**（正向是 城市名 → 经纬度）。

自动（定位）模式下系统给出的是**经纬度**，而提示词要写城市名（如「傍晚 · 在上海」），因此需要「经纬度 → 城市名」这一步。现状用 Android 系统 `Geocoder` 反查，问题有二：

- 国内大量 ROM（无 GMS）上 `Geocoder` 无后端，**直接不可用/返回空**；
- 它本质是发往谷歌的网络请求 → **坐标出境**。

**离线反查** = 用「城市选择器」已打包的**同一份城市库**，在设备本地做最近邻（haversine）匹配得到城市名：

- ① 国内必可用（不依赖谷歌服务）；
- ② 坐标不出设备（不发给任何地理编码服务）；
- ③ 成本≈0（数据已为选择器打包，只多一个最近邻函数）。

**建议：做。** 注意点：全球部分只有「主要城市」，境外反查可能归到较远的城市——仅影响提示词里城市名那句的精度，不影响天气本身。

> **附带选项**：自动模式下也可把设备坐标**吸附到最近的内置城市**，只把该城市的公开坐标发给 Open-Meteo（而非原始设备坐标），使自动模式同样满足「不上传设备经纬度」。**结论：不采纳**，见 §5.2。

## 7. 区级（district）精度评估

> 2026-09-13 调研，**仅调研，未改代码**。回答「坐标能不能判到『黄浦区』这一级、代价多大」。

### 7.1 结论速览

- **能，而且比现在更准**：区级反查把多数场景的误差从 1–37 km 压到 0.05–4 km（见 §7.4）。
- **代价很小**：区级资源 **110.3 KB**，APK 内 deflate 后约 **+44.5 KB**。
- **但「最近中心点」≠「落在区内」**：大面积／异形区会误判（陆家嘴 → 黄浦区，实际属浦东新区）。要严格正确需多边形包含判断，而多边形数据 **159 MB**，不能进 APK。
- **现状补充**：`cities.json` 的中国部分本就混入了 GeoNames 的**县级**条目（黄浦 / 浦东 / 南头 / 大沙 / 娲城），这才是「判成黄浦」的真正来源——当前数据是**粒度混杂**的，并非刻意做了区级反查。

### 7.2 数据源横评（本机 2026-09-13 实测）

| 源 | 许可 | 区级覆盖 | 中心坐标 | 拼音/英文名 | 实测结论 |
|---|---|---|---|---|---|
| GeoNames `cities15000`（现用） | CC-BY 4.0 | ✗ | ✓ | 部分 | 连「黄浦」「天河区」都没有 |
| GeoNames `CN` 全量（130 MB） | CC-BY 4.0 | 部分（PPLA3 2,109 条） | ✓ | ✗ | 「南山区」命中的是黑龙江的（47.31°N） |
| 阿里云 DataV `areas_v3` | 无明确开源许可 | ✓ 2,840 | ✓ | ✗ | **CDN 限流 403**（`denied by rate limit`），5 次探测未恢复 |
| Wikidata `P442` | CC0 | ✓ | ✓ | ✓ | **服务故障中 + 429 限流**，查询返回空 |
| modood 行政区划码 | 民政部口径 | ✓ 3,056 | ✗ | ✗ | 只有名称／代码，无坐标 |
| **AreaCity（xiangyuecn）** | **MIT** | **✓ 2,851** | **✓ `geo` 列即中心点** | **✓ 拼音覆盖 100%** | **采用**：Release 下载不需 key |

采用的组合：

- `ok_data_level3.csv`（525 KB 7z → 235 KB CSV）→ 省市区**名称 + 拼音**
- `ok_geo.csv`（16.5 MB 7z → 159 MB CSV）→ `geo` 列给**中心点**，`ext_path` 给**父市**
- 两者用 `id` 关联；台湾/港澳在该数据集里 `geo` 为空，已剔除。

### 7.3 体量实测

| 项 | 原始 | APK 内（deflate） |
|---|---|---|
| 现有 `cities.json`（7,690 条） | 304.2 KB | 117.3 KB |
| ↳ 其中中国部分（2,121 条） | 86.9 KB | — |
| 新增区级 `[区名, 拼音, lon, lat]`（3 位小数，2,851 条） | **110.3 KB** | **43.8 KB** |
| 用 AreaCity 的 372 个市替换现有中国市级 | 14.9 KB | — |
| **方案一：只新增区级** | 304.2 → **414.6 KB** | 117.3 → **161.8 KB（+44.5 KB）** |
| **方案二：新增区级 + 换掉中国市级** | 304.2 → **342.6 KB** | 约 +30 KB |

坐标保留 3 位小数（≈110 m），足以区分市中心与邻区。

### 7.4 精度实测（区中心最近邻）

| 位置 | 区级结果 | 距区中心 | 现有资产结果 | 距 |
|---|---|---|---|---|
| 上海人民广场 | 黄浦区 | 0.85 km | 黄浦 | 1.00 km |
| 上海五角场 | 虹口区 | 3.73 km | 杨浦 | 3.98 km |
| 上海陆家嘴 | **黄浦区（误）** | 1.71 km | 浦东 | 0.11 km |
| 北京天安门 | 东城区 | 2.71 km | 北京 | 0.14 km |
| 深圳南山科技园 | 南山区 | 0.25 km | 南头 | 1.56 km |
| 广州天河体育中心 | 天河区 | 0.05 km | 大沙 | 8.31 km |
| 杭州西湖 | 西湖区 | 0.05 km | 杭州 | 4.88 km |
| 河南扶沟县郊 | 扶沟县 | 4.46 km | 娲城 | 36.75 km |

**误判机理**：用的是「最近中心点」，不是「点是否落在多边形内」。浦东新区中心在花木，距陆家嘴 5 km+，于是最近的那个落到了黄浦区。大区（浦东新区、滨海新区、重庆各城区）都有此风险；小区（西湖区、天河区）几乎无误。

### 7.5 决策：D5=(b)，D6=做

- **D5 = (b) 显示 + 提示词都进**：chip 显示「黄浦区 · 上海」，提示词写 `Huangpu, Shanghai`。
- **D6 = 做**：中国省市县区全部改用 AreaCity，GeoNames 只负责境外（见 §8）。
- **兜底**：区查不到或太远 → 退回市级逻辑，保证不退化。

### 7.6 复现

```bash
# 1) 名称 + 拼音（525 KB）
curl -L -o okd.7z https://github.com/xiangyuecn/AreaCity-JsSpider-StatsGov/releases/download/2025.251231.260403/ok_data_level3-4.csv.7z
# 2) 中心点 + 父市（16.5 MB 7z，解压后 159 MB）
curl -L -o okg.7z https://github.com/xiangyuecn/AreaCity-JsSpider-StatsGov/releases/download/2025.251231.260403/ok_geo.csv.7z
# py7zr 解压；流式读 ok_geo.csv 的 id/deep/name/ext_path/geo（务必跳过 polygon 列）
```

许可：仓库为 MIT；数据取自民政部行政区划与高德。若采用，需在 `THIRD_PARTY_NOTICES.md` 补归属。

## 8. 落地记录（D5=b + D6，已实现）

### 8.1 数据构成

| 项 | 数量 | 来源 |
|---|---|---|
| 中国 省/直辖市/特别行政区 | 34 | AreaCity `ok_data_level3.csv` + `ok_geo.csv`（MIT） |
| 中国 市级（含自治州/盟） | 367 | 同上 |
| 中国 区/县/县级市/旗 | **2,813** | 同上（`geo` 列 = 中心点，`ext_path` = 父级，按 `id` 关联拼音） |
| 港澳台 地点 | 148 | GeoNames（AreaCity 对这些只给省一级、且 `geo` 为 `EMPTY`；中文名取别名列表中最短者） |
| 全球 ≥10 万人口城市 | 5,514 | GeoNames `cities15000`（CC-BY 4.0），已剔除 HK/MO/TW |
| **合计** | **8,876** | `assets/cities.json` **423.1 KB**（deflate 后 **136.0 KB**，上一版 304 KB / 117 KB） |

**港澳台的处理**：GeoNames 给它们各自的 `countryCode`，直接用会让选择器出现「Taipei, TW」这类标签；而 AreaCity 把它们列在省一级但没有坐标。做法是：剔除 GeoNames 里 `HK`/`MO`/`TW` 的行，再把它们的中国地点以 `country = "CN"` 收进来、显示名取中文别名（繁体保留原样，港澳台就是这么写的）。这样反查不会再出现「高雄 → 金门县 · 泉州」这种结果。

### 8.2 生成脚本的三条规则（都来自实测踩坑）

1. **重复行政层级要去掉**：`ext_path` 末两段相同时说明该行在重复上一级（直辖市的「市辖区」行 `北京市 北京市`、不设区的地级市 `广东省 东莞市 东莞市`、港澳的二级条目）。全部丢弃，否则选择器里每个直辖市都会出现两次。
2. **拼音的大小写**：按整个名字只首字母大写（`shang hai` → `Shanghai`），不是每个音节都大写（会得到 `ShangHai`）。
3. **拼音里可能带行政区划后缀**：AreaCity 只在能剥离时剥离，剥不掉的行拼音会连后缀一起给（`浦东新区` = `pu dong xin qu` → 会拼出 `Pudongxinqu`）。判据是 `name == ext_name` 时才回头裁掉后缀音节——用「末音节像不像后缀」判断会误伤（`涿州市` = `zhuo zhou`，裁完变成 `Zhuo`）。

### 8.3 反查规则：区级优先，但只在可信范围内

```
最近区级 ≤ 25km  → 用区级（"黄浦区 · 上海" / "Huangpu, Shanghai"）
否则 30km 内的最高等级城市（同级比距离）
都没有 → 最近的（区级兜底，境外退回城市）
```

| 位置 | 结果 | 距中心 |
|---|---|---|
| 上海人民广场 | 黄浦区 · 上海 | 0.83 km |
| 陆家嘴 | 黄浦区 · 上海 ⚠️ | 1.73 km |
| 北京天安门 | 东城区 · 北京 | 2.66 km |
| 广州天河体育中心 | 天河区 · 广州 | 0.00 km |
| 深圳南山科技园 | 南山区 · 深圳 | 0.22 km |
| 河南扶沟县郊 | 扶沟县 · 周口 | 0.46 km |
| 新疆戈壁 | 轮台县 · 巴音郭楞 | 37.30 km |
| **香港中环** | **灣仔**（25km 规则前是「福田区 · 深圳」） | 1.54 km |
| **香港元朗** | **元朗** | 0.35 km |
| 澳门半岛 | 澳門 | 0.45 km |
| 高雄 | 高雄 | 1.73 km |
| Paris / Tokyo / 纽约 | Paris / Tokyo / New York City | < 7 km |

**25km 上限是被香港逼出来的**：深圳的区中心离元朗只有 9 km、离中环 28.7 km。上限收紧到 25km 后，港澳台不再被深圳的区抢走，而新疆戈壁这类「区中心 37km 外」的地点因为兜底逻辑仍然返回区级。

### 8.4 父级只挂在区级上

`parent`/`parentName` 只对区县（rank 1）赋值。给市级也挂上父省的话，每一条提示词都会变成 `Guangzhou, Guangdong`——市级名字本身没有歧义，不需要限定。所以：

- 区级：label `天河区 · 广州`，prompt `Tianhe, Guangzhou`
- 市级：label `广州市`，prompt `Guangzhou`
- 省/首都：label `广东省`，prompt `Guangdong`
- 境外：label `Paris, FR`，prompt `Paris`

设置页选择器存的是 **label** 而不是 name：全国有 5 个「东区」，只存 name 会在下次解析时落到第一个（可能是攀枝花那个）而查错天气。

### 8.5 已知限制

1. **「最近中心点」≠「落在区内」**：陆家嘴被判成黄浦区（浦东新区中心在花木，距陆家嘴 5km+）。大区/异形区都有此风险。严格正确需要多边形包含判断，而多边形 159 MB 不可能进 APK。兜底：设置页可手选任意城市或区县。
2. **境外只到市级**：GeoNames 没有境外区级数据，也不该有——「巴黎的哪个区」对一句提示词没有价值。
3. **港澳台用的是 GeoNames 的次区级条目**（元朗、灣仔、天水囲…），别名挑选是「最短中文别名」，个别条目字面有噪声（如 `天水囲`），不影响反查位置正确性。

### 8.6 复现

```bash
# 输入 1：AreaCity（MIT）
curl -L -o okd.7z https://github.com/xiangyuecn/AreaCity-JsSpider-StatsGov/releases/latest/download/ok_data_level3-4.csv.7z
curl -L -o okg.7z https://github.com/xiangyuecn/AreaCity-JsSpider-StatsGov/releases/latest/download/ok_geo.csv.7z   # 解压后 159 MB
python -c "import py7zr; py7zr.SevenZipFile('okd.7z').extractall('okd'); py7zr.SevenZipFile('okg.7z').extractall('okg')"
# 输入 2：GeoNames（CC-BY 4.0）
curl -L -o cities15000.zip https://download.geonames.org/export/dump/cities15000.zip && unzip cities15000.zip

python gen_city_catalog.py cities15000.txt okg/ok_geo.csv okd/ok_data_level3.csv
```

许可归属已写入仓库根与 `assets/licenses/` 两份 `THIRD_PARTY_NOTICES.md`。

### 8.7 验证状态

- 单测：`CityCatalogTest` **22/22**、`SerendipityPromptComposerTest` **10/10** 通过。
- 构建：`:app:assembleDebug` **BUILD SUCCESSFUL**。
- 尚未提交、未装机。
