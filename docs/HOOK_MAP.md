# Hook 落点地图

> 目标读者：要验证「到底挂了什么、什么时候生效、失效长什么样」的人。
> 全部结论来自本仓库代码 + 两份逆向文档：`../../Greeze反射契约.md`（下称**契约**）、`../../LSPosed-Greeze逐App冻结控制模块可行性分析.md`（下称**报告**）。
> `文件:行` 的文件名省略 `app/src/main/java/dev/hypergreeze/` 前缀；`§` 指上述两份文档的章节。**没有在真机上跑过**，凡是静态分析推不出来的都写「未确证」。

Hook id 是稳定字符串常量，定义在 `module/HookInstaller.kt:515-523`，原样出现在诊断页、`greeze_ctl_heartbeat_v1` 与 hook 日志里。

---

## 0. 总览

| hook id | 目标类.方法 | 精确描述符 | `proceed()` 位置 | 改写返回值 | 依赖能力 |
|---|---|---|---|---|---|
| `capture-ctor-N` | `GreezeManagerService.<init>`（每个已声明构造器一个） | 未固定（逐个构造器） | **先** proceed | 否 | `declaredConstructors` 可读 |
| `capture-init` | `com.miui.server.greeze.GreezeManagerService.init` | `(Landroid/content/Context;)V` | **先** proceed | 否（void） | manager 上名为 `init`、元数 1 的方法 |
| `filter` | `com.miui.server.greeze.AurogonFilterManager.filter` | `(ILjava/lang/String;I)Z` | **先** proceed | 是（boolean） | `filterClass`，缺失时回退 manager 上的 `filter` |
| `no-restrict` | `com.miui.server.greeze.AurogonImmobulusMode.isNoRestrictApp` | `(Ljava/lang/String;)Z` | 先 proceed | 是（boolean） | `immobulusClass` + 方法 |
| `wakeup-list` | `com.miui.server.greeze.AurogonImmobulusMode.isAllowWakeUpList` | `(Ljava/lang/String;)Z` | 先 proceed | 是（boolean） | `immobulusClass` + 方法 |
| `temp-white` | `com.miui.server.greeze.GreezeManagerService.isTempWhite` | `(Ljava/lang/String;)Z` | 先 proceed | 是（boolean） | manager 上的方法 |
| `freeze-uid` | `com.miui.server.greeze.GreezeManagerService.freezeUid` | `(IJILjava/lang/String;I)Ljava/lang/String;` | **命中「永不冻结」时根本不 proceed** | 是（返回 `"SKIP_BY_MOD"`） | manager 上的方法 |
| `foreground` | `com.miui.server.greeze.GreezeManagerService$13.onForegroundActivitiesChanged` | `(IIZ)V` | 先 proceed | 否（void） | `declaredClasses` 里能扫到 3 参同名方法（契约 §A.8） |
| `screen-on-thaw` | `com.miui.server.greeze.GreezeManagerService.needThawWhenScreenOn` | `(I)Z` | 先 proceed | 是（boolean） | manager 上的方法；**契约 A 表未收录，很可能 MISSING** |

> 描述符来源：契约 §B（`filter`）、§A.2（`freezeUid`）、§A.4（`isTempWhite`）、§A.8（`$13.onForegroundActivitiesChanged`）、§C（`isNoRestrictApp` / `isAllowWakeUpList`）。`needThawWhenScreenOn(int)` 只出现在报告 §2.8，契约里没有对应 flags 行 → 存在性未确证。

### 0.1 通用装配约定

| 约定 | 代码 | 说明 |
|---|---|---|
| 逐个安装、失败只记状态 | `HookInstaller.kt:84-113` | 方法解析不到 → `HookState.MISSING`（90-94），不抛；`module.hook` 抛异常 → `HookState.ERROR` + note（107-112） |
| 优先级 | `HookInstaller.kt:98` | `setPriority(PRIORITY_DEFAULT)`，不与其他模块抢序 |
| 异常模式 | `HookInstaller.kt:99` | `ExceptionMode.PROTECTIVE`；按 libxposed API 102 的 javadoc（`.probe/src/io/github/libxposed/api/XposedInterface.java`）：`proceed()` **之前**抛异常 → 框架「当作没这个 hook」继续；`proceed()` **之后**抛异常 → 用 proceed 的结果作为返回；`proceed()` 自己抛的异常照常传播。模块在所有 body 里已经自己 try/catch，这层是第二道保险 |
| id | `HookInstaller.kt:100` | `.setId(id)`，用于诊断与热重载定位 |
| 被动模式 | `HookInstaller.kt:49`、`ModuleController.kt:94` | `passive=true` 时 hook 照样装（诊断页能解释「为什么我是被动」），但每个 body 都在改判之前就返回原始结果（`original` 或 `raw`） |

安装顺序（`HookInstaller.install()`，`HookInstaller.kt:74-82`）：

| 序号 | 函数 | 装上的 id |
|---|---|---|
| 1 | `installInstanceCapture()`（115-138） | `capture-ctor-0…N`、`capture-init` |
| 2 | `installFilter()`（140-146） | `filter` |
| 3 | `installWhitelists()`（148-166） | `no-restrict`、`wakeup-list`、`temp-white` |
| 4 | `installFreezeUid()`（168-174） | `freeze-uid` |
| 5 | `installForeground()`（176-182） | `foreground` |
| 6 | `installScreenOnThaw()`（184-190） | `screen-on-thaw` |

`ORDER`（`HookInstaller.kt:525-534`）是 8 个固定 id 的清单，**不含构造器 hook**；构造器 hook 的 id 由 `"$ID_CAPTURE_CTOR-$index"` 拼出（121-122），`ID_CAPTURE_CTOR` 本身不是完整 id。因此一次启动实际安装的 hook 数是 `8 + 构造器个数`。

---

## 1. `capture-ctor-N` —— 构造器捕获

| 项 | 内容 |
|---|---|
| 目标类 | `com.miui.server.greeze.GreezeManagerService`（候选表 `GreezeProbe.kt:77-81`） |
| 目标 | 该类**每一个**已声明构造器；反射 `declaredConstructors`（`HookInstaller.kt:116-120`） |
| 描述符 | 不固定（是 `<init>`，不是普通方法） |
| id | `capture-ctor-0`、`capture-ctor-1`……（`HookInstaller.kt:122`） |

**拦截语义**

1. `val result = chain.proceed()`（`HookInstaller.kt:195`）——先让构造器正常跑完。
2. `facade.captureInstance(chain.thisObject, "constructor-hook")`（197）把**被构造的实例**记进门面；`captureInstance` 只在对象不同时写入并记录来源（`greeze/GreezeFacade.kt:43-49`）。
3. 原样返回 `result`（201）。

存在的理由：契约 §H-4 指出 `getInstance()` 是「为空就 new」的 DCL，早期调用会造僵尸实例；构造器是能最早拿到实例的时机（早于 `init(Context)`，更早于 `Lifecycle.onStart` 里的 `publishBinderService`），且完全不需要触发那个 DCL。

**失败模式与兜底**

| 情况 | 行为 |
|---|---|
| `declaredConstructors` 抛异常 | 当成空数组，不装任何构造器 hook，无 MISSING 状态（`HookInstaller.kt:116-120` 的 `catch`） |
| 单个构造器 hook 抛异常 | 该 id 记 `HookState.ERROR` + note，其它构造器继续（130-133） |
| body 内 `captureInstance` 抛异常 | `Logx.w` 后返回 `result`，构造照常完成（198-200） |

**GreeZe 能力缺失（MISSING）时**：构造器 hook 没有 MISSING 状态——解析不到构造器就什么都不装。实例来源仍有三级兜底：`sInstance` 静态字段 → `ServiceManager.getService("greeze")` → `getService()` → 任意同类型静态字段（`GreezeFacade.kt:72-128`）。

**验证**

```bash
adb shell settings get system greeze_ctl_heartbeat_v1 | grep -o '"id":"capture-ctor-0"[^}]*'
```

> `hits` 恒为 0：`bodyConstructor` 不调用 `hit()`（对比 `HookInstaller.kt:432-435`）。看的是 `"state":"installed"` 而不是命中数。

---

## 2. `capture-init` —— `init(Context)` 捕获

| 项 | 内容 |
|---|---|
| 目标类 | `com.miui.server.greeze.GreezeManagerService` |
| 方法 | `init(android.content.Context)` → `void`，包内可见（契约 §A.8；§H-10 要求 `setAccessible`） |
| 描述符 | `(Landroid/content/Context;)V` |
| 解析 | `refs.m("init")`，spec 为 `MethodSpec("init", arrayOf("init"), 1)`（`GreezeProbe.kt:160`） |

**拦截语义**

1. `chain.proceed()`（`HookInstaller.kt:205`）——GreeZe 自己先完成 `init`。
2. `captureInstance(chain.thisObject, "init-hook")`（207）。
3. `chain.args` 非空 → `captureContext(chain.getArg(0))`（208）；`captureContext` 只在参数确实是 `Context` 时记录（`GreezeFacade.kt:51-53`）。
4. 打一行日志：`GreeZe init() observed; instance=… context=…`（209）。
5. `onGreezeReady?.invoke()`（210）；它由 `ModuleController` 绑成 `main.post { ensureContext() }`（`ModuleController.kt:95`），等于提前一次拿 `Context` 的尝试。
6. 原样返回 `result`（214）。

**失败模式与兜底**：整段（含日志与回调）在一个 `try/catch` 里，异常只 `Logx.w`（211-213），`init` 照常完成、无返回值改写。`Context` 拿不到不影响 hook 生效，只影响策略/遥测通道；`ensureContext` 另有 20×1.5s 重试（`ModuleController.kt:112-123`、`351-355`）。

**MISSING 行为**：`init` 解析不到 → 状态 `missing`，`onGreezeReady` 永远不触发。此时实例仍可能来自构造器 hook 或 `sInstance` 字段；`Context` 仍会由 `ModuleController.start()` 里的 `main.post { ensureContext() }`（`ModuleController.kt:102`）主动尝试，所以「策略完全不生效」不是必然结果。

**验证**

```bash
adb shell logcat -s HyperGreeze | grep "GreeZe init() observed"
```

（`Logx` 同时写 Xposed 日志与内存环，`module/Logx.kt`；tag 为 `Module.TAG = "HyperGreeze"`，`core/Keys.kt:46`。）

---

## 3. `filter` —— 32 位场景掩码改写

| 项 | 内容 |
|---|---|
| 目标类 | `com.miui.server.greeze.AurogonFilterManager`；**解析不到该类时回退**到 `GreezeManagerService` 上同名的 3 参方法（`HookInstaller.kt:141-142`） |
| 方法 | `filter(int uid, String pkg, int flags)` → `boolean`（契约 §B） |
| 描述符 | `(ILjava/lang/String;I)Z` |
| 状态标签 | 固定为 `AurogonFilterManager.filter(int,String,int):boolean`（143），**不反映实际解析到的类**；真实类名看 `GreezeProbe` 的 notes 日志（`GreezeProbe.kt:238-243`） |

**拦截语义（先 proceed，再改写）**

| 步 | 代码 | 动作 |
|---|---|---|
| 1 | `HookInstaller.kt:218` | `raw = chain.proceed()` —— GreeZe 先按自己的规则算出 `original` |
| 2 | 219 | `raw` 不是 `Boolean` → 原样返回 |
| 3 | 221-223 | 计命中；`passive` 或全局总开关关闭 → 返回 `original` |
| 4 | 224-225 | `uid = asInt(arg0)`；`pkg = arg1 as? String`，取不到就用 `packageFor(uid)`（uid→包名缓存），再取不到就直接返回 `original` |
| 5 | 226 | `reason = asInt(arg2)` |
| 6 | 227 | `verdict = engine.filterVerdict(pkg, reason, original)` |
| 7 | 228-237 | 只有 `verdict != original` 才写一条事件：`verdict=true` 记 `INFO`，`false` 记 `SKIP`，`detail="filter override"` |
| 8 | 238 | 返回 `verdict` |

`filterVerdict` 的判定表（`module/PolicyEngine.kt:61-79`）：

| 应用策略 | `filter()` 返回值 |
|---|---|
| 总开关关 / `pkg` 为空 / 该包无策略 / `FOLLOW` | `original`（完全不动） |
| `NEVER` | **`false`**（永不允许冻结） |
| `IMMEDIATE` / `CUSTOM`，`reason & maskKeep != 0` | `false`（强制豁免） |
| `IMMEDIATE` / `CUSTOM`，`reason & maskDrop != 0` | **`true`**（丢掉 GreeZe 自己的豁免） |
| `IMMEDIATE` / `CUSTOM`，两位都不命中 | `original` |

`true` = 「允许冻结」这一极性来自报告 §2.2，**未上机验证**（见 LIMITS §3）。

**失败模式与兜底**

| 情况 | 行为 |
|---|---|
| body 内任意异常 | `hit(id, error=true)` + `Logx.w("filter body failed")` + 返回 `original`（239-243） |
| `arg2` 不是 int | `asInt` 返回 0（`module/R.kt:145-151`）→ 位与全部为 0 → 等于 `original` |
| `getPackageNamesFromUid` 不可用 | `packageFor(uid)` 为空 → 直接 `original`（静默，无事件） |
| 策略文档损坏 | `PolicyCodec.decode` 返回 `Policy.EMPTY`（`core/PolicyCodec.kt:41-43`）→ 全部 `original` |

**MISSING 行为**：`filter` 解析不到 → 状态 `missing`，`filterClass` 也可能为 null（能力位 `filter=false`，`GreezeProbe.kt:37`）。后果：掩码（keep/drop）与「NEVER 时直接在判定层拒绝」这条路径失效；但 `freeze-uid` 的拦截仍独立存在（见 §7），所以「永不冻结」不会因为 filter 缺失而完全失效。

**场景位表**（`core/SceneBit.kt:16-49`；名称与位值交叉核对契约 §E）

| 位 | 十进制 | 十六进制 | `SceneBit` | 契约 §E 名称 | 标记 |
|---|---|---|---|---|---|
| 0 | 1 | `0x1` | `RESERVED` | **无名字**（契约 §H-2：`convertReasonToString` 无此分支） | 可配，但 GreeZe 侧语义未知 |
| 1 | 2 | `0x2` | `AUDIO` | `AUDIO_APP` | `LOCKED_DROP`、`DEFAULT_KEEP` |
| 2 | 4 | `0x4` | `DOWNLOAD` | `DOWNLOAD_APP` | |
| 3 | 8 | `0x8` | `GPS` | `GPS_APP` | |
| 4 | 16 | `0x10` | `BLUETOOTH` | `BLUETOOTH_APP` | `DEFAULT_KEEP` |
| 5 | 32 | `0x20` | `VISIBLE` | `VISIBLE_APP` | `LOCKED_DROP` |
| 6 | 64 | `0x40` | `NO_RESTRICT` | `NO_RESTRICT_APP` | `DEFAULT_DROP` |
| 7 | 128 | `0x80` | `REMOTE_VIEW` | `ROMOTE_VIEW_APP`（ROM 拼写） | |
| 8 | 256 | `0x100` | `NO_ICON` | `NO_ICON_APP` | |
| 9 | 512 | `0x200` | `TOP` | `TOP_APP` | `LOCKED_DROP` |
| 10 | 1024 | `0x400` | `BACKUP` | `BACKUP_APP` | |
| 11 | 2048 | `0x800` | `IME` | `IME_APP` | `LOCKED_DROP` |
| 12 | 4096 | `0x1000` | `VIBRATOR` | `VIBRATOR_APP` | `LOCKED_DROP` |
| 13 | 8192 | `0x2000` | `WIDGET` | `WIDGET_APP` | |
| 14 | 16384 | `0x4000` | `VPN` | `VPN_APP` | |
| 15 | 32768 | `0x8000` | `MUTI_WINDOW` | `MUTIWINDOW_APP`（ROM 拼写） | |
| 16 | 65536 | `0x10000` | `FG_CONNECTION` | `FG_CONNECTION_APP` | `DEFAULT_DROP` |
| 17 | 131072 | `0x20000` | `VALID` | `VALID_APP` | |
| 18 | 262144 | `0x40000` | `CONTROLLER` | `CONTROLLER_APP` | |
| 19 | 524288 | `0x80000` | `FOREGROUND_SERVICE` | `FOREGROUND_SERVICE_APP` | `DEFAULT_DROP` |
| 20 | 1048576 | `0x100000` | `VIRTUAL_DISPLAY` | `VIRTUAL_DISPLAY_APP` | |
| 21 | 2097152 | `0x200000` | `ZYGOTE` | `ZYGOTE_APP` | |
| 22 | 4194304 | `0x400000` | `INSTRUMENTATION` | `INSTRUMENTATION_APP` | |
| 23 | 8388608 | `0x800000` | `BROADCAST_LAUNCH` | `BROADCAST_LAUNCH_APP` | |
| 24 | 16777216 | `0x1000000` | `CAMERA` | `CAMERA_APP` | `LOCKED_DROP`、`DEFAULT_KEEP` |
| 25 | 33554432 | `0x2000000` | `DYNAMIC_ISLAND` | `DYNAMIC_ISLAND` | |
| 26 | 67108864 | `0x4000000` | `WINDOW_OVERLAY` | `WINDOW_OVERLAY` | |
| 27 | 134217728 | `0x8000000` | `TTS` | `TTS_APP` | |
| 28 | 268435456 | `0x10000000` | `APP_FUNCTION` | `APP_FUNCTION` | |
| 29 | 536870912 | `0x20000000` | `CARWITH_CAST` | 字面量 `"CARWITH_CAST_APP"` | |
| 30 | 1073741824 | `0x40000000` | `BIND_SYSTEMUI` | `BIND_SYSTEMUI_APP` | |

`LOCKED_DROP = {AUDIO, CAMERA, TOP, IME, VISIBLE, VIBRATOR}`（`SceneBit.kt:62`），在三处被强制清位，手改 JSON 也绕不过：

| 位置 | 作用 |
|---|---|
| `core/AppPolicy.kt:44` `withMask()` | 写策略时先做 `drop - LOCKED_DROP` |
| `core/PolicyCodec.kt:83-87` `stripLocked()` | 解码时再清一次（对 `adb shell settings put` 手改的文档同样生效） |
| `ui/vm/MainViewModel.kt:144-145` `updateApp()` | UI 保存前清位 |

**验证**

```bash
adb shell settings get system greeze_ctl_events_v1 | grep -o '"d":"filter override"' | wc -l
```

（事件里的 `pkg` / `r`(reason) / `res`(verdict) 由 `HookInstaller.kt:229-237` 写入；编解码见 `core/TelemetryCodec.kt:107-141`。）

---

## 4. `no-restrict` —— `isNoRestrictApp(String)`

| 项 | 内容 |
|---|---|
| 目标类 | `com.miui.server.greeze.AurogonImmobulusMode`（候选 `GreezeProbe.kt:85`） |
| 方法 | `isNoRestrictApp(String) → boolean`（契约 §C） |
| 描述符 | `(Ljava/lang/String;)Z` |

**拦截语义（先 proceed，再改写）**：`raw = proceed()`（`HookInstaller.kt:247`）→ 非 `Boolean` 直接返回（248）→ `passive`/总开关关闭返回 `original`（252）→ 取 `arg0` 作为包名，取不到返回 `original`（253）→ `verdict = engine.whitelistVerdict(pkg, original)`（254）→ 变了才记事件（`detail` = hook id，255-262）→ 返回 `verdict`。

`whitelistVerdict`（`PolicyEngine.kt:82-91`）：

| 应用策略 | 返回值 |
|---|---|
| 无策略 / `FOLLOW` | `original` |
| `NEVER` | **`true`**（把该包塞进「不受限」名单） |
| `IMMEDIATE` / `CUSTOM` 且 `maskDrop` 含 `NO_RESTRICT(64)` | `false`（不再享受白名单） |
| `IMMEDIATE` / `CUSTOM` 其它情况 | `original` |

> 只有 `NO_RESTRICT` 这一位参与判断（`dropsBit(pkg, SceneBit.NO_RESTRICT)`，88 行），其它位对本 hook 无影响。

**失败模式与兜底**：异常 → `hit(error)` + `Logx.w("<id> body failed")` + `original`（264-268）。`arg0` 不是 `String` → 返回 `original`。

**MISSING 行为**：`immobulusClass` 为 null 或方法找不到 → 状态 `missing`，能力位 `immobulus=false`（`GreezeProbe.kt:38`）。后果：`NEVER` 少了一条「强制进白名单」的路径，只剩 `filter`（§3）与 `freeze-uid`（§7）两条。

**验证**

```bash
adb shell settings get system greeze_ctl_events_v1 | grep -o '"d":"no-restrict"'
```

---

## 5. `wakeup-list` —— `isAllowWakeUpList(String)`

| 项 | 内容 |
|---|---|
| 目标类 | `com.miui.server.greeze.AurogonImmobulusMode` |
| 方法 | `isAllowWakeUpList(String) → boolean`（契约 §C；probe 接受 `isAllowWakeupList` 别名，`GreezeProbe.kt:140`） |
| 描述符 | `(Ljava/lang/String;)Z` |

**拦截语义（先 proceed，再改写）**：与 §4 同构（`HookInstaller.kt:271-285`），但判定函数换成 `wakeUpVerdict`（`PolicyEngine.kt:94-99`）：

| 应用策略 | 返回值 |
|---|---|
| 无策略 / `FOLLOW` | `original` |
| 其它任何模式（含 `NEVER`） | `app.thawOn.externalWake ?: original` |

即：这是「允许被外部唤醒」的开关。`externalWake = false` 时返回 `false`（不许被唤醒），`true` 时返回 `true`，未设置则跟随系统。**注意这条不区分 `NEVER`**——代码只排除 `FOLLOW`（97 行）。

**失败模式与兜底**：异常 → `hit(error)` + `Logx.w("wakeup body failed")` + `original`（280-284）。本 hook **不写事件**（没有 `telemetry.record` 调用），只能从命中数看。

**MISSING 行为**：状态 `missing`，能力位 `immobulus=false`。后果：`thawOn.externalWake` 这个开关在 UI 上仍然可配，但没有任何消费点（策略存了不生效）。

**验证**

```bash
adb shell settings get system greeze_ctl_heartbeat_v1 | grep -o '"id":"wakeup-list"[^}]*'
```

---

## 6. `temp-white` —— `isTempWhite(String)`

| 项 | 内容 |
|---|---|
| 目标类 | `com.miui.server.greeze.GreezeManagerService` |
| 方法 | `isTempWhite(String) → boolean`（契约 §A.4；probe 接受 `isTempWhiteList` 别名，`GreezeProbe.kt:138`） |
| 描述符 | `(Ljava/lang/String;)Z` |

**拦截语义**：与 §4 完全共用 `bodyWhitelist`（`HookInstaller.kt:161-165` 装配 → `246-269` 实现），判定表同 `whitelistVerdict`（`NEVER` → `true`；`IMMEDIATE`/`CUSTOM` 且 drop 了 `NO_RESTRICT` → `false`；否则 `original`）。先 `proceed()`，再改写。

**失败模式与兜底**：同 §4（`original` + 错误计数）。`isTempWhite` 语义是「临时白名单」，名单本身由 GreeZe 维护（`TEST_TEMP_WHITE` 集合，契约 §A.7），模块只覆盖返回值，**不写那个集合**。

**MISSING 行为**：状态 `missing`，能力位 `tempWhite=false`（`GreezeProbe.kt:49`）。后果：少一条「NEVER 强制白名单」路径。

**验证**

```bash
adb shell settings get system greeze_ctl_events_v1 | grep -o '"d":"temp-white"'
```

---

## 7. `freeze-uid` —— 唯一「先判定，再决定要不要 proceed」

| 项 | 内容 |
|---|---|
| 目标类 | `com.miui.server.greeze.GreezeManagerService` |
| 方法 | `freezeUid(int uid, long delay, int reason, String tag, int abnormalType) → String`（契约 §A.2：返回失败原因串，`null`/空 = 成功语义） |
| 描述符 | `(IJILjava/lang/String;I)Ljava/lang/String;` |

**拦截语义（与其它 hook 相反：先判定）**

| 步 | 代码 | 动作 |
|---|---|---|
| 1 | `HookInstaller.kt:290` | 计命中 |
| 2 | 292-295 | 非被动且总开关开：`uid = asInt(arg0)`；`blockedFor = packagesFor(uid).firstOrNull { engine.neverFreeze(it) }` |
| 3 | 296-299 | 预检查自身异常 → `hit(error)` + `Logx.w("freezeUid pre-check failed")`，`blockedFor` 保持 null（**继续走第 5 步**） |
| 4 | 300-316 | `blockedFor != null` → **不调用 `chain.proceed()`**，写 `SKIP` 事件（`result = "SKIP_BY_MOD"`，`detail = "never-freeze policy"`），`flushEvents()`，直接 `return "SKIP_BY_MOD"` |
| 5 | 317 | 其它情况：`result = chain.proceed()` |
| 6 | 318-333 | `global.audit` 为真时记事件：`result == "OK"` 记 `FREEZE`，否则 `INFO`；`detail = "freezeUid"` |
| 7 | 334 | 返回 `result` |

第 4 步是整个模块唯一「跳过 proceed」的地方，代码注释写明原因（313-315）：**跳过 `proceed()` 才是真正阻止 cgroup 写入的动作**，返回串只是让 GreeZe 的状态机认为「这次没冻」。

`neverFreeze`（`PolicyEngine.kt:30-31`）= `enabled && mode(pkg, processName) == NEVER`；`NEVER` 的判定只按包名逐个试（`packagesFor(uid)` 是 `getPackageNamesFromUid(uid)` 的结果，共享 uid / 双开多包名时**任一个** `NEVER` 就会拦截）。

**失败模式与兜底**

| 情况 | 行为 |
|---|---|
| 预检查异常 | 不拦截，照常 `proceed()`（保守方向：宁可不拦，也不误伤） |
| 审计段异常 | 只 `Logx.w("freezeUid audit failed")`，`result` 不受影响（331-333） |
| `getPackageNamesFromUid` 不可用 / 返回空 | `packagesFor` 为空 → `blockedFor` 恒为 null → `NEVER` 静默失效（这条**只**靠 filter/no-restrict/temp-white 兜） |
| 模块自己发起的冻结 | 同一方法，同样会被本 hook 检查（`forcedFreeze` 只对 `IMMEDIATE` 发起，所以不会被自己拦） |

**MISSING 行为**：状态 `missing`，能力位 `freezeUid=false`（`GreezeProbe.kt:40`）。这是**最致命的一条缺失**：所有「永不冻结」的硬拦截消失（`filter` 只能劝 GreeZe 不要发起，不能阻止已经发起的 `freezeUid` 调用）；`Command.OP_FREEZE_UID` / `OP_FREEZE_PKG` 也失去 `SKIP_BY_MOD` 回执（`ModuleController.kt:275-285`）。

**验证**

```bash
adb shell logcat -s HyperGreeze | grep "blocking freezeUid"
adb shell cat /sys/fs/cgroup/uid_<UID>/cgroup.freeze   # 期望 0（未被冻）
```

---

## 8. `foreground` —— 退后台触发器

| 项 | 内容 |
|---|---|
| 目标类 | `com.miui.server.greeze.GreezeManagerService$13`（匿名 `IProcessObserver`，契约 §A.8；**不在** `GreezeManagerService` 上，§H-8） |
| 方法 | `onForegroundActivitiesChanged(int, int, boolean) → void` |
| 描述符 | `(IIZ)V` |
| 解析 | `findForegroundCallback()`：先看 manager 自身，再遍历 `declaredClasses` 找 3 参同名方法（`GreezeProbe.kt:258-271`、`213-221`） |

**拦截语义（先 proceed，只做触发，不改返回值）**

1. `raw = chain.proceed()`（`HookInstaller.kt:338`）——GreeZe 自己的前台状态机先跑。
2. 计命中；`passive` 直接返回（340-341）。
3. `a0 = asInt(arg0)`、`a1 = asInt(arg1)`、`foreground = asBool(arg2, def = true)`（342-344）。默认值 `true` 意味着「第三个参数类型不符时按前台处理」，也就是**不触发**冻结。
4. `foreground == false` → `uid = resolveUid(a0, a1)`，`pid` 取另一个（346-348）→ `onBackground(uid, pid)`。
5. 始终返回 `raw`（354）。

`onBackground`（376-387）：总开关检查 → `packagesFor(uid).firstOrNull { engine.forceFreezeOnBackground(it) }`（只有 `IMMEDIATE` 模式且 `global.forceFreezeOnBackground` 为真才命中，`PolicyEngine.kt:34-37`）→ `pendingFreeze.add(uid)` 去重（同一 uid 已有排队的任务就不再排）→ `work.postDelayed(task, delayMs)` 投到 `HandlerThread("HyperGreeze")`。

`forcedFreeze`（389-413）依次：`passive`/总开关/模式复查 → `facade.isUidFrozen(uid) == true` 记 `already-frozen` 并退出（393-396）→ 冷却窗口（按 `lastAttempt` 而不是 `getLastThawedTime`，397-404；ARCHITECTURE §4 说明后者单位未确证）→ `applyResources(uid, pkg)`（405）→ `facade.freezeUid(uid, 0L, REASON_MOD=1000, FREEZE_TAG="hypergreeze", 0)`（406）→ 记事件 `detail = "forced pid=<pid>"`（407）。

**失败模式与兜底**：body 异常 → `hit(error)` + `Logx.w("foreground body failed")` + 返回 `raw`（350-353）。`forcedFreeze` 整体 `try/catch`（410-412），任务本身跑在 worker 线程，异常不会回到 AMS。`delayMs` 由模块自己的 `postDelayed` 实现，传给 GreeZe 的 `delay` 恒为 `0L`（406）——**GreeZe 的 delay 参数没被使用**。

**MISSING 行为**：状态 `missing`，能力位 `foreground=false`（`GreezeProbe.kt:50`）。后果：`IMMEDIATE` 模式的「退后台立即冻结」完全失效（没有触发器），`NEVER`/掩码仍生效；`delayMs`、`thawCooldownMs`、`forceFreezeOnBackground` 一并变成空配置。

**uid/pid 参数顺序未确证**：见 §11。

**验证**

```bash
adb shell logcat -s HyperGreeze | grep "foreground callback: uid is"
adb shell settings get system greeze_ctl_events_v1 | grep -o '"d":"forced pid=[0-9]*"'
```

---

## 9. `screen-on-thaw` —— 亮屏解冻判定

| 项 | 内容 |
|---|---|
| 目标类 | `com.miui.server.greeze.GreezeManagerService` |
| 方法 | `needThawWhenScreenOn(int uid) → boolean` |
| 描述符 | `(I)Z` |
| 出处 | 只出现在报告 §2.8（「亮屏解冻：`needThawWhenScreenOn(uid)`」）；**契约 §A 的方法表里没有它** → 存在性未确证 |

**拦截语义（先 proceed，再改写）**

1. `raw = chain.proceed()`（`HookInstaller.kt:358`）；非 `Boolean` 直接返回（359）。
2. 计命中；`passive`/总开关关闭返回 `original`（361-363）。
3. `uid = asInt(arg0)`；`packagesFor(uid).firstNotNullOfOrNull { engine.thawTrigger("screenOn", it) } ?: original`（364-367）。
4. 返回该值。

即：只覆盖「亮屏时该不该解冻」这一**判定**，**不主动调用 `thawUid`**——真正解冻仍由 GreeZe 执行。`thawTrigger` 是逐包三态（`PolicyEngine.kt:114-125`），`firstNotNullOfOrNull` 取第一个非 null 值：多包名共享 uid 时，**先命中的那个包的设置说了算**。

**失败模式与兜底**：异常 → `hit(error)` + **静默**返回 `original`（368-371，注意这里没有 `Logx.w`，是全部 body 里唯一不写日志的 catch）。

**MISSING 行为**：状态 `missing`。没有独立能力位（`GreezeProbe.capabilities()` 不含该键，35-59），只能从 `hooks[].state` 看。后果：`thawOn.screenOn` 配置无效，亮屏行为回到 GreeZe 原生。

**验证**

```bash
adb shell settings get system greeze_ctl_heartbeat_v1 | grep -o '"id":"screen-on-thaw"[^}]*'
```

---

## 10. 为什么没有 hook `isRestrictReceiver` / `udpPackageRestrict` / `jobRestrict`

这三者在模块里是**动作**，不是**判定点**：`HookInstaller.applyResources(uid, pkg)`（`HookInstaller.kt:420-428`）在「即将强制冻结某个 uid」那一刻调用它们一次，然后才 `freezeUid`。挂 hook 需要「每次调用都要不要改写结果」的语义，而它们没有可改写的东西。

| 成员 | 契约里的形态 | 模块里的用法 | 不 hook 的原因 |
|---|---|---|---|
| `udpPackageRestrict(int, boolean)` | **private**、`void`（契约 §A.5 [:366]；§H-10 需 `setAccessible`） | `applyResources` → `facade.setUdpRestrict(uid, it)`（`HookInstaller.kt:425`、`GreezeFacade.kt:312`） | 纯 setter，无返回值可改写。它是 per-uid 网络限制的**写入口**，GreeZe 内部自己也调它；hook 它只能旁观系统写状态，反而会和 GreeZe 的网络状态机抢写。模块只在需要「加限制」时调一次，从不解限（`core/ResourceSwitches.kt` 的 KDoc） |
| `jobRestrict(int)` | public、**返回 `void`**（契约 §A.5 [:480]，注意不是 boolean） | `applyResources` → `facade.setJobRestrict(uid)`（426、`GreezeFacade.kt:307`） | 返回 void → 没有任何「改写返回值」的余地；`callUnit` 的语义只是「invoke 没抛异常」（`GreezeFacade.kt:163-175`），无法判断是否真的生效 |
| `isRestrictReceiver(Intent, int, String, int, String)` | 包内可见、返回 `boolean`（契约 §A.5 [:470]） | **既没 hook 也没调用**；只在 probe 里记一条 spec（`GreezeProbe.kt:147`），并计入能力位 `resources`（53-58） | 5 个参数里有两个 `int`、两个 `String`，无法唯一确定哪个是包名、哪个是 uid / userId；猜错会影响**全局**广播，而不是单个 App。`HookInstaller.kt:415-419` 的注释就是这个决定 |

补充两点：

1. 判定层是存在开关位的：`PolicyEngine.resource()` 对 `net`/`alarm`/`job`/`wakelock`/`broadcast`/`cpu` 六项都返回三态（`PolicyEngine.kt:101-112`），但 `applyResources` 只消费 `net`/`job`/`wakelock` 三项（`HookInstaller.kt:425-427`）。`alarm`/`broadcast`/`cpu` 只有 UI 开关，没有落点（见 LIMITS §2）。
2. `applyResources` 有按 revision 的去重：`resourceAppliedRev[uid] == engine.policy.revision` 时直接返回（422-424），所以同一版本策略下每个 uid 只写一次资源。

---

## 11. `onForegroundActivitiesChanged` 的 uid/pid 顺序：未确证，用经验法

**已知**：契约 §A.8 只给出描述符 `(IIZ)V`，没给参数语义；§H-8 只说方法在 `$13` 上。AOSP 的 `IProcessObserver.onForegroundActivitiesChanged(int pid, int uid, boolean foregroundActivities)` 是 `(pid, uid, fg)`，但 GreeZe 的匿名实现**没有义务**保持这个顺序（`HookInstaller.kt:59-63` 的注释）。

**代码的实际做法**：`resolveUid(a0, a1)`（`HookInstaller.kt:450-470`）

| 顺序 | 条件 | 判定 | 是否写缓存 |
|---|---|---|---|
| 1 | `uidArgIndex == 0` | uid = `a0` | — |
| 2 | `uidArgIndex == 1` | uid = `a1` | — |
| 3 | 只有 `a0` 能解析出包名 | uid = `a0` | `uidArgIndex = 0` |
| 4 | 只有 `a1` 能解析出包名 | uid = `a1` | `uidArgIndex = 1` |
| 5 | 都能 / 都不能 | uid = `a1`（AOSP 顺序 `(pid, uid, fg)` 的回退） | 不写缓存 |

「能解析出包名」= `packagesFor(x)` 非空，即 `getPackageNamesFromUid(x)` 返回了至少一个包（`GreezeProbe.kt:133`、`GreezeFacade.kt:259-266`）。`uidArgIndex` 是 `@Volatile`，一旦落定在整个进程生命周期内不再变（65 行），日志里留一行 `foreground callback: uid is arg0/arg1`（459、464）。

**这个经验法的风险**

| 风险 | 说明 |
|---|---|
| 顺序其实是 `(uid, pid, fg)` 但两边都解析不出包名 | 会走第 5 条，把 `pid` 当 uid 传给 `freezeUid(pid, …)` → 冻结目标错误或直接失败。真正的缓解是「只有 `IMMEDIATE` 策略的包才会走到写操作」（`onBackground` 379 行先按包名过策略），未配置的 App 不受影响 |
| pid 恰好等于某个能解析出包名的 uid | 第 3/4 条会误判并**永久缓存**错误结论，直到进程重启（`invalidateUidCache()` 只清包名缓存，`HookInstaller.kt:510-512`） |
| `getPackageNamesFromUid` 缺失（能力位 `packagesOfUid=false`） | 两边都空 → 永远走 AOSP 回退；对 `(pid, uid, fg)` 的 ROM 正确，对反过来的是错的 |
| 判定结果没进心跳 | `uidArgDecision()`（504-508）存在，但 `Heartbeat` 里没有对应字段（`ModuleController.publishHeartbeat()` 212-238 只放 hooks 与 capabilities）→ **诊断页看不到**，只能抓 logcat |

正确做法（未做）：在真机上对一次已知前台切换抓一条 `logcat` + 对比 `dumpsys activity` 的 pid/uid，把结论写死；目前代码刻意保留运行时自适应。
