# SkyBox Android ARM64

SkyBox 是基于 reF1nd/sing-box 的独立 Android 客户端，采用蓝色渐变天气仪表、顶部配置切换/连接开关、大号 HTTP 延迟读数、最近一分钟下载记录、用量卡片和悬浮四页底栏。内核来自 `reF1nd-testing`，基线提交 `17397d022`；应用包名 `io.github.yuedaochangmendian.skybox`，Android 7.0+（API 24），ABI 仅 `arm64-v8a`。

## 使用

在「配置」页从系统文件选择器导入完整 sing-box JSON，或者新建/编辑。配置在保存前由内核校验，存于应用私有目录；请使用与内核版本匹配、包含 `tun` 入站且 `auto_route: true` 的 Android VPN 配置。首次连接需系统 VPN 授权。无配置时不会建立虚假连接。

概览展示内核真实上下行速率与累计流量，最近一分钟按 10 秒记录下载增量。点击延迟区域发起经当前应用路由的 HTTPS 204 请求测试；这是 HTTP 请求耗时，并非代理节点 TCP 延迟。未测试时显示占位符。连接、DNS、出站和路由使用导入 JSON 的实际设置；不内置代理服务器、订阅账号或假节点。

配置可选用、编辑与删除；切换/修改配置后重新连接以生效。配置若引用本地规则集或证书，文件必须能由应用访问，本版不提供额外文件导入映射。Root 自动重定向、邻居监视和平台 Shell 功能不提供。

## 工具链与构建

- Go 1.25.5、sagernet gomobile/gobind v0.1.12。
- JDK 21（Java 源码目标 17）、Gradle 8.12、AGP 8.9.1。
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
