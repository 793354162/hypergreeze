# 性能（PERFORMANCE）

一句话：**这份文档只写「代码里能数出来的东西」——每次调用分配几个对象、拿不拿锁、有没有反射/Binder；没有上机采样的一律标「未测量」。**
边界：挂在 `system_server` 里的东西必须轻，但「轻」的判据是分配与锁，不是感觉。做不到的部分见 [LIMITS.md](LIMITS.md)，行为语义见 [ARCHITECTURE.md](ARCHITECTURE.md)。

> 依据：`module/HookInstaller.kt`（hook body 与计数器）、`module/PolicyStore.kt`（策略缓存）、`module/PolicyEngine.kt`（纯判定）、`module/ModuleTelemetry.kt`（落盘节流）、`module/ModuleController.kt`（tick 与心跳）、`ui/vm/MainViewModel.kt`（轮询）、`ui/data/Repos.kt`（图标缓存）、`module/greeze/GreezeFacade.kt`（反射封装）。
> 「改动前」的表述来自 [ROADMAP.md](ROADMAP.md) §P2 的问题表与 [CHANGELOG.md](../CHANGELOG.md) 1.0.1 的记录；「改动后」全部由当前代码给出。

---

## 1. 热路径预算表

### 1.1 四个高频落点

| 路径（入口） | 我们代码 new 的对象 | 装箱 | 锁 | 反射 / Binder | 依据 |
|---|---|---|---|---|---|
| **`filter`**（`HookInstaller.bodyFilter`） | 判定与原值**相同**时：**0 个**（`Boolean.valueOf` 复用 JVM 的两个缓存实例）<br>判定不同时：1 个 `EventRecord` | 无额外装箱（`R.asInt` 是拆箱；`Boolean` 走缓存） | 无（`ConcurrentHashMap.get` 无锁读；`AtomicLong.incrementAndGet` 是 CAS） | 仅当 `chain.getArg(1)` 不是 String 时才 `packageFor(uid)` → `packagesFor`；命中缓存则**没有**反射 | `HookInstaller.kt:267-294`、`519-523`、`R.kt:145-151` |
| **`freezeUid`**（`bodyFreezeUid`） | 放行路径：`audit=true`（默认）时 1 个 `EventRecord` + 2 次 `packagesFor` 各 1 个迭代器<br>拦截路径：额外 1 次字符串拼接（`Logx.i`）+ 1 次 `flushEvents()` | 每次 `packagesFor(uid)` 1 个 `Integer`（uid > 127，不在 Integer 缓存里） | `ModuleTelemetry.record` 的 `synchronized(lock)`（只在记录时） | 缓存 miss 才反射 `getPackageNamesFromUid` | `HookInstaller.kt:337-385`、`525-534`；`ModuleTelemetry.kt:115-121` |
| **`freezeUids`**（`bodyFreezeUids`） | 需要过滤时：`kept` / `blocked` 两个 `ArrayList` + `kept.toIntArray()` + `chain.args.toTypedArray()` + 每 uid 1 个迭代器<br>不需要过滤时也仍会先建 `kept` | 每 uid 1 个 `Integer`（`packagesFor` 的键 + `it !in kept` 的 `contains`） | 无 | 每个 uid 的首次 `packagesFor` | `HookInstaller.kt:396-421` |
| **`foreground`**（`bodyForeground`） | 前台方向：**0 个**<br>后台方向：命中 `immediate` 时 1 个 `Runnable` + `pendingFreeze` 的集合节点 + 1 个 `firstOrNull` 迭代器 | `pendingFreeze.add(uid)` 的键装箱；返回值 Boolean 被丢弃 | `ConcurrentHashMap.newKeySet()` 的内部桶锁（只在 `add`） | `resolveUid` 未定时才会 2 次 `packagesFor`（首次会打 Binder + 反射） | `HookInstaller.kt:423-441`、`462-473`、`538-558` |

结论（按代码，不按感觉）：

- `filter` 与 `foreground` 的**主方向**已经是「零自建对象、零自取锁」。
- `freezeUid` 的固定成本是**每次 1～2 个 `Integer` + 1 个迭代器**；`audit=true` 时再加一条事件。`audit` 可以关（首页「记录事件」），但关了就看不清 GreeZe 的真实返回串。
- `freezeUids` 是这几条里最"肥"的：一次批量冻结至少 2 个 `ArrayList` + 2 个数组。

### 1.2 另外三个落点（不在上表，但同样是 hook）

| 路径 | 我们代码 new 的对象 | 备注 |
|---|---|---|
| `screen-on-thaw`（`bodyScreenOn`） | 每个 uid 1 个迭代器 + 1 个 `Integer`；`firstNotNullOfOrNull` 命中即停 | 亮屏一次跑一批，不是每帧 |
| `freeze-process`（`bodyFreezeProcess`） | **每次** `R.allFields` 新建 `ArrayList<Field>` 并遍历整条父类链；每个字段名再 `lowercase()` 一次（1 个 String） | 这条路径的固定成本远高于 `filter`，因为 `GreezeFacade.rowOf` 是"按字段名猜语义"（`GreezeFacade.kt:274-290`、`R.kt:105-118`） |
| `capture-ctor` / `capture-init` | 只在 GreeZe 初始化时跑几次 | 一次性 |

### 1.3 锁的清单（谁在什么时候拿锁）

| 锁 | 谁拿 | 频率 |
|---|---|---|
| `ConcurrentHashMap` 桶锁 | `counters` / `uidPackages` / `resourceAppliedRev` / `lastAttempt` / `pendingFreeze` 的**写入** | 只在 miss / 首次 |
| `ModuleTelemetry.lock`（`synchronized`） | `record` / `journalAdd` / `snapshot` / `undoableCount` | 每条事件、每条账 |
| `Logx.ring`（`synchronized`） | 每次 `Logx.i/w/e` | 热路径上刻意不打日志 |
| `JournalRing.lock`（`synchronized`） | `add` / `snapshot` / `count` | 只在 Effector 记录时（`filter` 路径不经过） |

**没有任何 hook body 自己 `synchronized`**。这是硬约束：hook 跑在 GreeZe 的 Binder 线程和 AMS 回调线程上。

---

## 2. 本轮做了什么

| 项 | 改动前 | 改动后（机制） | 依据 |
|---|---|---|---|
| **`PolicyEngine` 缓存** | `HookInstaller.engineProvider()` 走 `PolicyStore.engine`，每次 hook 调用都构造一个 `PolicyEngine` | `PolicyStore` 持 `@Volatile cachedEngine`；`reload()` 里只有 `parsed != cached` 才重建，getter 只读字段。无策略时统一用 `ModuleController.emptyEngine` 这个单例 | `PolicyStore.kt:31、46、76-92`；`ModuleController.kt:45、96` |
| **`hit()` 快路径** | 每次调用都走 `ConcurrentHashMap.computeIfAbsent(id) { Counter() }`，也就是每次一个 lambda 对象 | 先 `counters[id]` 取一次；命中时右侧不求值 → **lambda 不分配** | `HookInstaller.kt:519-523` |
| **`filterVerdict` 单次查表** | 先 `appPolicy(pkg)` 判 mode、再 `appPolicy(pkg)` 取掩码，一次判定两次 map 查找 | 局部 `val app = appPolicy(pkg) ?: return original` 之后全部复用 | `PolicyEngine.kt:67-71` |
| **UI 轮询原始串比较** | 每 2.5 s 解 60 条事件 + 120 行日志（+ 现在还有账本） | 先 `readRaw` 拿原始串，与上次相同就复用上次的解码结果；**只有变了才解析** | `MainViewModel.kt:111-147` |
| **`IconCache` 200 / 72px** | 96 项 / 96px，长列表回滚会重新走 `getApplicationIcon` | `LruCache<String, ImageBitmap>(200)`，`toBitmap(width=72, height=72)` | `Repos.kt:129-143` |
| **事件/日志/账本落盘节流** | 每条事件一次 `Settings.System` 写（每次都是一次 `SettingsProvider` Binder 往返） | 5 s tick 批量 flush；`flushEvents` / `flushJournal` 都有 `dirty` 守卫（不脏直接 return），`flushLogs` 另有 15 s 节流 | `ModuleController.kt:248-271`；`ModuleTelemetry.kt:74-81、144-151、164-169` |

「改动前」的三条（engine 每次 new、`hit()` 的 lambda、`filterVerdict` 两次查表）就是 ROADMAP §P2 问题表里点名的三条；这里只补上了「改动后长什么样」，没有编造倍数。

---

## 3. 还剩下的开销（诚实清单）

| 项 | 现状（代码写死的） | 什么时候疼 | 现在能按的开关 / 已有行为 | 观测方式 |
|---|---|---|---|---|
| **`uidPackages` 缓存上限 1024** | `if (uidPackages.size > 1024) uidPackages.clear()`，整表清空 | 装了上千个 uid（多用户/分身/系统应用多）时可能反复清空，之后每个 uid 首次都要重新反射 | 无开关；`invalidateUidCache()` 只在策略真正变化时被调 | 没有日志；只能看 `hits` 与体感，**未测量** |
| **`packagesFor` 首次反射** | `facade.packagesOfUid(uid)` → 反射调 `getPackageNamesFromUid`，返回 `Array` 后再 `filterIsInstance<String>().toTypedArray()`（再分配 2 个数组 + 1 个 List） | 每个 (uid) 只疼一次；缓存被清空时集体疼 | 同上 | `logcat -s HyperGreeze` 没有对应日志；**未测量** |
| **Settings 写入频率** | 心跳：每 6 个 tick ≈ **30 s**（且每条命令、每次 revision 变化都强制发一次）；事件：**5 s**（dirty 才写）；日志：**15 s**；账本：5 s（dirty 才写） | 每次写 = 1 次 `SettingsProvider` Binder 往返 + provider 侧落库 | `compactEvents=true` 把事件环从 60 压到 20，减小每次写的字节数 | `adb shell settings get system greeze_ctl_*` 的 `ts` 间隔 |
| **NEVER 拦截路径的写入放大** | `bodyFreezeUid` 拦截时**直接** `telemetry?.flushEvents()`，不受 5 s 节流约束 | GreeZe 高频重试同一个被 never 的 App 时，每次拦截 = 1 次 Settings 写 | 无开关（这是为了"立刻看到为什么没冻"刻意做的） | 数事件里 `SKIP_BY_MOD` 的条数 vs 时间 |
| **`snapshot()` / 心跳每 30 s 分配** | `publishHeartbeat()` 每次构造 `Heartbeat` + `hooks.snapshot()`（`statuses.map{}` → 1 个 `ArrayList` + 每项 `copy()`）+ `refs.capabilities()`（每次新建 18 项的 `linkedMapOf`）+ 一次 JSON 编码 + `journalUndoable()` 遍历最多 200 行 | 命令多、revision 频繁变化时会被额外触发 | 无 | 心跳的 `ts` 与 `revision` 变化次数 |
| **UI 侧仍有 4 次 Binder 读 / 2.5 s** | `refreshTelemetry()` 每次读心跳 + events + logs + journal 四个键 | 只在页面可见时（`onStart`/`onStop` 控制）；页面不可见时整个轮询被取消 | 关掉页面即停 | 见 §4 |
| **UI 侧解码仍在主线程** | `pollJob = viewModelScope.launch` 默认主线程；原始串变化时在那里解码 | 事件/日志/账本恰好同时变化时，一次解码在主线程 | 已把"每次都解"降成"变了才解" | 只能加 trace；**未测量** |
| **`JournalRing.add` 的 O(n) 位移** | 容量 200，超了 `items.removeAt(0)`，每次新账一次数组位移 | 频繁资源改动时（每行最多 200 次位移） | 无 | 一次还原前后的 `journal` 长度 |

---

## 4. 测量方法

### 4.1 一次性取证

```bash
# 内存：system_server 是模块真正住的地方
adb shell dumpsys meminfo system | head -20

# GreeZe 侧状态（服务名是 greeze，见 GreezeFacade.SERVICE_NAME）
adb shell dumpsys greeze | head -60
# 注：dumpsys greezed 不是本模块探测的服务名；若你的 ROM 真注册成 greezed，先用
#     adb shell service list | grep -i greez 确认，别把"没有输出"当成没冻结

# 钩子命中数与错误数（这就是性能的"计数器"）
adb shell settings get system greeze_ctl_heartbeat_v1 \
  | jq -r '.hooks[] | "\(.id)\t\(.state)\thits=\(.hits)\terr=\(.errors)"'

# 带时间戳的模块日志（看 forced freeze / command 的间隔）
adb logcat -v time -s HyperGreeze | tail -40
```

### 4.2 100 次前台/后台压测

沿用 [TEST_PLAN.md](TEST_PLAN.md) **阶段 7「反抖动（连续 fg/bg 100 次）」**，别另造一套：

```bash
# 7.1 前置：设 5 s 冷却，让第 2 次以后的切换走 cooldown 分支
adb shell settings put system greeze_ctl_policy_v1 \
  '{"v":2,"apps":{"<PKG>":{"mode":"immediate","thawCooldownMs":5000}}}'

# 基线
adb shell dumpsys meminfo system > mem-before.txt
adb shell settings get system greeze_ctl_heartbeat_v1 > hb-before.json
adb shell pidof system_server > pid-before.txt

# 7.2 100 次循环
for i in $(seq 1 100); do
  adb shell am start -n <PKG>/<LAUNCHER_ACTIVITY> >/dev/null 2>&1
  adb shell input keyevent KEYCODE_HOME >/dev/null 2>&1
done

# 收尾
adb shell dumpsys meminfo system > mem-after.txt
adb shell settings get system greeze_ctl_heartbeat_v1 > hb-after.json
adb shell pidof system_server > pid-after.txt
adb logcat -b crash -d | tail -20
```

判断只看四件事：`pid` 没变、`hits` 涨了、`freeze` 事件远少于 100（其余是 `res="cooldown"`）、`meminfo` 的 `Java Heap` / `Native Heap` 没有单向爬升。TEST_PLAN 7.3 已给出对应的 `jq` 断言。

### 4.3 判据表

| 想验证 | 判据 | 现状 |
|---|---|---|
| `filter` 没有变重 | 100 次切换前后 `hits` 增量 ≈ 切换次数；`errors` 不涨 | 方法就绪，**未测量** |
| 无 GC 抖动 | `meminfo system` 的 `Java Heap` 前后差在噪声内（需同机对照） | **未测量** |
| 事件不刷屏 | `jq 'length'` ≤ 60；`freeze` 数量 ≪ 100 | TEST_PLAN 7.3 可直接跑 |
| 冷却生效 | 出现 `skip … -> cooldown` | TEST_PLAN 7.4 |
| 账本不因自动冻结膨胀 | 100 次切换后 `journal` 长度不变（自动冻结不记账） | 代码保证，见 REVERSIBILITY §1.1 |

---

## 5. 与 ROADMAP P2 验收的对应

| ROADMAP P2 验收项（`ROADMAP.md:89-91`） | 现状 | 差在哪 |
|---|---|---|
| hook 热路径（`filter` / `freezeUid`）零对象分配（除必要的装箱） | **部分满足**：`filter` 在同值路径上是 0；`freezeUid` 固定有 1～2 个 `Integer` + 1 个迭代器，`audit=true` 时每条成功冻结 1 个 `EventRecord` | 迭代器与 `Integer` 来自"用 Java 集合按 Int 键查表"这件事本身；要真做到零分配得换 primitive map，本轮没做 |
| 100 次 fg/bg 后 `hits` 增长但无 GC 抖动（`dumpsys meminfo` 对照） | **方法就绪，未测量** | 见 §4.2；数字必须上机取 |
| UI 轮询期间主线程无 JSON 解析（可加 trace 验证） | **部分满足**：原始串不变时确实零解析；变了仍在主线程解（`viewModelScope.launch` 默认 Main） | 严格按字面不算过；要过就得把解码挪到 `Dispatchers.IO` 再回写 state |
