# Runami GPS

<p align="center">
  <a href="https://github.com/Akiyama-25/Runami-GPS/stargazers">
    <img src="https://img.shields.io/github/stars/Akiyama-25/Runami-GPS?style=flat-square" alt="Stars" />
  </a>
  <a href="https://github.com/Akiyama-25/Runami-GPS/releases">
    <img src="https://img.shields.io/github/v/release/Akiyama-25/Runami-GPS?include_prereleases&style=flat-square&label=Release" alt="Latest Release" />
  </a>
  <a href="https://github.com/Akiyama-25/Runami-GPS/actions/workflows/build.yml">
    <img src="https://img.shields.io/github/actions/workflow/status/Akiyama-25/Runami-GPS/build.yml?style=flat-square&label=Build" alt="Build Status" />
  </a>
  <a href="https://github.com/Akiyama-25/Runami-GPS/blob/main/LICENSE">
    <img src="https://img.shields.io/github/license/Akiyama-25/Runami-GPS?style=flat-square" alt="License" />
  </a>
  <img src="https://img.shields.io/badge/Android-8.1%2B%20(API%2027--37)-3DDC84?style=flat-square&logo=android&logoColor=white" alt="Android 8.1+" />
  <img src="https://img.shields.io/badge/Xposed-API%2093--102%2B-EC407A?style=flat-square" alt="Xposed API" />
</p>

**Runami GPS**（包名：`Akari.Runami.GPS`）是一款基于现代 Xposed 框架（兼容 **LSPosed / Vector Manager** 等，支持 API 93–102+）开发的 **免勾选系统框架、免重启** Android 定位伪装模块。

通过在目标应用进程内构建 **底层 Binder IPC / AIDL 存根拦截 + SDK / GMS Fused 双层钩子架构**，Runami GPS 无需注入 `system_server`（系统框架）即可完成高精度虚拟定位、反模拟位置检测与 GNSS 卫星信号伪装，同时确保未勾选应用 100% 物理隔离且保留原生的电子罗盘方向与传感器数据。

---

## 核心特性

### 1. 免勾选系统框架 · 彻底免重启
- **双层进程内拦截架构**：
  - **Layer 1（底层 Binder IPC & AIDL 存根层）**：直接在目标应用进程内拦截 `ILocationManager$Stub$Proxy`、`LocationListenerTransport`、`GetCurrentLocationTransport`、`GnssStatusTransport` 等跨进程通信存根。
  - **Layer 2（SDK & GMS Fused 层）**：覆盖 `LocationManager`、`Location`、`LocationResult`、`GnssStatus` 以及 Google Play 服务 `FusedLocationProviderClient` / `LocationCallback`。
- **零全局污染**：移除了传统的 `android`（系统框架）默认作用域。仅需在 LSPosed / Vector 管理器中勾选需要伪装的目标应用，强行停止目标应用后重新打开即可立即生效，无需重启手机。

### 2. 原生传感器与电子罗盘方向完整保留
- **不破坏指南针航向角**：基于系统真实 `Location` 进行克隆与原位坐标改写，完整保留原生 `hasBearing()`、`bearing`、`bearingAccuracyDegrees`、`speed`、`altitude` 与卫星 `extras` 数据。
- **智能动态航向计算**：静止选点时不强制注入虚假的 `0.0°` 正北航向角，确保各类地图与导航应用（Google Maps、高德地图、百度地图等）的电子罗盘旋转矢量传感器（`TYPE_ROTATION_VECTOR`）正常工作；当拖动选点或使用摇杆移动时，自动根据地理位移矢量计算真实运动方向角。

### 3. 全链路详细诊断日志系统
- **自身日志 + 定位请求记录双轨采集**：
  - 记录模块自身运行状态与所有目标应用的定位请求（区分 `LOC:SPOOFED` 已伪装拦截与 `LOC:PASSTHROUGH` 真实位置透传）。
  - 支持从 `WorkSource` 中自动解析经由 `com.google.android.gms` 发起定位的真实客户端包名（显示为 `com.google.android.gms -> [客户端包名]`）。
- **详细诊断字段**：每条日志均包含 `pid`、`tid`、线程名、拦截层级（`layer`）、完整方法签名（`method`）、请求参数（`args`）、原始系统定位详情（`origin`）、改写后返回详情（`result`）及调用栈帧（`caller`）。
- **日志层级过滤（默认 `ERROR`）**：
  - 支持在 `ERROR`（默认）、`WARN`、`INFO`、`DEBUG` 四个层级间随时切换。
  - **定位请求记录不受日志层级影响**：无论当前选择何种日志层级，所有定位请求记录均会完整捕获、展示并导出。
- **跨会话自动清理**：当模块发生版本更新、覆盖安装、系统重启、软重启（Zygote 重启）或模块重新加载时，自动清空上一周期的历史旧日志。
- **标准 `.log` 文件导出**：支持一键保存或分享包含设备环境头信息与时间正序流水记录的 `.log` 日志文件。

### 4. 实用工具与现代交互体验
- **悬浮窗虚拟摇杆（Joystick）**：支持通过桌面悬浮摇杆实时微调与连续移动伪装坐标。
- **随机坐标微偏（Random Jitter）**：在设定坐标周围模拟自然的细微 GPS 传感器漂移。
- **反模拟位置检测 & GNSS 卫星增强**：自动抹除 `isFromMockProvider` / `isMock` 标记，并伪装合理的 GNSS 卫星数量与信噪比（`Cn0DbHz`）。
- **双通道应用内更新**：启动应用时自动检查一次更新，同时支持在设置中手动点击「检查更新」，可自由切换 **Release（正式版）** 与 **Action（CI 自动构建版）** 更新通道。
- **多语言与深色模式**：全面适配 **简体中文、繁体中文、English、日本語**（支持跟随系统或应用内独立切换）及 Material 3 动态配色深色模式。

---

## 环境要求与兼容性

| 项目 | 要求 |
| :--- | :--- |
| **Android 版本** | Android 8.1 ~ Android 16+（API 27 – 37） |
| **Xposed 框架** | LSPosed / Vector Manager 等支持 `XSharedPreferences` 的现代框架（兼容 API 93 – 102+） |
| **系统框架作用域** | **无需勾选** `System Framework (android)`，**无需重启系统** |
| **Google Play 服务** | **严禁勾选** `com.google.android.gms`（模块已在目标应用内直接劫持 GMS 客户端 SDK，勾选服务本身会导致系统全局定位后台被污染） |

---

## 使用说明

1. **安装并启用模块**
   - 安装 **Runami GPS** APK。
   - 打开 **LSPosed / Vector** 管理器，启用 **Runami GPS** 模块。
   - 在模块作用域中**仅勾选需要伪装定位的目标应用**（如浏览器、外服游戏、社交软件等）。
   - **注意**：**请勿勾选「Google Play 服务 (`com.google.android.gms`)」或「系统框架 (`android`)」**。Runami GPS 已经在目标应用进程内直接集成了底层 Binder 与 GMS 融合定位 SDK 劫持，勾选 GMS 服务本身会导致系统全局定位后台被污染，进而影响未勾选应用的真实定位透传。模块已内置硬性隔离过滤机制。
2. **设置伪装位置**
   - 打开 **Runami GPS**，在地图上点击选点、使用顶部搜索框搜索地址/经纬度，或从「收藏夹」中选择位置。
   - 点击右下角 **开始** 按钮激活定位伪装（可通过悬浮摇杆或重新选点实时同步更新位置）。
3. **验证与排查（运行日志）**
   - 打开侧边栏 -> **运行日志**，即可查看实时的定位拦截记录（绿色 `定位拦截 · 已伪装` 或橙色 `定位请求 · 透传`）。
   - 点击顶部 **「层级: ERROR」** 芯片可切换自身运行日志的显示层级（`ERROR` / `WARN` / `INFO` / `DEBUG`），或通过右上角菜单导出 `.log` 诊断文件。

---

## 本地构建

### 1. 配置地图 API Key（可选）
项目使用 `secrets-gradle-plugin` 读取 `.env`（默认回退至 `.env.example`）：

```bash
cp .env.example .env
# 在 .env 中填入你的 MAPS_API_KEY
```

### 2. 编译 APK
```bash
# 构建 Debug 版本
./gradlew assembleDebug

# 构建 Release 版本（可传入自定义版本标签）
./gradlew assembleRelease -PversionNameProp=release-1
```

---

## 致谢与开源许可

- **维护者**：[@Akiyama-25](https://github.com/Akiyama-25)
- **项目仓库**：[https://github.com/Akiyama-25/Runami-GPS](https://github.com/Akiyama-25/Runami-GPS)
- **上游与致谢**：
  - [@jqssun/android-gps-setter](https://github.com/jqssun/android-gps-setter)
  - [@Android1500/GpsSetter](https://github.com/Android1500/GpsSetter)

本项目基于 [GNU General Public License v3.0 (GPL-3.0)](LICENSE) 开源许可发布。
