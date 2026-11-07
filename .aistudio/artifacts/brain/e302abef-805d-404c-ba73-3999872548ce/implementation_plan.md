# 免勾选系统框架与免重启：目标进程内底层 Binder & AIDL 深度定位拦截方案

本方案通过**在被勾选的目标应用进程内部直接拦截系统 `ILocationManager` Binder 代理与 `ILocationListener` / `ILocationCallback` / `IGnssStatusListener` AIDL 回调存根（Stub）**，彻底替代原仓库对 `System Framework (android / system_server)` 的依赖。模块安装、启用或更新后**完全无需重启手机**，只需重开目标应用即可立即生效，同时 100% 天然隔离未勾选应用。

---

## 用户确认与关键决策

> [!IMPORTANT]
> 以下架构决策已根据您的确认锁定，完全移除对 `android`（系统框架）的依赖与重启要求。

- **确认决策 1（拦截架构）**：采用**目标进程内底层 Binder 与 AIDL 拦截方案**，在目标应用进程的 IPC 边界直接劫持系统定位服务返回的数据与回调，彻底免勾选系统框架、免重启手机。
- **确认决策 2（默认作用域配置）**：完全移除默认作用域中的 `android`（系统框架），用户仅需在 LSPosed / Vector 中勾选需要伪装的目标应用即可生效。

---

## 1. 核心概念与价值

- **解决痛点**：原仓库将 `android`（系统框架）设为必选项并直接 Hook `system_server` 中的 `LocationManagerService`，不仅每次安装/更新模块都必须重启手机，还会污染全局系统定位状态导致未勾选应用定位异常。
- **核心原理**：Android 系统中，`system_server` 与应用进程之间通过 Binder IPC 通信。目标应用通过 `ILocationManager` 代理向系统发起请求，系统通过应用进程内的 `ILocationListener.Stub`、`ILocationCallback.Stub` 及 `IGnssStatusListener.Stub` 将位置和卫星状态跨进程回调给应用。只要在目标应用进程的 **Binder IPC 入口与出口** 直接完成拦截与改写，就能在不触碰 `system_server` 的前提下达到与系统级 Hook 完全等价的底层伪装效果。
- **核心价值**：
  1. **零重启体验**：启用模块、更新模块或切换配置均无需重启系统，重开目标应用即刻生效。
  2. **底层防检测**：在 AIDL Stub 刚反序列化出 `Location` / `GnssStatus` 的第一时间完成坐标改写与 `mock` 标记清除，上层 SDK 与应用代码完全无感。
  3. **绝对进程隔离**：仅在 LSPosed 勾选的应用进程内生效，未勾选应用 100% 走真实系统定位。

---

## 2. 用户体验与视觉交互设计

- **关键使用流程**：
  1. 用户在 LSPosed / Vector 管理器中启用 **Runami GPS**，无需勾选“系统框架”，直接勾选需要伪装的目标应用（如地图、社交或游戏应用）。
  2. 打开 **Runami GPS** 选择目标坐标并点击开启伪装，随时在设置中开启 **“深度底层 Binder & GNSS 伪装（免重启）”**。
  3. 划掉并重新打开目标应用（无需重启手机），目标应用即刻获取伪装位置；在 **“运行与定位请求日志”** 页面可实时查看目标应用触发的 Binder IPC 与 SDK 定位请求明细。
- **视觉与文案更新**：
  - **设置页清晰指引**：将设置页原“Hook system location”升级为 **“深度底层 Binder & GNSS 伪装（免重启）”**，副标题明确标注“在目标进程内拦截 ILocationManager Binder 通信与 GNSS 卫星状态，无需勾选系统框架或重启手机”。
  - **日志标签与溯源增强**：在日志查看器中区分记录 **Binder/AIDL 底层拦截**（如 `ILocationManager.getLastLocation`、`ILocationListener.Stub.onLocationChanged`、`IGnssStatusListener.Stub`）与 **SDK 层拦截**，方便直观验证底层拦截是否正常工作。

---

## 3. 关键技术决策与权衡

- **决策 1：在目标进程内拦截 AIDL Stub/Proxy 而非 Hook `system_server`**
  - *所选方案*：在目标应用进程加载时，动态定位并 Hook `android.location.ILocationManager$Stub$Proxy` 的全部定位请求方法，以及注册进来的 `ILocationListener`、`ILocationCallback`、`IGnssStatusListener` 实例与 `LocationTransport` 内部类。
  - *原因*：`LocationManager` 内部通过 `LocationListenerTransport`（实现 `ILocationListener.Stub`）接收来自 `system_server` 的 `onLocationChanged(List<Location>, IRemoteCallback)`。在此处拦截可在数据进入应用主线程前直接在 Binder 线程池完成覆写，既具备 `system_server` 级别的底层隐蔽性，又无需重启手机。
- **决策 2：主动接管 Provider 可用性与 GNSS 卫星状态**
  - *所选方案*：当开启深度拦截时，同步在 `ILocationManager` 代理层拦截 `isProviderEnabled` / `isProviderEnabledForUser` / `getProviders`，确保 `gps` 与 `network` 始终向目标应用报告已启用状态；同时拦截 `GnssStatus` 与 `IGnssStatusListener` 回调，伪造高信噪比卫星状态。
  - *原因*：防止部分应用通过底层 Binder 直接查询 GPS 开关状态或检验卫星信噪比来识别虚拟定位。

---

## 4. 技术架构与数据流设计

### 目标进程内多层定位拦截架构图

```
┌─────────────────────────────────────────────────────────────────────────┐
│                      被勾选的目标应用进程 (Target App)                   │
│                                                                         │
│  ┌───────────────────────────┐       ┌───────────────────────────────┐  │
│  │  应用业务层 / 第三方定位SDK  │       │  Google Play 融合定位 (GMS)   │  │
│  └─────────────┬─────────────┘       └───────────────┬───────────────┘  │
│                │                                     │                  │
│                ▼                                     ▼                  │
│  ┌───────────────────────────────────────────────────────────────────┐  │
│  │ [第二层：SDK API 拦截层 + 1Hz 实时位置推送器 (Live Ticker)]        │  │
│  │ • LocationManager / Location / LocationResult / GnssStatus        │  │
│  │ • FusedLocationProviderClient / LocationCallback                  │  │
│  └─────────────┬─────────────────────────────────────▲───────────────┘  │
│                │                                     │                  │
│                ▼                                     │                  │
│  ┌───────────────────────────────────────────────────────────────────┐  │
│  │ [第一层：底层 Binder & AIDL IPC 拦截层 (免勾选系统框架核心)]       │  │
│  │ • ILocationManager$Stub$Proxy:                                    │  │
│  │   - getLastLocation / getCurrentLocation / registerLocationListener│  │
│  │   - isProviderEnabledForUser / getProviders / registerGnssStatus  │  │
│  │ • ILocationListener.Stub (LocationListenerTransport):             │  │
│  │   - onLocationChanged(List<Location>, IRemoteCallback)            │  │
│  │ • ILocationCallback.Stub: onLocation(Location)                    │  │
│  │ • IGnssStatusListener.Stub: onSvStatusChanged(GnssStatus)         │  │
│  │ • HiddenApiBypass 清除 Location.mIsFromMockProvider 掩码           │  │
│  └─────────────┬─────────────────────────────────────▲───────────────┘  │
└────────────────┼─────────────────────────────────────┼──────────────────┘
                 │ Binder IPC 请求                      │ Binder IPC 回调
                 ▼                                     │ (进入进程即被覆写)
┌─────────────────────────────────────────────────────────────────────────┐
│            Android 系统服务进程 (system_server - 无需注入/无需重启)       │
└─────────────────────────────────────────────────────────────────────────┘
```

### 核心拦截与状态映射说明

1. **`ILocationManager$Stub$Proxy` 代理拦截**：
   - **`getLastLocation` / `getLastKnownLocation`**：在方法返回后，若伪装开启则直接将返回的 `Location`（或 `null`）替换为带有完整 `elapsedRealtimeNanos`、卫星数与清除了模拟标记的伪装 `Location`。
   - **`getCurrentLocation`**：拦截传入的 `ILocationCallback`（AIDL 接口）参数，立即对其实现类的 `onLocation(Location)` 方法挂载 Hook，并在伪装开启时直接回调伪装坐标。
   - **`registerLocationListener` / `requestLocationUpdates`**：提取传入的 `ILocationListener`（AIDL Stub，如 `LocationManager$LocationListenerTransport`），动态 Hook 其 `onLocationChanged` 方法，确保 `system_server` 每次跨进程回调的 `Location` 或 `List<Location>` 在第一时间被原地改写为伪装坐标。
   - **`isProviderEnabled` / `isProviderEnabledForUser`**：当开启深度底层伪装且查询 `gps` 或 `network` 时，强制返回 `true`。
2. **`IGnssStatusListener` & `GnssStatus` 底层卫星伪装**：
   - 拦截 `ILocationManager$Stub$Proxy.registerGnssStatusCallback`，动态 Hook 传入的 `IGnssStatusListener` 实现类的 `onSvStatusChanged(GnssStatus)`，结合 `GnssStatus` 方法拦截，保证目标应用读取到充足的参与定位卫星数（$\ge 12$ 颗）与高信噪比（`38.0 dBHz`）。
3. **实时日志埋点与反馈**：
   - 所有底层 Binder/AIDL 拦截事件均通过 `HookLogReporter` 记录 `[Binder]` 前缀标识并同步至 **Runami GPS** 的日志查看器，便于直观核验免重启底层拦截效果。
