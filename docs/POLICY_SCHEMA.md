# HyperGreeze 通道与策略 Schema

> 本文是 `Settings.System` 通道的唯一契约文档。键名、字段名、取值、默认值、上限逐条对照代码，行内标注 `文件:行`。
> 代码里没有的东西，本文一律留空；代码与本文冲突时以代码为准，并请同步改本文。
> 锚点写成 `文件:行`，且每条都同时给出符号名（方法/字段/键）；代码改动导致行号漂移时，以符号名为准。
>
> 基线：`PolicyCodec.VERSION = 2`（`core/PolicyCodec.kt:14`）、模块 `versionName 1.0.0` / `versionCode 1`（`app/build.gradle.kts:24-25`）、libxposed **api 102**（`module/HyperGreezeModule.kt:11`，`META-INF/xposed/module.prop`）。

---

## 0. 速查

| 类别 | 名称 | 方向 | 格式 |
|---|---|---|---|
| 键 | `greeze_ctl_policy_v1` | App → hook | JSON object（策略文档） |
| 键 | `greeze_ctl_command_v1` | App → hook | JSON object（一次性命令） |
| 键 | `greeze_ctl_heartbeat_v1` | hook → App | JSON object |
| 键 | `greeze_ctl_events_v1` | hook → App | JSON array（≤60 条） |
| 键 | `greeze_ctl_logs_v1` | hook → App | JSON array（≤120 行字符串） |
| 键 | `greeze_ctl_boot_guard_v1` | — | 已声明，**无读写方**（见 §11） |
| 键 | `greeze_ctl_safe_mode_v1` | App 写入，**无 hook 读取方**（见 §11） | 字符串 `"1"` |
| 键 | `greeze_ctl_safe_mode_reason_v1` | App 写入（自检探针也用它） | 任意字符串 |
| 属性 | `persist.sys.hypergreeze.guard` | hook 读写 | 十进制整数 |
| 属性 | `persist.sys.hypergreeze.safe` | hook 读写 | `0` / `1` |
| 属性 | `persist.sys.hypergreeze.safe_reason` | hook 读写 | 字符串 |
| 广播 | `dev.hypergreeze.action.STATE_CHANGED` | — | 已声明，**未发送/未注册**（见 §11） |

常量出处：`core/Keys.kt:12,15,18,21,24,27,30,33,36`；属性名：`module/SafetyGuard.kt:81-83`。

---

## 1. 通道选型：为什么是 `Settings.System`

### 1.1 对照可行性报告 §3.2 的四个方案

| 方案 | 报告 §3.2 的判断 | 本模块的取舍 | 代价 / 边界 |
|---|---|---|---|
| **`Settings.System` JSON 键** | 推荐。App 侧需 `WRITE_SETTINGS`；`system_server` 直接读；无 SELinux 问题；跨重启持久；可被 `ContentObserver` 监听；天然按用户分库 | **采用，单通道双文档**（App→hook 写 `policy`/`command`，hook→App 写 `heartbeat`/`events`/`logs`） | App 必须拿到 appop `WRITE_SETTINGS`，否则写入被拒（`ui/data/Repos.kt:48-55` 显式返回 `false`，UI 报错而不假装保存） |
| `XSharedPreferences` + `xposedsharedprefs` meta-data | 只读语义为主、写回不实时，适合当 boot 期初值 | 不用 | 两个独立原因：①本模块要「UI 改完秒级生效 + 回读心跳」的双向语义，prefs 是 key-value 增量、不是整份文档；②新 API 下它属 legacy 路径（既有定论见 `ARCHITECTURE.md` §2） |
| 广播（hook → UI 回写） | 适合事件推送，不适合大数据 | 只留常量 `Keys.ACTION_STATE_CHANGED`（`core/Keys.kt:36`），**当前代码没有发送方也没有接收方** | 快路径实际由 `ContentObserver` + 5 s tick 轮询承担（`ModuleController.kt:165-187, 191-228`） |
| Unix abstract socket / 文件 | `system_server` 连 App socket 常被 SELinux 拦；App 写 `/data/system/` 走不通 | 不用 | 一旦被 SELinux 拦就是整条通道静默失效，没有可观测的失败信号 |

### 1.2 采用 `Settings.System` 的五个具体理由

1. **写权限只需一个 appop**：`<uses-permission android:name="android.permission.WRITE_SETTINGS" />`（`AndroidManifest.xml:6`），用户可在「修改系统设置」里授权，或 `adb shell appops set dev.hypergreeze android:write_settings allow`。
2. **`system_server` 可直接读**：hook 侧只调 `Settings.System.getString(context.contentResolver, key)`（`module/PolicyStore.kt:84-89`、`module/ModuleTelemetry.kt:121-126`）。读路径在系统进程内部完成，App↔`system_server` 之间不存在 socket 或文件往返。
3. **没有 SELinux 跨域问题**：写入方是普通 App，落点是 `SettingsProvider`；hook 侧只做同进程读取。两端都不碰对方的数据目录。
4. **天然按用户分库**：`Settings.System` 的存储本身以 userId 分区。**当前实现的边界**：`PolicyStore` / `ModuleTelemetry` 都用不带 user 参数的 `getString`，即 hook 侧读出的是 `system_server` 上下文对应的 user 0；App 侧写的是当前用户。工作资料 / 分身要与主用户共享策略，需要改成 `getStringForUser(..., userId)`，这属于后续改动（见 §11）。
5. **跨重启持久 + 可外部读写**：`settings put` 写完就在数据库里，重启后 hook 侧 `PolicyStore.start()` 第一件事就是 `reload()`（`module/PolicyStore.kt:43-45`），因此「adb 手改 → 重启仍生效」成立；这一点也是全部逃生手段的基础（§9）。

### 1.3 单写者约定与写放大

| 键 | 唯一写入方 | 唯一读取方 |
|---|---|---|
| `policy` / `command` | App（`SettingsGateway`） | hook（`PolicyStore` / `ModuleController.processCommand`） |
| `heartbeat` / `events` / `logs` | hook（`ModuleTelemetry`） | App（`SettingsGateway.read*`） |

这条约定是通道不需要仲裁的根本原因：没有两端同时写同一个键的路径，也就不需要版本号之外的并发控制。

一次 Settings 写入 = 一次到 `SettingsProvider` 的调用 + 一次数据库写，因此 hook 侧全部写操作都做了收敛：

| 文档 | 写入时机 | 节流 |
|---|---|---|
| `heartbeat` | Context 就绪、启动后 90 s 稳定窗口、每 6 个 tick（约 30 s）、每条命令执行后、策略 revision 变化 | `HEARTBEAT_MIN_INTERVAL_MS = 5000`（`ModuleTelemetry.kt:157`）；但 `ModuleController.publishHeartbeat()` 恒传 `force = true`（`ModuleController.kt:255`），实际按上述时机全量写 |
| `events` | `record()` 只标脏；`flushEvents()` 才落盘 | 仅脏时写（`ModuleTelemetry.kt:92-99`）；`forcedFreeze` 与 `processCommand` 主动 flush |
| `logs` | tick 内 `flushLogs()` | `LOG_FLUSH_INTERVAL_MS = 15000`（`ModuleTelemetry.kt:158`） |

---

## 2. 每个键一张表

### 2.1 `greeze_ctl_policy_v1`

| 键名 | 写入方 | 类型 | 内容 | 容量 / 上限 | 示例 |
|---|---|---|---|---|---|
| `greeze_ctl_policy_v1` | **App**（`SettingsGateway.writePolicy` → `Settings.System.putString`，`ui/data/Repos.kt:59`） | JSON object（字符串） | 整份策略文档，schema 见 §3 | 代码未设条目上限；整份重写、无增量；只序列化非默认字段（`PolicyCodec.encodeApp` 的 `putOpt`） | `{"v":2,"rev":12,"ts":1761692400000,"global":{"enabled":true,"forceFreezeOnBackground":true,"audit":true,"patchLists":false,"compactEvents":false},"apps":{"com.tencent.mm":{"mode":"immediate","keep":16777218}}}` |

- 读取方：hook（`module/PolicyStore.kt:84-89`），注册 `Settings.System.getUriFor(Keys.POLICY)` 且 `descendant = true`（`PolicyStore.kt:57-61`），变更即时 `reload()`；每个 tick（5 s）的 `reload()` 是兜底。
- 变更判定：`reload()` 先比较**原始字符串**，相同则直接返回 `false`（`PolicyStore.kt:72`），不相同才解析并比较 `Policy` 相等性。
- 键被删除时 `readRaw()` 返回 `null` → `PolicyCodec.decode(null)` → `Policy.EMPTY`（`revision = 0`），模块回到纯跟随。

### 2.2 `greeze_ctl_command_v1`

| 键名 | 写入方 | 类型 | 内容 | 容量 / 上限 | 示例 |
|---|---|---|---|---|---|
| `greeze_ctl_command_v1` | **App**（`SettingsGateway.sendCommand`，`ui/data/Repos.kt:67`） | JSON object（字符串） | 一次性命令，见 §5 | 单对象；执行后**不清键**（靠 `seq` 水位去重），永远只会存在一条 | `{"seq":1761692400123,"op":"thawAll"}` |

- 读取方：hook（`ModuleController.processCommand`，`ModuleController.kt:260-282`），两条触发路径：`ContentObserver`（注册 `descendant = false`，`ModuleController.kt:173-178`）与每个 tick（5 s）里的 `processCommand()`。
- 与 `policy` 共用一个 observer 回调，但注册成两条 URI：

```text
registerContentObserver(Settings.System.getUriFor(Keys.COMMAND), false, observer)   // ModuleController.kt:173-178
registerContentObserver(Settings.System.getUriFor(Keys.POLICY),  false, observer)   // ModuleController.kt:178-183
```

### 2.3 `greeze_ctl_heartbeat_v1`

| 键名 | 写入方 | 类型 | 内容 | 容量 / 上限 | 示例 |
|---|---|---|---|---|---|
| `greeze_ctl_heartbeat_v1` | **hook**（`ModuleTelemetry.publishHeartbeat`，`module/ModuleTelemetry.kt:52-61`） | JSON object（字符串） | 模块/框架/GreeZe/钩子/能力快照，见 §6 | 单对象；`hooks` 数组 = 8 个稳定 id + `capture-ctor-N`（按构造器个数）；`caps` 固定 18 键 | `{"ts":1761692400000,"api":102,"safeMode":false,"lastSeq":1761692400123,"hooks":[…]}` |

### 2.4 `greeze_ctl_events_v1`

| 键名 | 写入方 | 类型 | 内容 | 容量 / 上限 | 示例 |
|---|---|---|---|---|---|
| `greeze_ctl_events_v1` | **hook**（`ModuleTelemetry.flushEvents`，`ModuleTelemetry.kt:92-99`） | JSON array（字符串） | 冻结/解冻/跳过/命令结果事件，见 §7 | **`Module.EVENT_RING = 60`**（`core/Keys.kt:58`），超出丢最旧（`ModuleTelemetry.kt:63-69`） | `[{"ts":1761692400123,"t":"skip","pkg":"com.tencent.mm","uid":10123,"r":2,"res":"SKIP_BY_MOD","d":"never-freeze policy"}]` |

- `clearEvents` 命令写 `"[]"`（`ModuleTelemetry.kt:101-108`）。
- 启动时 `loadExistingEvents()` 读回上次内容并 `takeLast(60)`（`ModuleTelemetry.kt:39-49`）。
- UI 读取时 `asReversed()`（`ui/data/Repos.kt:63`），即页面显示「最新在上」。

### 2.5 `greeze_ctl_logs_v1`

| 键名 | 写入方 | 类型 | 内容 | 容量 / 上限 | 示例 |
|---|---|---|---|---|---|
| `greeze_ctl_logs_v1` | **hook**（`ModuleTelemetry.flushLogs`，`ModuleTelemetry.kt:112-117`） | JSON array\<string\> | `Logx` 环形缓冲快照，见 §8 | **`Module.LOG_RING = 120`** 行（`core/Keys.kt:57`） | `["I hooks installed=8/8 passive=false guard=1","W hook screen-on-thaw: target missing (…)"]` |

### 2.6 `greeze_ctl_boot_guard_v1`

| 键名 | 写入方 | 类型 | 内容 | 容量 / 上限 | 示例 |
|---|---|---|---|---|---|
| `greeze_ctl_boot_guard_v1` | **无**（常量只在 `Keys.ALL` 里出现） | 声明为「纯整数字符串」 | 意图是把守卫计数镜像到 Settings；**实际实现用的是系统属性**，见 §11 | — | 期望形态 `"2"`，但当前没有任何代码写入它 |

### 2.7 `greeze_ctl_safe_mode_v1`

| 键名 | 写入方 | 类型 | 内容 | 容量 / 上限 | 示例 |
|---|---|---|---|---|---|
| `greeze_ctl_safe_mode_v1` | **App**（`SettingsGateway.requestSafeMode`，`Repos.kt:69-72`） | 字符串 | 手动请求安全模式的标记 | 固定 `"1"` | `1` |

- **必须知道的事实**：hook 侧没有读取这个键的代码。安全模式真正的闩锁是系统属性 `persist.sys.hypergreeze.safe`（`SafetyGuard.kt:40, 71-72`）。要让「手动请求」真的生效，当前只能 `adb shell setprop persist.sys.hypergreeze.safe 1` 后重启（§9 与 TEST_PLAN 阶段 8/11 都按这条路径写）。见 §11。

### 2.8 `greeze_ctl_safe_mode_reason_v1`

| 键名 | 写入方 | 类型 | 内容 | 容量 / 上限 | 示例 |
|---|---|---|---|---|---|
| `greeze_ctl_safe_mode_reason_v1` | **App**（`requestSafeMode` 写原因；`selfTest` 写探针） | 字符串 | 人类可读原因 / 写入自检探针 | 无约束 | `用户从界面请求` / `hypergreeze-probe-1761692400123` |

- 写入自检用它做一次写-读回环（`Repos.kt:75-85`），因此**它会覆盖上一次的安全模式原因文本**。`selfTest` 的四个分支：未授权 appop / 写入动作被系统挡下 / 写入成功但读回值不一致 / 写入与读回一致。四个返回串的原文在 `ui/data/Repos.kt:76-84`，诊断页直接显示。
- 心跳里的 `safeReason` 取自属性 `persist.sys.hypergreeze.safe_reason`（`SafetyGuard.kt:78`），**不是**这个键。

---

## 3. 策略 JSON 完整 schema

### 3.1 根对象

| 字段 | 类型 | 默认值 | 取值范围 | 生效条件 |
|---|---|---|---|---|
| `v` | int | `2`（解码缺省 `PolicyCodec.VERSION`，`PolicyCodec.kt:35`） | 任意 int | 信息性。解码不做版本门禁，`v` 写几都照常解析；编码回写的是 `policy.version` |
| `rev` | long | `0` | ≥0 | 信息性 + 心跳展示（`Heartbeat.revision`）；`Policy.withApp/withGlobal` 每次编辑 `+1`（`core/Policy.kt:18-31`） |
| `ts` | long | `0` | epoch ms | 信息性，编辑时置 `System.currentTimeMillis()` |
| `global` | object | `GlobalPolicy()` | 见 §3.2 | 缺省即默认值；`enabled = false` 时全部 hook 变成直通（`PolicyEngine.kt:18`） |
| `apps` | object | `{}` | `包名 → app 对象` | 条目的 `isEffective == false` 时，解码阶段被**丢弃**（`PolicyCodec.kt:53-54`），即 `{"mode":"follow"}` 这种空壳不会驻留 |

### 3.2 `global`

| 字段 | 类型 | 默认值 | 取值范围 | 生效条件 |
|---|---|---|---|---|
| `enabled` | bool | `true` | `true`/`false` | 总开关。`false` ⇒ 所有判定返回原始值（`PolicyEngine.enabled`） |
| `forceFreezeOnBackground` | bool | `true` | `true`/`false` | 与 `mode == immediate` 同时成立才强制冻结（`PolicyEngine.forceFreezeOnBackground`，`PolicyEngine.kt:34-37`） |
| `audit` | bool | `true` | `true`/`false` | 关掉后 `freezeUid` 的审计事件不再写（`HookInstaller.kt:320`） |
| `patchLists` | bool | `false` | `true`/`false` | **当前无逻辑读取**：`applyLists` 命令不受它控制（见 §11） |
| `compactEvents` | bool | `false` | `true`/`false` | **当前无逻辑读取**（见 §11） |
| `filterPolarityInverted` | bool | `false` | `true`/`false` | `true` 时把 `filter` 的**模块覆写**取反（`PolicyEngine.kt:80-81`）；只反转模块自己的答案，系统原值不动。属于「掩码行为与该 ROM 相反」时的逃生开关（诊断页有对应开关） |
| `defaultDelayMs` | long | `0` | `0..600000`（解码 `coerceIn`，`PolicyCodec.kt:171`） | app 的 `delayMs` 为 0 时才用它（`PolicyEngine.kt:39-42`） |
| `applyFreezeBinder` | bool? | `null` | `true`/`false`/缺省 | **当前无逻辑读取**；改写 GreeZe 云开关只走 `setFreezeBinder` 命令（见 §11） |

### 3.3 `apps[<包名>]`

| 字段 | 类型 | 默认值 | 取值范围 | 生效条件 |
|---|---|---|---|---|
| `mode` | string | `"follow"` | `follow` / `never` / `immediate` / `custom`；未知串 → `follow`（`FreezeMode.fromWire`，`core/FreezeMode.kt:23-24`） | 决定下方哪些字段被消费，见 §3.7 |
| `delayMs` | long | `0` | `0..600000`（`coerceIn`，`PolicyCodec.kt:72`） | 仅 `immediate` / `custom` 的强制冻结路径；有效值 = `delayMs > 0 ? delayMs : global.defaultDelayMs` |
| `thawCooldownMs` | long | `0` | `0..3600000`（`coerceIn`，`PolicyCodec.kt:73`） | 仅强制冻结路径：上次尝试后未超冷却则记 `skip(cooldown)`（`HookInstaller.kt:398-403`） |
| `keep` | int | `0` | 位掩码，`SceneBit.value` 的或（§4） | ≥1 即视为有效配置；命中 `reason` 时 `filter` 返回 `false` |
| `drop` | int | `0` | 位掩码；**`LOCKED_DROP` 的位在解码时被清掉** | 命中 `reason` 时 `filter` 返回 `true`；含 `NO_RESTRICT(64)` 时白名单判定返回 `false` |
| `processes` | object | `{}` | `完整进程名 (pkg:sub) → mode`；`follow` 条目解码时丢弃（`PolicyCodec.kt:96-106`） | 参与 `isEffective` 与 UI 展示；**hook 调用点不传入进程名，故当前对任何判定都没有影响**（§11） |
| `resources` | object | `{}` | 见 §3.4 | 仅在强制冻结前的 `applyResources` 里消费，且同一 `revision` + 同一 uid 只应用一次（`HookInstaller.kt:420-428`） |
| `thawOn` | object | `{}` | 见 §3.5 | 见 §3.5 的「消费点」列 |
| `note` | string | `""` | 任意 | 仅 UI 展示；空白串不序列化（`PolicyCodec.kt:68`） |

### 3.4 `apps[].resources`（三态）

| 字段 | 类型 | 默认值 | 取值 | 消费点（代码） |
|---|---|---|---|---|
| `net` | bool? | `null` | `true`/`false`/缺省 | `facade.setUdpRestrict(uid, value)`（`HookInstaller.kt:425`） |
| `job` | bool? | `null` | 同上 | **仅 `true` 时** 调 `facade.setJobRestrict(uid)`（`HookInstaller.kt:426`）；`false` 没有反向调用 |
| `wakelock` | bool? | `null` | 同上 | `facade.setWakeLockLimit(uid, value)`（`HookInstaller.kt:427`） |
| `alarm` | bool? | `null` | 同上 | **仅存储**，无 setter 调用（§11） |
| `broadcast` | bool? | `null` | 同上 | **仅存储**，无 setter 调用（§11） |
| `cpu` | bool? | `null` | 同上 | **仅存储**，无 setter 调用（§11） |

三态语义（`core/ResourceSwitches.kt:5-9`）：`null` = 完全放手，让 GreeZe 自己决定；模块只**增加**用户显式要求的限制，从不反向放宽系统侧已有约束。

### 3.5 `apps[].thawOn`（三态）

| 字段 | 类型 | 默认值 | 取值 | 消费点（代码） |
|---|---|---|---|---|
| `screenOn` | bool? | `null` | `true`/`false`/缺省 | `needThawWhenScreenOn(int)` hook 返回它（`HookInstaller.kt:357-372`） |
| `externalWake` | bool? | `null` | 同上 | `isAllowWakeUpList(String)` hook 返回它（`PolicyEngine.wakeUpVerdict`，`PolicyEngine.kt:101-107`） |
| `netChange` | bool? | `null` | 同上 | **仅存储**（§11） |
| `notification` | bool? | `null` | 同上 | **仅存储**（§11） |
| `audioFocus` | bool? | `null` | 同上 | **仅存储**（§11） |
| `charging` | bool? | `null` | 同上 | **仅存储**（§11） |

### 3.6 编码省略规则（决定"UI 写出来长什么样"）

`PolicyCodec.encodeApp`（`PolicyCodec.kt:59-68`）只在非默认时写字段：

| 字段 | 省略条件 |
|---|---|
| `delayMs` | `== 0` |
| `thawCooldownMs` | `== 0` |
| `keep` / `drop` | `== 0` |
| `processes` / `resources` / `thawOn` | 为空（`isEmpty` / 全 `follow`） |
| `note` | `isBlank()` |

`encodeGlobal`（`PolicyCodec.kt:154-164`）恒写六个布尔：`enabled / forceFreezeOnBackground / audit / patchLists / compactEvents / filterPolarityInverted`；`defaultDelayMs` 仅 `≠ 0`、`applyFreezeBinder` 仅 `≠ null` 时写。

手写 JSON 时带不带这些 0/空对象都等价——解码端 `oLong`/`oInt` 缺省即 `0`。

### 3.7 模式 × 字段生效矩阵

| 字段 | `follow` | `never` | `immediate` | `custom` |
|---|---|---|---|---|
| `keep` / `drop` | 忽略 | 忽略（`filterVerdict` 直接 `false`） | 生效 | 生效 |
| `delayMs` / `defaultDelayMs` | 忽略 | 忽略 | 生效 | 生效 |
| `thawCooldownMs` | 忽略 | 忽略 | 生效 | 生效 |
| `resources.net/job/wakelock` | 忽略 | 忽略（不进入强制冻结） | 生效 | 生效 |
| `thawOn.screenOn` | 忽略 | **生效**（注释见 `AppPolicy.kt:7`，代码里 `bodyScreenOn` 不看 mode，只看 `thawTrigger("screenOn", pkg)`） | 生效 | 生效 |
| `thawOn.externalWake` | 忽略 | 生效 | 生效 | 生效 |
| `processes` | 参与 `isEffective` | 参与 `isEffective` | 参与 `isEffective` | 参与 `isEffective` |

> 表中 `never` 行的 `screenOn` 一项值得单独记：`bodyScreenOn` 不判 mode，只要该包有 `thawOn.screenOn` 就按它返回；`wakeUpVerdict` 则在 `mode == FOLLOW` 时原样返回。

### 3.8 微信示例（UI 实际写出的形状）

```json
{
  "v": 2,
  "rev": 12,
  "ts": 1761692400000,
  "global": {
    "enabled": true,
    "forceFreezeOnBackground": true,
    "audit": true,
    "patchLists": false,
    "compactEvents": false,
    "filterPolarityInverted": false
  },
  "apps": {
    "com.tencent.mm": {
      "mode": "immediate",
      "keep": 16777218,
      "drop": 64,
      "processes": {
        "com.tencent.mm:push": "never",
        "com.tencent.mm:appbrand0": "immediate"
      },
      "resources": { "net": false, "job": true, "wakelock": true },
      "thawOn": { "screenOn": true, "notification": true, "externalWake": true },
      "note": "通话/相机优先"
    }
  }
}
```

逐项解释：

| 片段 | 含义 |
|---|---|
| `"keep": 16777218` | `AUDIO(2) + CAMERA(16777216)`：通话、相机相关 reason 命中时强制**不冻结** |
| `"drop": 64` | `NO_RESTRICT(64)`：不再承认 GreeZe 自己的白名单豁免 |
| `"mode": "immediate"` | 一退到后台就由模块主动下发冻结（`delayMs` 缺省 = 0） |
| `"processes"` | 已存储、已参与 `isEffective` 与 UI 展示；当前不影响判定（§11） |
| `"resources"` | `net=false` 表示显式调 `udpPackageRestrict(uid,false)`；`job/wakelock=true` 表示加限制 |
| `"thawOn"` | 亮屏 `needThawWhenScreenOn` 返回 `true`，`isAllowWakeUpList` 返回 `true`；`notification` 仅存储 |

---

## 4. `mask keep/drop` 语义

### 4.1 一句话

`keep` = **强制豁免** → `AurogonFilterManager.filter()` 返回 `false`；`drop` = **强制不豁免** → 返回 `true`。两者都不命中的位交回给系统原值。

### 4.2 `filterVerdict` 决策表（`PolicyEngine.kt:61-87`）

| # | 条件 | 返回 | 对冻结的影响 |
|---|---|---|---|
| 1 | `global.enabled == false` | `original` | 完全不干预 |
| 2 | `pkg == null` 或 `apps` 无此包 | `original` | 未配置的 App 100% 原样 |
| 3 | `mode == follow` | `original` | 同上 |
| 4 | `mode == never` | `false` | 拦下冻结（`freezeUid` hook 另有一道 `SKIP_BY_MOD`） |
| 5 | `immediate`/`custom` 且 `keep & reason != 0` | `false` | 强制豁免，**优先于 drop** |
| 6 | `immediate`/`custom` 且 `drop & reason != 0` | `true` | 丢掉豁免，允许冻结 |
| 7 | 其余 | `original` | 交回系统 |

> `keep` 先于 `drop` 判定：同一位同时出现在两个掩码里时 `keep` 赢。UI 的 `toggleKeepBit` / `toggleDropBit` 已保证互斥（`ui/vm/MainViewModel.kt:153-175`）。
>
> **极性逃生阀**：第 5/6 行的返回值是「模块覆写」。`global.filterPolarityInverted = true` 时，覆写整体取反（`PolicyEngine.kt:78-84`）：
> `override == null → original`；`filterPolarityInverted → !override`；否则 `override`。
> 也就是说 `keep` 位在反向 ROM 上会表现为「允许冻结」，`drop` 位表现为「强制豁免」，而**系统自己的 `original` 永不被改动**。诊断页「反射 filter 极性反转」开关就是给这一个假设准备的。

### 4.3 其余两个判定

| hook | 目标方法 | 判定函数 | 规则 |
|---|---|---|---|
| `no-restrict` / `temp-white` | `isNoRestrictApp` / `isTempWhite` | `whitelistVerdict`（`PolicyEngine.kt:89-98`） | `never` → `true`；`immediate`/`custom` 且 `drop` 含 `NO_RESTRICT` → `false`；其余 `original` |
| `wakeup-list` | `isAllowWakeUpList` | `wakeUpVerdict`（`PolicyEngine.kt:101-107`） | `mode == follow` → `original`；其余 → `thawOn.externalWake ?: original` |

### 4.4 `LOCKED_DROP`：解码期强制清位

`SceneBit.LOCKED_DROP = {AUDIO, CAMERA, TOP, IME, VISIBLE, VIBRATOR}`（`core/SceneBit.kt:62`），十进制和 **16783906 = 0x01001A22**。三层都会清掉这些位：

| 层 | 代码 | 行为 |
|---|---|---|
| 解码 | `PolicyCodec.stripLocked`（`PolicyCodec.kt:83-87`） | 无论存储文档写了什么，`drop` 里这些位一律清 0 |
| 写策略（模型） | `AppPolicy.withMask`（`core/AppPolicy.kt:43-44`） | `drop - SceneBit.LOCKED_DROP` |
| 写策略（UI） | `MainViewModel.updateApp`（`MainViewModel.kt:143-146`） | `maskDrop and locked.inv()` |

UI 上这些位的三态选择器带 `locked = true`（`ui/pages/PagesApps.kt:426`），即「丢豁免」这一档对它们不开放。
理由：把 `AUDIO`/`CAMERA`/`TOP`/`VISIBLE` 的豁免丢掉，等于在用户正在通话或拍照时冻结该应用。

### 4.5 32 位全表（`core/SceneBit.kt:16-49`）

| 位 | 十进制 | 十六进制 | 枚举名 | 标签 | 分组 | 备注 |
|---|---|---|---|---|---|---|
| 0 | 1 | `0x00000001` | `RESERVED` | 保留位 (0x1) | 策略 | **保留，不参与配置** |
| 1 | 2 | `0x00000002` | `AUDIO` | 音频播放 | 多媒体 | `LOCKED_DROP`、`DEFAULT_KEEP` |
| 2 | 4 | `0x00000004` | `DOWNLOAD` | 下载中 | 策略 | — |
| 3 | 8 | `0x00000008` | `GPS` | 定位中 | 定位 | — |
| 4 | 16 | `0x00000010` | `BLUETOOTH` | 蓝牙 | 连接 | `DEFAULT_KEEP` |
| 5 | 32 | `0x00000020` | `VISIBLE` | 可见窗口 | 界面 | `LOCKED_DROP` |
| 6 | 64 | `0x00000040` | `NO_RESTRICT` | 白名单 | 策略 | `DEFAULT_DROP` |
| 7 | 128 | `0x00000080` | `REMOTE_VIEW` | 远程视图 | 界面 | — |
| 8 | 256 | `0x00000100` | `NO_ICON` | 无图标 | 策略 | — |
| 9 | 512 | `0x00000200` | `TOP` | 前台 TOP | 界面 | `LOCKED_DROP` |
| 10 | 1024 | `0x00000400` | `BACKUP` | 备份中 | 策略 | — |
| 11 | 2048 | `0x00000800` | `IME` | 输入法 | 界面 | `LOCKED_DROP` |
| 12 | 4096 | `0x00001000` | `VIBRATOR` | 马达 | 多媒体 | `LOCKED_DROP` |
| 13 | 8192 | `0x00002000` | `WIDGET` | 桌面小部件 | 界面 | — |
| 14 | 16384 | `0x00004000` | `VPN` | VPN | 连接 | — |
| 15 | 32768 | `0x00008000` | `MUTI_WINDOW` | 分屏/小窗 | 界面 | 枚举名拼写就是 `MUTI_WINDOW` |
| 16 | 65536 | `0x00010000` | `FG_CONNECTION` | 前台服务连接 | 服务 | `DEFAULT_DROP` |
| 17 | 131072 | `0x00020000` | `VALID` | VALID_APP | 服务 | — |
| 18 | 262144 | `0x00040000` | `CONTROLLER` | CONTROLLER_APP | 服务 | — |
| 19 | 524288 | `0x00080000` | `FOREGROUND_SERVICE` | 前台服务 | 服务 | `DEFAULT_DROP` |
| 20 | 1048576 | `0x00100000` | `VIRTUAL_DISPLAY` | 虚拟显示 | 服务 | — |
| 21 | 2097152 | `0x00200000` | `ZYGOTE` | ZYGOTE_APP | 服务 | — |
| 22 | 4194304 | `0x00400000` | `INSTRUMENTATION` | 调试中 | 服务 | — |
| 23 | 8388608 | `0x00800000` | `BROADCAST_LAUNCH` | 广播拉起 | 服务 | — |
| 24 | 16777216 | `0x01000000` | `CAMERA` | 相机 | 多媒体 | `LOCKED_DROP`、`DEFAULT_KEEP` |
| 25 | 33554432 | `0x02000000` | `DYNAMIC_ISLAND` | 灵动岛 | 界面 | — |
| 26 | 67108864 | `0x04000000` | `WINDOW_OVERLAY` | 悬浮窗 | 界面 | — |
| 27 | 134217728 | `0x08000000` | `TTS` | TTS | 多媒体 | — |
| 28 | 268435456 | `0x10000000` | `APP_FUNCTION` | APP_FUNCTION | 服务 | — |
| 29 | 536870912 | `0x20000000` | `CARWITH_CAST` | 车机投屏 | 服务 | — |
| 30 | 1073741824 | `0x40000000` | `BIND_SYSTEMUI` | 绑定 SystemUI | 服务 | — |
| 31 | 2147483648 | `0x80000000` | — | 未使用 | — | 代码未定义；置位会被原样保存，但没有对应枚举，`describe()` 不显示 |

分组标签取自 `SceneBit.Group`（`SceneBit.kt:51-58`）：多媒体 / 界面 / 连接 / 定位 / 服务 / 策略。

### 4.6 预置集合

| 集合 | 成员 | 掩码 |
|---|---|---|
| `LOCKED_DROP` | AUDIO, VISIBLE, TOP, IME, VIBRATOR, CAMERA | `16783906` (`0x01001A22`) |
| `DEFAULT_KEEP` | AUDIO, CAMERA, BLUETOOTH | `16777234` (`0x01000012`) |
| `DEFAULT_DROP` | NO_RESTRICT, FG_CONNECTION, FOREGROUND_SERVICE | `589888` (`0x00090040`) |

`fromMask` 只按已定义位展开（`SceneBit.kt:70-71`），未定义位不会出现在 UI 列表里，但会原样回写——所以手改 JSON 塞入 `0x80000000` 不会破坏文档。

---

## 5. 命令通道 `greeze_ctl_command_v1`

### 5.1 JSON 形状

```json
{ "seq": 1761692400123, "op": "freezePkg", "pkg": "com.tencent.mm", "uid": 10123, "arg": "quick_freeze" }
```

| 字段 | 类型 | 必填 | 编码规则（`CommandCodec.encode`，`core/Command.kt:40-46`） | 解码规则（`Command.kt:48-64`） |
|---|---|---|---|---|
| `seq` | long | 建议 | 恒写 | 缺省 `0` |
| `op` | string | **是** | 恒写 | 缺失或不在 `ALL_OPS` 内 → 整条命令丢弃（`decode` 返回 `null`） |
| `pkg` | string? | 否 | 非 `null` 才写 | 缺省 `null` |
| `uid` | int | 否 | **非 0 才写** | 缺省 `0` |
| `arg` | string? | 否 | 非 `null` 才写 | 缺省 `null` |

### 5.2 `seq` 水位机制

| 环节 | 行为 | 代码 |
|---|---|---|
| UI 生成 | `seq = System.currentTimeMillis()` | `ui/data/Repos.kt:87`；`MainViewModel.send` 调用（`MainViewModel.kt:224`） |
| hook 初始水位 | `lastCommandSeq = 0` | `ModuleController.kt:59` |
| 重启后恢复 | Context 就绪时读 `heartbeat.lastSeq`，`lastCommandSeq = max(旧值, 心跳值)` | `ModuleController.kt:132-139` |
| 执行门 | `if (command.seq <= lastCommandSeq) return` | `ModuleController.kt:264` |
| 消费 | 执行前就把 `lastCommandSeq = command.seq`（失败也算已消费） | `ModuleController.kt:265` |
| 回写 | 每个命令执行后 `publishHeartbeat()`，把新水位写进心跳 | `ModuleController.kt:281` |
| 去重后遗症 | 命令键**从不删除**；同 `seq` 重复写等于空操作 | — |

**重启不回放的成立条件**：心跳键还在。若有人 `settings delete system greeze_ctl_heartbeat_v1`（或首次安装从未写过），水位回到 0，此时 Settings 里残留的任何命令（`seq` 是 epoch 毫秒，必然 > 0）会被**执行一次**。

### 5.3 10 个 op

| # | `op` | 常量 | 参数 | 实际调用 | 成功返回串 | 失败返回串 |
|---|---|---|---|---|---|---|
| 1 | `thawAll` | `OP_THAW_ALL` | 无 | `facade.thawAll(0, "hypergreeze")` → 依次试 `thawAll3` / `thawAll1` / `thawuidsAll`（`GreezeFacade.kt:201-214`） | `ok` | `fail` |
| 2 | `thawUid` | `OP_THAW_UID` | `uid` | `facade.thawUid(uid)` → `thawUid(uid, 0, "hypergreeze")` | `ok` | `fail` |
| 3 | `freezeUid` | `OP_FREEZE_UID` | `uid` | `facade.freezeUid(uid, 0, 1000, "hypergreeze", 0)` | GreeZe 自己的返回串（如 `OK`） | `fail`（反射返回 `null` 时） |
| 4 | `freezePkg` | `OP_FREEZE_PKG` | `pkg` | `pm.getPackageUid(pkg, 0)` → 同 #3 | 同 #3 | `no-uid`（查不到 uid / 无 Context）或 `fail` |
| 5 | `quickMode` | `OP_QUICK_MODE` | `arg`（缺省 `quick_freeze`）、可选 `uid` | `facade.triggerQuickFreezeMode(mode, uid, "hypergreeze")` | `ok` | `fail` |
| 6 | `resetCgroup` | `OP_RESET_CGROUP` | 无 | **与 #1 同一分支**：`facade.thawAll()`（`ModuleController.kt:287-288`） | `ok` | `fail` |
| 7 | `clearEvents` | `OP_CLEAR_EVENTS` | 无 | `telemetry.clearEvents()` → 内存环清空 + 写 `"[]"` | `ok`（无条件） | — |
| 8 | `clearSafeMode` | `OP_CLEAR_SAFE_MODE` | 无 | `guard.clearSafeMode()` + `installer.passive = false` | `ok`（无条件） | — |
| 9 | `applyLists` | `OP_APPLY_LISTS` | 无 | `patchListField("mNoRestrictAppSet", never)` + `patchListField("mGrabTicketsAppList", immediate)` | `ok(never=<N>,immediate=<M>)` | `fail`（两个字段都没打上）或 `no-policy`（引擎未就绪） |
| 10 | `setFreezeBinder` | `OP_SET_BINDER` | `arg` = `"1"`/`"0"` | `facade.setFreezeBinderEnabled(arg == "1")` → `setFreezeBinderEnableCloud(boolean)` | `ok` | `fail` |

### 5.4 失败串全集

| 串 | 产生点 | 含义 |
|---|---|---|
| `fail` | 各 op 的布尔/字符串判定 | 反射调用没落地，或 GreeZe 返回 `null`/`false` |
| `no-uid` | #4 | `pkg` 为空、无 Context、或 `getPackageUid` 抛异常（`ModuleController.kt:343-352`） |
| `no-greeze` | `execute()` 开头 | GreeZe 门面尚未建立（`facade == null`，通常意味着探测阶段就没找到类） |
| `no-policy` | #9 | `store?.engine` 为空，即策略尚未加载 |
| `unknown-op` | `execute()` 的 `else` 分支 | 防御性分支；`decode` 已把未知 op 整条丢弃，正常路径到不了这里 |
| `error: <SimpleClassName>` | `processCommand` 的 catch | 执行期异常，只记类名（`ModuleController.kt:266-272`） |

每条命令无论成败都会写一条 `info` 事件：`result = 返回串`、`detail = "cmd:<op>"`，并立即 `flushEvents()` + 刷新心跳（`ModuleController.kt:273-282`）。

### 5.5 UI 侧的快捷入口（`MainViewModel.kt:240-266`）

| UI 方法 | op | 额外参数 |
|---|---|---|
| `thawEverything()` | `thawAll` | — |
| `freezeNow(pkg)` | `freezePkg` | `pkg` |
| `thawUid(uid)` | `thawUid` | `uid` |
| `triggerQuickMode(mode)` | `quickMode` | `arg ∈ {quick_freeze, immobulus, launch_mode, grab_tickets, video}`（`ui/pages/PagesMain.kt:46`） |
| `clearEvents()` | `clearEvents` | — |
| `clearSafeMode()` | `clearSafeMode` | — |
| `applyLists()` | `applyLists` | — |
| `setFreezeBinder(b)` | `setFreezeBinder` | `arg = "1"` / `"0"` |
| `send(Command.OP_*, …)` | 任意 | 通用入口 |

---

## 6. 心跳 JSON（hook → App）

### 6.1 字段表（`TelemetryCodec.encodeHeartbeat`，`core/TelemetryCodec.kt:14-52`）

| JSON 键 | 来源字段 | 类型 | 缺省 | 含义 / 取值 |
|---|---|---|---|---|
| `ts` | `ts` | long | `0` | 写入时刻 epoch ms；UI 用它算 `heartbeatAgeMs`，`< 120000` 判为存活（`MainViewModel.kt:42`） |
| `moduleV` | `moduleVersion` | string | `""` | `BuildConfig.VERSION_NAME`，当前 `1.0.0` |
| `api` | `apiVersion` | int | `0` | libxposed API 版本，当前 102 |
| `fw` | `frameworkName` | string | `""` | LSPosed 框架名 |
| `fwV` | `frameworkVersion` | string | `""` | 框架版本 |
| `fwProps` | `frameworkProperties` | long | `0` | 框架位属性 |
| `proc` | `processName` | string | `""` | 控制器硬编码 `"system_server"`（`ModuleController.kt:241`） |
| `build` | `buildFingerprint` | string | `""` | `Build.FINGERPRINT` |
| `greeze` | `greezePresent` | bool | `false` | 进入 `start()` 且有 `GreezeRefs` 时为 `true`（硬编码 `true`，`ModuleController.kt:243`） |
| `greezeClass` | `greezeClass` | string? | 缺省 | 探测到的 manager 类名，如 `com.miui.server.greeze.GreezeManagerService` |
| `instance` | `instanceOk` | bool | `false` | `facade.hasInstance`：`sInstance` / `ServiceManager("greeze")` / `getService()` / 静态字段任一命中 |
| `enabled` | `policyEnabled` | bool | `false` | `PolicyEngine.enabled` |
| `safeMode` | `safeMode` | bool | `false` | 即 `installer.passive` |
| `safeReason` | `safeModeReason` | string? | 缺省 | 属性 `persist.sys.hypergreeze.safe_reason` 的内容 |
| `bootGuard` | `bootGuard` | int | `0` | `SafetyGuard.decision.bootGuard`（本次启动自增后的计数） |
| `revision` | `revision` | long | `0` | 当前生效策略的 `rev` |
| `lastSeq` | `lastCommandSeq` | long | `0` | 已消费的最高命令序号（§5.2） |
| `hooks` | `hooks` | array | `[]` | 见 §6.2 |
| `caps` | `capabilities` | object | `{}` | 见 §6.3 |

### 6.2 `hooks[]`（`HookStatus`，`core/State.kt:70-77`）

| 键 | 类型 | 含义 |
|---|---|---|
| `id` | string | 稳定 id，见下表 |
| `target` | string | 人类可读的方法签名 |
| `state` | string | `installed` / `missing` / `disabled` / `error` / `skipped`（常量在 `core/State.kt:62-68`；**当前实现只写 `installed`/`missing`/`error`**） |
| `hits` | long | 命中计数（`installed` 后才增长） |
| `errors` | long | 该 hook 体内的异常计数 |
| `note` | string? | 失败原因；passive 时统一覆盖成 `"safe mode: passive"`（`HookInstaller.kt:474-486`） |

诊断页固定展示的 8 个 id（`HookInstaller.ORDER`，`HookInstaller.kt:525-534`）：

| 顺序 | `id` | `target` | 作用 |
|---|---|---|---|
| 1 | `capture-init` | `GreezeManagerService.init(Context)` | 捕获单例 + Context，通知 `onGreezeReady` |
| 2 | `filter` | `AurogonFilterManager.filter(int,String,int):boolean` | 掩码决策主入口 |
| 3 | `no-restrict` | `AurogonImmobulusMode.isNoRestrictApp(String):boolean` | 白名单去留 |
| 4 | `wakeup-list` | `AurogonImmobulusMode.isAllowWakeUpList(String):boolean` | 外部唤醒 |
| 5 | `temp-white` | `GreezeManagerService.isTempWhite(String):boolean` | 临时白名单 |
| 6 | `freeze-uid` | `GreezeManagerService.freezeUid(int,long,int,String,int):String` | `never` 时直接返回 `SKIP_BY_MOD`，否则审计 |
| 7 | `foreground` | `GreezeManagerService$13.onForegroundActivitiesChanged(int,int,boolean)` | fg→bg 触发强制冻结 |
| 8 | `screen-on-thaw` | `GreezeManagerService.needThawWhenScreenOn(int):boolean` | 亮屏解冻 |

此外每个构造器还会装一个 `capture-ctor-<index>`（`HookInstaller.kt:121-134`）。它不是固定 8 个之一，只能通过心跳的 `hooks[]` 看到；`ORDER` 不含它，因此诊断页的「8/8」只统计上表 8 个的 `installed` 数（`Heartbeat.installedHooks`，`core/State.kt:105`）。

### 6.3 `caps{}`（`GreezeRefs.capabilities`，`module/greeze/GreezeProbe.kt:35-59`）

| 键 | 为 `true` 的条件 |
|---|---|
| `manager` | 恒 `true`（到这一步说明 manager 类已解析） |
| `filter` | `AurogonFilterManager` 存在 |
| `immobulus` | `AurogonImmobulusMode` 存在 |
| `listApps` | `power.ListAppsManager` 存在 |
| `freezeUid` / `freezePids` / `freezeAction` | 同名方法按 5/4/5 参数解析到 |
| `thawUid` | 3 参数 `thawUid` 解析到 |
| `thawAll` | `thawAll3` 或 `thawAll1` 之一 |
| `freezeState` | 1 参数 `getFreezeState` |
| `frozenUids` | 1 参数 `getFrozenUids` |
| `processList` | 1 参数 `getProcessByUid` |
| `packagesOfUid` | 1 参数 `getPackageNamesFromUid` |
| `tempWhite` | `isTempWhite` / `isTempWhiteList` |
| `foreground` | 找到 `$13` 这类内部类且含 3 参数回调 |
| `quickFreezeMode` | 3 参数 `triggerQuickFreezeMode` |
| `checkReason` | 1 参数 `getAurogonCheckReason` |
| `resources` | `jobRestrict` / `updateWakeLockLimit` / `udpPackageRestrict` / `isRestrictReceiver` 任一存在（注意：`isRestrictReceiver` 存在但**没有对应 hook**，见 §11） |

### 6.4 写入时机

```
start()                                   → 90 s 后 markStable() + publishHeartbeat()
ensureContext() 成功                      → publishHeartbeat()
每 6 个 tick（TICK_MS = 5_000，即约 30 s）  → publishHeartbeat()
命令执行完                                 → publishHeartbeat()
策略 revision 变化（tick 检测到）           → publishHeartbeat()
```

要点：

| 项 | 值 | 出处 |
|---|---|---|
| tick 周期 | `TICK_MS = 5_000` | `ModuleController.kt:370` |
| 心跳节流 | 每 `HEARTBEAT_EVERY_TICKS = 6` 个 tick 一次，即约 30 s | `ModuleController.kt:371`、`tick()` 里的 `tickCount % HEARTBEAT_EVERY_TICKS == 0` |
| 策略重读 | **每个** tick 都调 `PolicyStore.reload()`（5 s 兜底） | `ModuleController.tick()` |
| 事件/日志 flush | 每个 tick 都投递，但各自有 dirty 判断与 15 s 节流 | `ModuleTelemetry.kt:92-99, 112-117` |

即：**策略/命令最快 5 s 内被看到**（观察者触发时是即时），**心跳本身约 30 s 一次**；命令路径与 revision 变化都会立即刷新心跳。

---

## 7. 事件 JSON（hook → App）

### 7.1 字段表（`TelemetryCodec.encodeEvents`，`TelemetryCodec.kt:107-122`）

| JSON 键 | 来源字段 | 类型 | 编码规则 |
|---|---|---|---|
| `ts` | `ts` | long | 恒写（epoch ms） |
| `t` | `type` | string | 恒写；未知值解码时回落 `info`（`TelemetryCodec.kt:130`） |
| `pkg` | `pkg` | string? | 非 `null` 才写 |
| `uid` | `uid` | int | 恒写（`0` = 无归属） |
| `r` | `reason` | int | **非 0 才写** |
| `res` | `result` | string? | 非 `null` 才写（通常是 GreeZe 的返回串或命令返回串） |
| `d` | `detail` | string? | 非 `null` 才写 |

### 7.2 `EventType` 常量、发射点与 UI 颜色（`core/State.kt:28-36`）

| 常量值 | 含义 | 谁在写 | UI 颜色令牌 | 浅色 / 深色 |
|---|---|---|---|---|
| `freeze` | 冻结成功（审计或强制） | `bodyFreezeUid`（结果为 `OK`）、`forcedFreeze` | `frozenFully` | `#3482FF` / `#4788FF` |
| `thaw` | 解冻 | **当前无发射点** | `unfrozen` | `#00B884` / `#00BA7C` |
| `skip` | 主动放弃冻结 | `bodyFreezeUid`（`SKIP_BY_MOD`）、`bodyFilter`（verdict 为 `false`）、`forcedFreeze`（`already-frozen` / `cooldown`） | `warning` | `#FF6A05` / `#F97A1F` |
| `block` | 拦截 | **当前无发射点** | `warning` | `#FF6A05` / `#F97A1F` |
| `error` | 异常 | **当前无发射点**（异常只进 `hits/errors` 计数与日志） | `danger` | `#E94634` / `#F12522` |
| `info` | 一般信息 | `bodyFilter`（verdict 为 `true`）、`bodyWhitelist`、`bodyFreezeUid`（非 `OK` 结果）、命令执行结果、策略 reload | `textHint` | `#66000000` / `#66FFFFFF` |
| `boot` | 启动 | **当前无发射点** | `primary` | `#3482FF` / `#4788FF` |

颜色映射代码：`ui/pages/Common.kt:82-90`；色值：`ui/theme/Theme.kt:70-103`（浅色）/ `108-141`（深色）。

### 7.3 事件筛选（事件页）

| 筛选项 | 命中类型 | 代码 |
|---|---|---|
| 0 全部 | 全部 | `ui/pages/PagesMain.kt:308-311` |
| 1 冻结 | `freeze` | 同上 |
| 2 跳过/拦截 | `skip` + `block` | 同上 |
| 3 错误 | `error` | 同上 |

### 7.4 容量与落盘

| 项 | 值 | 出处 |
|---|---|---|
| 环形容量 | 60 条 | `Module.EVENT_RING`（`core/Keys.kt:58`） |
| 溢出策略 | 丢最旧 | `ModuleTelemetry.kt:63-69` |
| 启动恢复 | 读旧文档并 `takeLast(60)` | `ModuleTelemetry.kt:39-49` |
| 落盘时机 | 脏标记 + 显式 flush；`forcedFreeze` 与命令路径主动 flush | `ModuleTelemetry.kt:92-99` |
| 展示顺序 | UI `asReversed()`，最新在上 | `ui/data/Repos.kt:63` |

---

## 8. 日志键 `greeze_ctl_logs_v1`

| 项 | 值 |
|---|---|
| 环形容量 | **120 行**（`Module.LOG_RING`，`core/Keys.kt:57`） |
| 实现 | `Logx` 内 `ArrayDeque<String>(120)`，`synchronized` 保护，溢出丢最旧并累加内部 `dropped` 计数（`module/Logx.kt:13, 26-43`） |
| 行格式 | `<级别字母> <消息>`；带异常时追加 ` \| <异常类名>: <异常消息>`。级别字母 `V/D/I/W/E`（`Logx.kt:49-55`） |
| 镜像 | 同时写 Xposed 日志，tag = `HyperGreeze`（`Module.TAG`，`core/Keys.kt:46`），因此 `adb logcat -s HyperGreeze` 能看 live 日志 |
| 落盘 | `flushLogs()`，15 s 节流（`ModuleTelemetry.kt:112-117, 158`） |
| 异常安全 | `Logx` 内部 `catch` 掉 sink 异常（`Logx.kt:38-42`）——日志失败不影响 `system_server` |

---

## 9. 手改示例（adb）

### 9.1 三条基本命令

```bash
# 写入整份策略（JSON 用单引号包住，内部不要再有单引号）
adb shell settings put system greeze_ctl_policy_v1 '{"v":2,"apps":{"com.tencent.mm":{"mode":"never"}}}'

# 读心跳（hook 是否活着、钩子是否装全、水位到哪）
adb shell settings get system greeze_ctl_heartbeat_v1

# 删除策略 → 模块回到纯跟随（逃生第一条）
adb shell settings delete system greeze_ctl_policy_v1
```

写入被系统挡下时（部分机型对 shell 也校验 appop）：

```bash
adb shell appops set dev.hypergreeze android:write_settings allow
```

### 9.2 最小可用 JSON：只把一个包设成 `never`

```bash
adb shell settings put system greeze_ctl_policy_v1 \
  '{"v":2,"apps":{"com.tencent.mm":{"mode":"never"}}}'
```

解码结果：`global` 取默认值（`enabled=true`、`forceFreezeOnBackground=true`、`audit=true`），`apps` 只有微信一条，`rev`/`ts` 为 0（手写没带）。

### 9.3 最小 JSON：设成 `immediate`

```bash
adb shell settings put system greeze_ctl_policy_v1 \
  '{"v":2,"apps":{"com.tencent.mm":{"mode":"immediate","delayMs":0}}}'
```

`delayMs` 写 0 与省略等价；要 500 ms 就写 `"delayMs":500`。

### 9.4 设备属性（守卫与安全模式）

```bash
adb shell setprop persist.sys.hypergreeze.guard 3      # 守卫计数：下次启动 next=4 → 被动
adb shell getprop persist.sys.hypergreeze.guard
adb shell setprop persist.sys.hypergreeze.safe 1       # 手动安全模式闩锁
adb shell setprop persist.sys.hypergreeze.safe 0       # 解闩锁（还要把 guard 归零）
adb shell setprop persist.sys.hypergreeze.guard 0
adb shell getprop persist.sys.hypergreeze.safe_reason  # 最近一次被动原因的文本
```

### 9.5 三个键一起看的一次性取证

```bash
for k in greeze_ctl_policy_v1 greeze_ctl_command_v1 greeze_ctl_heartbeat_v1 greeze_ctl_events_v1; do
  echo "== $k"; adb shell settings get system "$k"
done
```

> 手写 JSON 里避免空格更安全（某些 shell 层会改写参数）。要缩进可先写到本机文件再 `adb shell 'settings put system … "$(cat /data/local/tmp/p.json)"'`。

---

## 10. 版本兼容与容错

| 机制 | 行为 | 代码 |
|---|---|---|
| `v` 字段 | 解码时缺省填 `2`；**不做版本比较**，任何 int 都接受；编码回写解码得到的值 | `PolicyCodec.kt:35`、`Policy.kt:8` |
| 未知键 | `org.json` 的 `opt*` 取值方式天然忽略未定义键，不报错 | `core/Jsonx.kt:8-22` |
| 缺失键 | 各字段回落默认值，不是整份失败 | `PolicyCodec.kt:163-175` |
| 旧文档没有 `filterPolarityInverted` | 解码为 `false`，行为与加该字段之前完全一致（不反转） | `PolicyCodec.kt:170` |
| 字段类型错（如 `"keep":"abc"`） | `optInt` 回落 `0`，静默降级为该字段默认值 | `Jsonx.kt:17-18` |
| 单条 app 不是对象 | 该条被 `continue` 跳过，其他条目照常 | `PolicyCodec.kt:52` |
| 单条 app 无效（`isEffective=false`） | 解码时从 map 里剔除 | `PolicyCodec.kt:54` |
| **整份 JSON 语法错误** | `JSONObject(raw)` 抛异常 → 捕获 → **返回 `Policy.EMPTY`** | `PolicyCodec.kt:41-43` |
| 半应用 | 不存在：`decode()` 返回一个新 `Policy` 实例，`PolicyStore` 整体替换（`PolicyStore.kt:73-77`） | 同上 |
| `null` / 空串 | 直接 `Policy.EMPTY` | `PolicyCodec.kt:31` |
| 心跳/事件/日志解码失败 | 分别返回 `null` / `emptyList()` / `emptyList()`，UI 显示「无数据」而非崩溃 | `TelemetryCodec.kt:100-102, 138-140, 156-157` |
| 命令解码失败 | 返回 `null`，命令被忽略且水位**不前进**（下次仍会重试同一条）；未知 op 同样整条丢弃 | `Command.kt:48-64`、`ModuleController.kt:262-265` |

`Policy.EMPTY` 的具体含义：`version = 2`、`revision = 0`、`updatedAt = 0`、`global = GlobalPolicy()`（`enabled=true` 等默认值）、`apps = {}`（`core/Policy.kt:36`）。也就是「所有 App 跟随系统，模块不可见」。

---

## 11. 已知缺口（代码事实，不是设计意图）

以下各项在 schema 里存在、在 UI 里可见，但**当前没有任何 hook 路径消费它们**。写进文档是为了让验收时不去验证不存在的行为。

| # | 项 | 事实 | 出处 |
|---|---|---|---|
| 1 | `apps[].processes` | 只参与 `isEffective` 与 UI 展示；所有 hook 调用点都**不传入进程名**（`engine.neverFreeze(pkg)`、`engine.forceFreezeOnBackground(pkg)`、`filterVerdict(pkg, reason, original)`），所以进程级覆盖不影响任何判定 | `HookInstaller.kt:294, 379, 392`、`PolicyEngine.kt:25-28` |
| 2 | `global.patchLists` | 无读取方；`applyLists` 命令不看它，只要有 never/immediate 就打字段 | `ModuleController.kt:332-341` |
| 3 | `global.compactEvents` | 无读取方 | `core/GlobalPolicy.kt:24` |
| 4 | `global.applyFreezeBinder` | 无读取方；改云开关只走 `setFreezeBinder` 命令 | `GlobalPolicy.kt:22`、`ModuleController.kt:321-324` |
| 5 | `resources.alarm/broadcast/cpu` | 无 setter 调用（只有 `net` / `job` / `wakelock` 三项落地） | `HookInstaller.kt:420-428` |
| 6 | `thawOn.netChange/notification/audioFocus/charging` | 无 hook 消费 | `HookInstaller.kt` 全文 |
| 7 | `resetCgroup` op | 实现等同 `thawAll`，没有调 `resetCgroupUidStatus` / `resetCgroupUidFreezeStatus` | `ModuleController.kt:287-288` |
| 8 | 键 `greeze_ctl_boot_guard_v1` | 只在 `Keys.ALL` 里出现，无读写方；守卫真值在 `persist.sys.hypergreeze.guard` | `core/Keys.kt:24, 38-40` |
| 9 | 键 `greeze_ctl_safe_mode_v1` | App 会写 `"1"`，hook 侧没有读取方；生效路径是属性 `persist.sys.hypergreeze.safe` | `Repos.kt:69-72`、`SafetyGuard.kt:40` |
| 10 | 广播 `ACTION_STATE_CHANGED` | 无发送、无注册 | `core/Keys.kt:36` |
| 11 | `HookState.DISABLED` / `SKIPPED` | 常量存在，无写入点 | `core/State.kt:62-68` |
| 12 | `EventType.THAW/BLOCK/ERROR/BOOT` | 常量存在，无发射点 | `HookInstaller.kt` 全文 |
| 13 | `isRestrictReceiver` 相关能力 | `GreezeProbe` 会探测 `isRestrictReceiver` / `isRestrictBackgroundAction`，但两者都没有 hook（注释说明：5 个参数认不出唯一包名，猜错会全局影响后台广播） | `GreezeProbe.kt:147-148`、`HookInstaller.kt:415-419` |
| 14 | 多用户 | 两侧都用不带 user 参数的 `getString`，即固定 user 0 语义；工作资料 / 分身共享策略需要改 `getStringForUser` | `PolicyStore.kt:84-89`、`ModuleTelemetry.kt:121-126` |
| 15 | 冻结态展示 | hook 侧不发布 per-uid cgroup 状态（每次轮询会多一次 Binder），UI 只在 `never` 时敢标「未冻结」，其余一律「未知」 | `MainViewModel.kt:277-287` |

---

## 附：本文字段索引（便于反查代码）

| 概念 | 定义位置 |
|---|---|
| 键名 | `core/Keys.kt:10-41` |
| 模块常量（环容量、守卫上限、稳定窗口、原因码、tag） | `core/Keys.kt:43-65` |
| 策略模型 | `core/Policy.kt`、`core/GlobalPolicy.kt`、`core/AppPolicy.kt` |
| 三态开关 | `core/ResourceSwitches.kt` |
| 模式枚举 | `core/FreezeMode.kt` |
| 位定义 | `core/SceneBit.kt` |
| 策略编解码 | `core/PolicyCodec.kt` |
| 命令 | `core/Command.kt` |
| 遥测编解码 | `core/TelemetryCodec.kt` |
| 状态与枚举常量 | `core/State.kt` |
| 纯判定层 | `module/PolicyEngine.kt` |
| 命令执行 / 心跳 | `module/ModuleController.kt` |
| hook 安装与 body | `module/HookInstaller.kt` |
| 反射门面 | `module/greeze/GreezeFacade.kt`、`module/greeze/GreezeProbe.kt` |
| 守卫与安全模式 | `module/SafetyGuard.kt`、`module/SysProp.kt` |
| 日志环 | `module/Logx.kt` |
| UI 侧读写 | `ui/data/Repos.kt`、`ui/vm/MainViewModel.kt` |
