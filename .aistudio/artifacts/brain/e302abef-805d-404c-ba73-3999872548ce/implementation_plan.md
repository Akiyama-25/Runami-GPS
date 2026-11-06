# GPS Setter — 定位标识展示、活跃态位置热更新与进程级深度 Hook 隔离方案

本方案旨在彻底解决应用刚启动时已开启伪装状态下定位标识（Marker）缺失、目标应用在活跃定位期间切换伪装坐标导致“位置信息不可用”或无法实时刷新的问题，并通过“目标应用进程内全链路深度拦截”取代污染全局 `system_server` 的系统服务 Hook，实现对未勾选应用 100% 透传真实位置、对已勾选应用保持高精度无感伪装。

---

## 用户评审与关键决策确认

> [!IMPORTANT]
> 以下核心架构与交互策略已根据您的确认定稿，将在批准后直接执行：

- **已确认决策 1（未勾选应用透传与 Hook 隔离策略）**：**仅在勾选应用进程内深度拦截定位与传感器回调，天然隔离未勾选应用**。不再对全局系统服务（`system_server` 的 `LocationManagerService`）做无差别的全局注入修改，而是利用 LSPosed 的作用域机制，仅在被勾选的目标应用进程中拦截 `Location`、`LocationManager`（含 `getLastKnownLocation`、`getCurrentLocation`、`requestLocationUpdates` 回调传输层）、`LocationResult`、GNSS 卫星状态/测量回调以及 Google Play Services (`FusedLocationProvider`) 位置分发通道。
- **已确认决策 2（伪装开启状态下的实时更新交互）**：**伪装开启状态下，点击地图、搜索或选择收藏夹时立即实时更新位置**。当伪装处于开启状态时，任何地图选点、地址搜索跳转或收藏夹选点都会立即将最新坐标写入共享配置并更新通知栏与活跃定位流，无需重启目标应用或反复开关伪装。
- **已确认决策 3（启动时地图定位标识显示规则）**：**仅当定位伪装处于已开启状态时，在启动应用时显示定位标识**。应用冷启动或恢复时，动态读取当前真实的伪装开关状态；若已开启则立即在地图对应坐标处渲染定位图钉，若未开启则保持隐藏，直至用户点击地图选点或启动伪装。

---

## 1. 概述与核心目标

- **解决的核心痛点**：
  1. **启动时图钉状态不一致**：原实现中地图就绪后对已启动状态（`isStarted`）的图钉可见性逻辑被注释（Full 变体）或未区分启停状态与空图钉点击（FOSS 变体），且视图模型中的坐标与状态属性仅在初始化时静态读取一次，导致启动时定位标识无法正确反映当前伪装状态。
  2. **活跃应用切换位置失效与报“位置不可用”**：
     - 地图选点、搜索和收藏夹跳转在伪装开启时未同步触发持久化写入；
     - `Xshare` 每次读取属性时重复创建 `XSharedPreferences` 实例却从未在运行期调用 `reload()`，导致目标应用进程内的 Hook 始终读取旧缓存；
     - Hook 构造或篡改的 `Location` 对象缺失 `elapsedRealtimeNanos`、完整精度标记（`hasAccuracy`）或时间戳与系统单调时钟不匹配，未能通过 Android 12–14+ 的 `Location.isComplete()` 校验，导致正在监听位置的目标应用判定位置数据损坏并报“位置信息不可用”。
  3. **系统级 Hook 误伤未勾选应用**：原代码在 `android`（`system_server`）进程中直接拦截 `LocationManagerService.injectLocation` 与 `callLocationChangedLocked`，从系统源头篡改了全局位置缓存，导致所有未在 LSPosed 中勾选的应用也拿到伪装坐标。
- **核心价值**：实现“所点即所得”的实时位置热切换体验，确保构造的每一帧 `Location` 对象严格满足 Android 系统完整性校验，并依托进程级全链路拦截让未勾选应用完全不受干扰。

---

## 2. 用户体验与视觉交互设计

### 关键用户流程

1. **应用启动与地图初始化流**：
   - 用户启动 GPS Setter，地图加载完成并将镜头平滑移动至上次保存的经纬度。
   - **当伪装处于已开启状态（`isStarted == true`）**：地图立即在目标坐标处显示红色定位图钉，底部浮动按钮呈“停止（Stop）”状态。
   - **当伪装处于未开启状态（`isStarted == false`）**：地图不显示定位图钉，底部浮动按钮呈“开始（Start）”状态。
2. **选点与实时热更新流（伪装已开启时）**：
   - 用户在地图上点击任意新位置、通过顶部搜索框搜索坐标/地址跳转、或从侧边栏收藏夹选择地点。
   - 地图图钉立即平滑移动至新位置；系统后台同步将新坐标写入可跨进程读取的共享配置，并自动刷新通知栏地址信息。
   - 后台或分屏中处于活跃定位状态的目标应用在下一帧定位回调（或主动拉取）时立即收到合法、完整的最新伪装坐标，地图蓝点平滑跳转，不再出现“位置信息不可用”报错。
3. **选点与预备启动流（伪装未开启时）**：
   - 用户点击地图选点、搜索或选择收藏夹时，地图图钉立即显现并落在新选定的坐标上，方便用户预览和微调位置；点击“开始”按钮后正式激活伪装。

### 视觉与交互反馈细节

- **一致的材质与控件状态**：保持现有 Material Design 3 动态配色（`DynamicColors`）与沉浸式状态栏/导航栏体验，修正收藏夹添加弹窗对“当前是否已选点（`hasMarker`）”的判断反转错误，确保已放置图钉时可正常保存收藏，未放置图钉时给出明确 Toast 提示。

---

## 3. 核心架构决策与技术权衡

### 决策 1：目标应用进程内全链路深度拦截 vs. `system_server` 全局注入
- **选定方案**：完全移除对 `system_server`（`android` 进程）全局位置源的无差别篡改，改为在 LSPosed 勾选的目标应用进程内实施多层级深度 Hook（覆盖 `Location` 方法层、`LocationManager` 主动拉取与 `ILocationListener` / `LocationCallback` 异步回调传输层、`LocationResult` 容器层，以及針對启用高级欺骗选项时的 GNSS 卫星状态与模拟标记清除）。
- **原因与优势**：
  - **零误伤透传**：未在 LSPosed 中勾选的应用进程根本不会加载本模块，直接与原生的 `system_server` 通信获取真实 GPS/网络位置，彻底杜绝全局污染。
  - **高伪装性能**：通过在应用进程内同时拦截底层回调传输对象（如 `LocationListenerTransport`、`GetCurrentLocationTransport`、GMS `FusedLocationProvider` 回调与 `LocationResult`）以及 `Location` 自身 Getter/Setter，并自动清除 `isFromMockProvider` 标记与注入合理的卫星/精度参数，勾选应用无论使用系统定位还是融合定位 SDK 都能获得无缝的伪装效果。
- **放弃的替代方案**：在 `system_server` 的 `injectLocation` 处拦截。该方案在位置进入系统服务的第一站就改写了全局 Provider 的内部状态，无法区分下游订阅者是否被勾选。

### 决策 2：完整的 `Location` 对象构造与 `XSharedPreferences` 热重载机制
- **选定方案**：
  - 单例化持有 `XSharedPreferences`，在每次节流窗口（如 80ms~150ms）到期触发 `updateLocation()` 时显式调用 `makeWorldReadable()` 与 `reload()`，确保目标应用在不重启的情况下毫秒级感知最新坐标与开关状态。
  - 统一封装 `buildSpoofedLocation(origin: Location?, provider: String)` 工厂函数：完整填充 `latitude`、`longitude`、`accuracy`、`time`（`System.currentTimeMillis()`）、`elapsedRealtimeNanos`（`SystemClock.elapsedRealtimeNanos()`）、`altitude`、`bearing`、`speed` 及 Android O+ 的垂直/速度/方向精度字段，并通过 `HiddenApiBypass` 清除 Mock 标记。
- **原因与优势**：Android 12+ 的 `LocationManager` 与三方定位 SDK 会校验 `Location.isComplete()` 及 `elapsedRealtimeNanos` 的单调递增性；此前缺失 `elapsedRealtimeNanos` 或时间戳停滞会导致目标应用在位置更新时直接丢弃数据并报错“位置不可用”。

---

## 4. 技术架构与数据流设计

### 系统架构与跨进程组件关系图

```
┌────────────────────────────────────────────────────────────────────────────┐
│                        GPS Setter 主应用进程 (UI & 配置源)                    │
│                                                                            │
│  ┌──────────────────────┐    实时状态/坐标读写    ┌────────────────────────┐ │
│  │  MapActivity (Full & │ ─────────────────────► │     MainViewModel      │ │
│  │  FOSS) / BaseMapUI   │ ◄───────────────────── │  (动态读取最新状态/坐标) │ │
│  └──────────┬───────────┘                        └───────────┬────────────┘ │
│             │ 地图选点 / 搜索 / 收藏夹 / 摇杆                   │              │
│             ▼                                                ▼              │
│  ┌───────────────────────────────────────────────────────────────────────┐ │
│  │               PrefManager (MODE_WORLD_READABLE 共享首选项)             │ │
│  │   [start, latitude, longitude, accuracy_level, random_position, ...]  │ │
│  └───────────────────────────────────┬───────────────────────────────────┘ │
└──────────────────────────────────────┼─────────────────────────────────────┘
                                       │ 跨进程文件监听 / reload() 热同步
          ┌────────────────────────────┴────────────────────────────┐
          ▼ (LSPosed 已勾选的目标应用进程)                             ▼ (LSPosed 未勾选的应用进程)
┌───────────────────────────────────────────────────┐     ┌─────────────────────────────┐
│             LocationHook & Xshare                 │     │      不加载 Xposed 模块       │
│  1. Xshare 单例缓存 + 节流周期显式 reload()         │     │                             │
│  2. 定位数据全链路拦截与 Location 完整性构造：       │     │  直接与系统 LocationManager  │
│     • Location (getLat/Lng/Accuracy/set/isMock)   │     │  及 GMS 通信，100% 透传真实   │
│     • LocationManager (getLastKnown/getCurrent)   │     │  硬件 GPS / 网络定位数据      │
│     • LocationResult & 异步 Listener/Callback 回调 │     │                             │
│     • 高级模式：GNSS 卫星状态伪装 & 隐藏 Mock 标记   │     │                             │
└───────────────────────────────────────────────────┘     └─────────────────────────────┘
```

### 数据模型与状态管理

- **实时状态读取（`MainViewModel` & `PrefManager`）**：
  - 将 `MainViewModel` 中的 `getLat`、`getLng`、`isStarted`、`mapType` 从一次性初始化属性改为实时计算属性（`get() = prefManger.xxx`），防止 Activity 重建或运行期状态变更后读取到陈旧快照。
  - `PrefManager.update` 确保写入操作后文件具备可读权限，供 `XSharedPreferences` 立即感知变更。
- **跨进程配置热加载（`Xshare`）**：
  - 维护单一 `XSharedPreferences` 实例，提供显式的 `reload()` 方法，在 `LocationHook.updateLocation()` 中先执行 `settings.reload()` 再读取 `isStarted`、`getLat`、`getLng`、`accuracy` 与 `isRandomPosition`。

### 交互组件与状态转换映射

1. **应用启动与图钉初始化（`MapActivity` - Full & FOSS 双变体）**：
   - 在 `onMapReady` 中读取 `viewModel.getLat`、`viewModel.getLng` 与 `viewModel.isStarted`。
   - **Full 变体（Google Maps）**：创建 `mMarker` 时将初始 `visible` 设为 `viewModel.isStarted`；若 `viewModel.isStarted == true`，调用 `showInfoWindow()` 展示当前标记。
   - **FOSS 变体（MapLibre）**：仅当 `viewModel.isStarted == true` 时在 `onMapReady` 中调用 `updateMarker(it)` 添加图钉；若未开启则不添加图钉（保持 `mMarker = null`）。
   - **收藏夹校验修复（`BaseMapActivity.addFavoriteDialog`）**：统一 `hasMarker()` 语义为“当前地图上是否存在可见的定位图钉”（Full 变体返回 `mMarker?.isVisible == true`，FOSS 变体返回 `mMarker != null`），并在 `addFavoriteDialog` 中当 `!hasMarker()` 时提示未选择位置，當 `hasMarker()` 时正常保存。
2. **伪装开启状态下的实时选点热更新（`onMapClick` & `moveMapToNewLocation`）**：
   - 用户在地图上点击（`onMapClick`）时：更新 `lat`、`lon`，调用 `updateMarker` 显示/移动图钉并移动镜头（解决 FOSS 变体此前 `mMarker == null` 时点击地图无反应的问题）。
   - 若当前 `viewModel.isStarted == true`：无论是 `onMapClick` 选点，还是搜索地址、选择收藏夹、点击定位按钮触发 `moveMapToNewLocation(true)`，均立即调用 `viewModel.update(true, lat, lon)` 同步写入新坐标，并异步更新通知栏地址展示。
3. **目标应用活跃定位热更新与防报错机制（`LocationHook`）**：
   - **移除全局 `system_server` 污染**：当 `lpparam.packageName == "android"` 时不再篡改全局 `LocationManagerService` 位置流，避免任何未勾选应用被干扰。
   - **构造合法完整的 `Location` 实例**：每次生成或替换 `Location` 时，确保设置：
     - `latitude = newlat`、`longitude = newlng`、`accuracy = accuracy`（若 `accuracy <= 0f` 则兜底为 `10.0f`）
     - `time = System.currentTimeMillis()`
     - `elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()`
     - `altitude`、`speed`、`bearing` 及对应的精度字段
     - 调用 `HiddenApiBypass` 将 `setIsFromMockProvider(false)` 写入对象，并拦截 `Location.isFromMockProvider()` / `Location.isMock()` 强制返回 `false`。
   - **拦截活跃定位回调流**：除 `Location` 的 `getLatitude`、`getLongitude`、`getAccuracy`、`set` 与 `LocationManager.getLastKnownLocation` 外，补充对 `LocationResult`（Android 12+ 位置回调载体，如 `asList()` / `getLastLocation()`）及 `LocationListener.onLocationChanged` 的拦截，确保活跃监听中的目标应用在伪装坐标改变或中途关闭伪装时，能立刻平滑收到有效的新坐标或恢复真实定位。
