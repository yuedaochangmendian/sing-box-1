# SkyBox 3.0 — Flutter 版

SkyBox 的 Flutter 实现。UI 与业务逻辑全部用 Dart 编写，玻璃材质来自
[`liquid_glass_widgets`](https://github.com/sdegenaar/liquid_glass_widgets)（真折射 shader：
SDF 斜面法线 → Snell 折射 → 边缘色散 → 亮度门控高光）。

旧的 View 版本保留在 `../android-ui/`，作为对照与回退。

## 架构边界

`libbox.aar` 是 sing-box Go 内核的 gomobile 绑定，Dart 无法直接调用，所以内核侧必须留在
原生宿主。桥接只有一个 `MethodChannel("skybox/core")`。

| 留在 Kotlin 宿主 | 迁到 Dart |
| --- | --- |
| `VpnController`（`VpnService` + `PlatformInterface`） | 全部 UI（首页 / 配置 / 活动 / 设置） |
| `CoreRuntime`、`libbox.aar` | 天气、地理定位、延迟探测（都走隧道） |
| `ProfileStore`（文件层 + 内核校验） | 主题调色、图表自绘、静态策略组解析 |

状态只读 8 个字段，命令 12 个。启动流程（校验 → `VpnService.prepare` →
`startActivityForResult` → `startForegroundService`）在宿主内闭环，Dart 只等结果。

## 构建

内核归档不进仓库（59 MB）。构建前先拷进来：

```bash
cp ../android-ui/app/libs/libbox.aar android/app/libs/
```

然后：

```bash
flutter pub get
flutter build apk --release --target-platform=android-arm64   # arm64 单 ABI
flutter build apk --release                                    # universal
```

### 构建环境要求

- Flutter 3.47.8 / Dart 3.13.7（`liquid_glass_widgets` 要求 Flutter ≥ 3.41）
- JDK 21
- `compileSdk 36`（`shared_preferences_android` 的 AAR metadata 硬性要求，35 会被 AGP 拦下）

### 已知环境坑

1. **Gradle 分发**：wrapper 从 `services.gradle.org` 下载极慢。可先手动从镜像取
   `https://mirrors.cloud.tencent.com/gradle/gradle-9.3.1-all.zip` 放进
   `~/.gradle/wrapper/dists/gradle-9.3.1-all/<hash>/` 并解压完整，再 `touch gradle-9.3.1-all.zip.ok`。
2. **跨盘符**：pub 缓存在 `C:`、工程在 `E:` 时，Kotlin 增量存储的 `Path.relativeTo` 会报
   "different roots"。已在 `android/gradle.properties` 关闭 `kotlin.incremental`。

### 签名

当前 release 沿用默认 debug key（与 2.x 各版本一致，保证覆盖安装可用）。
分发前换成私有签名：把 `key.properties` 与密钥库放进 `android/`，并在
`android/app/build.gradle.kts` 里建 `signingConfigs.release`（两者都已被 gitignore）。

## 目录

```
lib/
  bridge/skybox_bridge.dart     MethodChannel 封装
  state/app_state.dart          1s ticker / 30s 延迟 / 5min 节点地区
  services/                     weather_service · node_location · latency_probe
  theme/sky_theme.dart          WMO → 调色 + 中文名
  models/weather.dart
  ui/
    home_page.dart panel_page.dart
    widgets/  glass.dart speed_chart.dart weather_icon.dart temperature_bar.dart
    dialogs/  city_picker.dart weather_detail.dart profile_editor.dart
android/app/src/main/kotlin/.../MainActivity.kt   原生宿主 + 通道实现
```

## 未验证

尚未在真机跑过：通道往返、玻璃观感、天气刷新都需要实机确认。
