# SkyBox Android ARM64

SkyBox 是基于 reF1nd/sing-box 的独立 Android 客户端。主界面为全屏天气页：城市与时间提示、大号温度、天气状况与当日高低温、空气质量与天气预报胶囊、分钟级降水柱状图（含大/中/小刻度）、逐日预报温度条；左下角圆形按钮展开控制面板（连接开关、延迟、流量、配置、日志、设置），右下角圆形按钮添加配置。内核来自 `reF1nd-testing-next`，基线提交 `3cf2b01ce`（`v1.15.0-alpha.13-reF1nd` 之上叠加 DNS 缓存修复 `1d089fcba`）；应用包名 `io.github.yuedaochangmendian.skybox`，Android 7.0+（API 24），ABI 仅 `arm64-v8a`。

## 使用

在「配置」页从系统文件选择器导入完整 sing-box JSON，或者新建/编辑。配置在保存前由内核校验，存于应用私有目录；请使用与内核版本匹配、包含 `tun` 入站且 `auto_route: true` 的 Android VPN 配置。首次连接需系统 VPN 授权。无配置时不会建立虚假连接。

概览展示内核真实上下行速率与累计流量，最近一分钟按 10 秒记录下载增量。点击延迟区域发起经当前应用路由的 HTTPS 204 请求测试；这是 HTTP 请求耗时，并非代理节点 TCP 延迟。未测试时显示占位符。连接、DNS、出站和路由使用导入 JSON 的实际设置；不内置代理服务器、订阅账号或假节点。

配置可选用、编辑与删除；切换/修改配置后重新连接以生效。配置若引用本地规则集或证书，文件必须能由应用访问，本版不提供额外文件导入映射。Root 自动重定向、邻居监视和平台 Shell 功能不提供。

## 工具链与构建

- Go 1.25.5、sagernet gomobile/gobind v0.1.12。
- JDK 21（Java/Kotlin 字节码目标 17）、Kotlin 2.2.21、Gradle 8.12、AGP 8.9.1。
- 应用源码为纯 Kotlin（`app/src/main/java/**/*.kt`，Kotlin 插件默认把该目录视为源码根）。Kotlin 插件通过根 `build.gradle` 的 `buildscript` classpath 引入，避免离线构建解析插件 marker；依赖完整缓存后可直接 `--offline` 构建。
- Android SDK Platform 24（gomobile）和 35（应用）、Build Tools 35.0.0、NDK 28.2.13676358。

从仓库根目录，在 Bash 中设置本机路径后执行：

```bash
export ANDROID_HOME=/path/to/Android/Sdk
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/28.2.13676358"
export JAVA_HOME=/path/to/jdk-21
bash android-ui/tools/build-core.sh
cd android-ui
./gradlew --no-daemon assembleDebug lintDebug
```

Windows 可在 Git Bash 中构建 AAR，然后用 `gradlew.bat` 或已安装的 Gradle，并设置 `ANDROID_HOME`。生成的 AAR 位于 `app/libs/libbox.aar`，已忽略不入 Git；APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。首次构建需要联网，依赖完整缓存后可加 `--offline`。

`build-core.sh` 直接调用 gomobile 构建 ARM64 主变体，支持 JDK 21，无需改写上游的 `build_libbox` JDK17 检查或 legacy 构建逻辑。Go 包名保持上游要求的 `github.com/sagernet/sing-box`。

GitHub Actions 工作流 **SkyBox Android ARM64**（`.github/workflows/skybox-android.yml`）支持手动从当前分支构建 AAR、APK、Lint 并上传工作流产物。

当前 debug/release 均使用开发测试签名，`release` 仅关闭调试标志，不能等同生产签名。不同 CI 机器的默认 debug key 不保证相同；正式分发应先配置并长期保存专属签名密钥，密钥与密码不得提交源码。

## 验证范围与归属

APK 构建、Android Lint、签名与 ZIP/ABI 检查结果以本次交付说明为准。未连接 ARM64 Android 真机时，不能声称已经验证系统 VPN 授权、实际代理联网或像素级截图还原。

本项目与内核遵循根目录 `../LICENSE` 的 GPL-3.0-or-later 及相关命名/归属说明。sing-box Copyright (C) 2022 nekohasekai；SkyBox 为独立衍生客户端，不代表 SagerNet 官方或上游背书。分发 APK 应同时提供对应分支源码、许可证及依赖许可。

## v1.4.0 界面改版说明

- 主界面改为全屏天气页，按参考稿还原：`SkyBackdropView`（渐变 + 雨/雪/星/雾粒子，约 30fps，系统栏透明）、大号温度（`SuperscriptSpan` 缩放的度数符号）、空气质量与天气预报胶囊、`PrecipitationChartView`（15 分钟降水柱状图 + 虚线刻度 + 大/中/小轴）、`TemperatureRangeView`（逐日最低/最高温度条）。
- 天气数据扩展：`WeatherState` 新增 `daily`（4 天）、`precipitation15`（8 个 15 分钟值）、`aqi/aqiLabel`；`WeatherService` 增加 `daily`、`minutely_15` 参数与可选的 `air-quality-api.open-meteo.com`（US AQI，失败不影响天气）；快照把这些字段序列化为一个 JSON 字符串持久化。
- 降水文案由真实 nowcast 推导（何时停、是否再下、何时开始、无降水），不编造天气。
- 交互：左下圆按钮打开原有四页控制面板（`panel` 覆盖层，返回键关闭），右下圆按钮弹出「从文件导入 JSON / 新建空白配置」。原「概览」页保留连接开关、延迟、流量与快捷入口，但不再重复天气读数。
- 配色按主题切换：夜间深蓝渐变 + 白色文字，日间浅蓝渐变 + 深色文字，卡片、胶囊、刻度线、图表网格与浮动按钮均取自 `WeatherTheme`；同时删除未使用的 `WeatherView`。
- 位置提示写为「手动选择城市，获取当地天气」，因为本应用不申请定位权限，不复制参考稿中「开启位置服务」的措辞。

## v1.3.0 内核基线更新说明

- 内核基线从 `reF1nd-testing` 的 `17397d022`（`v1.15.0-alpha.13-reF1nd`）前移到 `reF1nd-testing-next` 的 `3cf2b01ce`，内容差异为 4 个文件、115 行：`protocol/dns/outbound.go` 与 `route/route.go` 的 DNS 缓存修复（上游 `1d089fcba` "DNS: Fix some cache issues"）及其新增测试。
- `experimental/libbox` 导出接口与 1.2.0 完全一致（该目录零 diff），应用侧 Kotlin 代码无需改动，仅重新生成 `libbox.aar`。
- `reF1nd-testing-next` 是 reF1nd 的重放分支：功能补丁与 `reF1nd-testing` 同源，但提交 SHA 不同，因此本次采用 `git rebase --onto` 把 SkyBox 的 4 个应用提交（`fc01fdebd`、`b0302d273`、`7f4c9b050`、`b58763cfb`）重放到新基线上，`android-ui/` 目录内容逐字节不变。
- 该分支尚未出现在 `reF1nd/sing-box-releases` 的发布列表里（最新发布仍是 `v1.15.0-alpha.13-reF1nd`），属测试通道最新提交；稳定通道 `v1.14.4-reF1nd`（`732618a`）是 1.14 分支，功能更旧，未采用。

## v1.2.0 Kotlin 迁移说明

- 应用代码（界面、VPN 服务、配置存储、天气数据/主题/图标）已全部由 Java 迁移为 Kotlin，行为保持不变；`libbox.aar` 仍提供 Java 接口，`VpnController` 继续实现 `PlatformInterface` 等接口。
- `VpnController` 的静态状态（`running/state/error/downlink/...`）与 action 常量保留为 Java 可见的静态字段（`@JvmField`），`ProfileStore`、`CoreRuntime`、`LatencyProbe` 为 Kotlin `object`，Manifest 与通知点击逻辑无需改动。
- 顺带修复审查发现的三项问题：天气快照与 15 分钟限流改为持久化（Activity 重建后不再立即重发请求并保留上次读数）；切换页签后天气图标不再回落为未知图标；有缓存数据时刷新失败会显示具体错误而不是仅显示“已过期”。

## v1.1.0 天气数据与状态说明

- 天气数据通过 HTTPS 请求 Open-Meteo（`https://api.open-meteo.com/`），无需配置 API key；获取新数据需要网络连接。
- 城市由用户手动选择，按所选城市获取天气，不申请定位权限，也不依赖 GPS 或后台定位。
- 天气数据按 15 分钟间隔刷新；这不是系统后台准点唤醒的保证，应用挂起或网络不可用时不能承诺实时更新。
- 天气主题使用蓝天、太阳、云朵与线性导航图标。天气读数和晴雨画面只表达所选城市的天气，与真实 VPN 状态分离；获取到天气不代表 VPN 已连接，天气请求失败也不代表 VPN 已断开。
- VPN 连接仍以 Android 系统授权与 sing-box 内核实际状态为准，活动页中的速率、累计流量和日志仍来自真实内核；天气刷新不会替用户授权 VPN、选择代理或伪造连接/流量。

