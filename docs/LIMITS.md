# 能力边界、未做实项与未确证清单

> **1.2.0 通道变更（真机实测驱动）**：部分 HyperOS 机型上 `Settings.System.putString` 对三方 App 返回 `false`，即使 `Settings.System.canWrite()` 为真 —— 也就是说「按文档应该能写」的通道在这些机器上是死的。所以 app → hook 的下行改为 **provider 优先**：App 把策略写进自己的 SharedPreferences，通过只读 `ContentProvider`（`content://dev.hypergreeze.policy/policy`）暴露，`system_server` 直接 `query`。`Settings.System` 退化为镜像与迁移回退；hook → App 的上行（心跳/事件/日志/账本）仍走 Settings，因为 `system_server` 侧不受该限制。诊断页「策略通道」显示 hook 侧实际来源（provider / settings / none）。

> 目标读者：准备把这个模块装到真机上、或者准备接手改它的人。
> 三份材料的分工：**契约** = `../../Greeze反射契约.md`（字节码签名/字段/位值），**报告** = `../../LSPosed-Greeze逐App冻结控制模块可行性分析.md`（粒度阶梯与硬上限），**代码** = 本仓库 `app/src/main/java/dev/hypergreeze/`。
> `文件:行` 的文件名省略 `app/src/main/java/dev/hypergreeze/` 前缀。除特别标注外，全部是静态分析 + 代码阅读结论，**未在真机验证**。
> **行号基准：commit `84c9f82`。** 仓库迭代很快，行号可能漂移；函数名是稳定锚点。

---

## 0. 三张清单不是一回事

| 清单 | 含义 | 处理方式 |
|---|---|---|
| §1 确实做不到 | GreeZe / cgroup v2 / Android 推送模型本身的边界；换实现语言、换 hook 点都没用 | 不投入，只在 UI 上如实呈现 |
| §2 本版本没做 | 代码里明确缺的落点或明确关掉的开关 | 逐条写「为什么」；其中两条**与立项假设/README 不符**（事件环清空、安全模式按钮），按代码实际写 |
| §3 未在真机确证 | 断言成立与否会改变行为，但只有静态证据 | 真机灰度时优先验证；验证方法列在表里 |
| §4 版本漂移 | OTA 改了签名/类名/字段名时的降级路径 | 设计上已覆盖，列出可观测后果 |
| §5 安全阀 | 启动守卫、安全模式、总开关、逃生命令 | 出问题时按 §5.3 执行 |

---

## 1. 确实做不到

总依据：报告 §2.9「硬上限（做不到的部分，必须诚实标注）」。下面每条给出**机制级原因** + **现行实现为什么绕不过**。

| 想做 | 机制级原因 | 现行实现为什么绕不过 |
|---|---|---|
| 只冻结某个线程，其它线程照跑 | cgroup v2 freezer 的冻结单位是 `uid_<uid>` cgroup；per-task 接口只在 v1 布局里存在（契约 §G.4 列出的 `/sys/fs/cgroup/freezer/perf/frozen/tasks` 是 v1 形态），v2 只有 `cgroup.procs`/`cgroup.threads`，而 GreeZe 的 per-uid 冻结落在 `uid_<uid>`（报告 §2.5、§2.9） | 模块从不直接写 cgroup（报告 §3.5 把「直写式」列为不做），只经由 GreeZe 的方法。`freezeThread(int)` 在 probe 表里有 spec（`GreezeProbe.kt:106`），但**全仓库没有任何调用点**；即使调了，v2 设备上 GreeZe 的 `FreezeUtils` 也写不进 tasks 节点 |
| 只冻结某个 binder 事务 | `freezeBinder(int,int,boolean,int)` 是 **uid+pid 级开关**（契约 §A.2 [:297]，private），事务级「代答」由 `GreezeBinderProxyImpl` 固定实现（报告 §2.9） | 模块只暴露全局开关：`setFreezeBinderEnabled(boolean)` → `setFreezeBinderEnableCloud`（`GreezeFacade.kt:319-320`）。没有 per-transaction 入口，也没有 per-transaction 的观测点 |
| 冻结后仍然实时收推送 | 进程被 freezer 停住，FCM / MiPush 的 handler 根本没有机会执行——这是物理互斥，不是配置问题（报告 §2.9） | 模块能做的只有「事后解冻」：改写亮屏解冻判定（`needThawWhenScreenOn`，`HookInstaller.kt:439-454`）与外部唤醒许可（`isAllowWakeUpList`，317-331）。「冻结态 + 实时推送」无法同时成立 |
| 精确到毫秒的冻结时刻 | GreeZe 的冻结请求经它自己的串行 Handler 排队，AMS 前台状态回调本身有延迟，抖动在 10² ms 量级（报告 §2.9） | 模块还在中间加了一跳：`bodyForeground`（AMS 回调线程）→ `onBackground` → `work.postDelayed(...)`（`HandlerThread("HyperGreeze")`）→ `freezeUid`（`HookInstaller.kt:458-469`）。`delayMs` 是我们自己的 `postDelayed` 实现的，传给 GreeZe 的 `delay` 恒为 `0L`（488 行）。所以「最小可观测时延 = 一次跨线程 post + GreeZe 内部串行化」，**没有毫秒级保证**。观测侧同样到不了毫秒：tick 5 s（`ModuleController.kt:404`）、心跳每 6 个 tick 一次 ≈ 30 s（405 行 + `tick()` 233-254）、`ModuleTelemetry` 自身还有 5 s 心跳下限（`ModuleTelemetry.kt:161`） |

> 报告 §2.9 的另外两条（pid 级冻结 → `PARTIALLY_FROZEN`；`freezeThread` 只在 v1）在本模块里属于「未接线」而不是「接不上」：`freezePids` 与 `freezeThread` 都有 probe spec 与 facade 包装（`GreezeProbe.kt:104/106`，`GreezeFacade.kt:185-186`），但没有任何调用点——见 §2。

---

## 2. 本版本没做 / 故意不做

| # | 未做项 | 代码现状 | 原因 | 用户可见后果 |
|---|---|---|---|---|
| 1 | UI 读不到真实 per-uid 冻结状态 | hook 侧只发布 `Heartbeat`（`hooks` + `capabilities` 两个集合，`core/State.kt` 的 `Heartbeat` 无 per-uid 字段；写入见 `ModuleController.publishHeartbeat()`（257 起））；UI 的 `freezeStateOf()` 只按策略推断（`ui/vm/MainViewModel.kt:282-287`），而且这个函数与 `FreezeBadge` 组件目前**都没接到页面上**——列表真正渲染的是 `mode.label` | 真实状态要逐 uid 调 `isUidFrozen`/`getFreezeState`，每个 uid 一次 Binder 往返；在 system_server 里做这件事本身就是性能风险，而且 UI 是**普通 App**，根本拿不到 Binder 句柄 | 列表里显示的是**策略意图**（跟随/永不/立即/自定义），不是实测状态；`NEVER` 在本模块语义下确实不会被冻，`IMMEDIATE`/`CUSTOM` 只能显示 `UNKNOWN`，**不伪造徽章** |
| 2 | 广播 `isRestrictReceiver` 未接管 | 只有 probe spec（`GreezeProbe.kt:147`），既没 hook 也没调用；`applyResources` 只碰 net/job/wakelock（`HookInstaller.kt:507-509`） | 5 个参数 `(Intent, int, String, int, String)`（契约 §A.5 [:470]，包内可见）里有两个 `int`、两个 `String`，无法唯一确定包名参数；猜错是**全局**广播行为变化，不是单 App | `ResourceSwitches.broadcast` 只有 UI 开关，存进策略后无人消费（`PolicyEngine.resource("broadcast")` 有分支，但 `applyResources` 不取） |
| 3 | CPU 限频未接管 | 只有查询方法 `checkCpuLimitEnable()`（probe：`GreezeProbe.kt:149`，无调用点）；没有任何 setter | 契约里 CPU 侧只有 `checkCpuLimitEnable()` 与 `config_greezer_cpulimit_enable` 开关，**没有 per-uid setter**；报告 §1.4 也只列到 `checkCpuLimitEnable()` + `CpuLimitPolicy` | `ResourceSwitches.cpu` 只有开关位，无落点 |
| 4 | 闹钟（alarm）未接线 | `PolicyEngine.resource("alarm")` 有分支，`applyResources` 不取；契约里的 `releaseAlarmResource(int)`（§A.5 [:526]）没进 probe 表 | 没有确认过 `releaseAlarmResource` 的副作用边界（它按 uid 释放待发闹钟，语义比「缓存闹钟」更重）；宁可留空也不误调 | `ResourceSwitches.alarm` 只有开关位 |
| 5 | 子进程例外（`pkg:sub`）只在 `freezeProcess` 路径生效 | `AppPolicy.processes` 可配（`core/AppPolicy.kt:40-41`、UI `MainViewModel.setProcessMode`）；唯一把进程名送进判定层的是 `freeze-process` hook（`HookInstaller.kt:192-220`，`engine.neverFreeze(pkg, row.name)` 在 201）。`filter`（273）、`freezeUid`（340）、`freezeUids`（398）、`forceFreezeOnBackground`（461、474）一律传 `null` | 只有 `freezeProcess(RunningProcess,long,int,String)` 这一个入口自带进程名（契约 §A.2）；其余入口签名里只有 uid，没有进程名可传 | 子进程「永不冻结」只在 GreeZe 走 `freezeProcess` 时成立；子进程 `IMMEDIATE`、以及 `filter` 侧的子进程掩码覆盖都**不生效**。README 说「子进程级例外」时没区分这一点 |
| 6 | 解冻条件只接了 2 个 | 已接：`screenOn`（`HookInstaller.kt:447-449`）、`externalWake`（`PolicyEngine.kt:105`）。未接：`netChange`/`notification`/`audioFocus`/`charging` 只有策略字段与 UI 开关（`ResourceSwitches.kt` 的 `ThawTriggers.ORDER`） | 这四个事件在 GreeZe 里的消费点没有在契约里确证（契约 §A 没有对应方法；报告 §2.8 只给了「网络变化 → `thawUid("Net Change")`」这类描述，没有可 hook 的签名） | 四个开关存了不生效 |
| 7 | 热重载未启用 | `META-INF/xposed/module.prop` 只有 4 行：`minApiVersion=102`、`targetApiVersion=102`、`staticScope=true`、`exceptionMode=protective`——**没有 `autoHotReload`**；`HyperGreezeModule` 只覆写 `onModuleLoaded`/`onSystemServerStarting`/`onPackageLoaded`，不覆写 API 102 的 `HotReloadingParam`/`HotReloadedParam` | 没启用就不敢依赖：热重载会在同一进程里跑新代码并拿到旧 `HookHandle`，策略/遥测/守卫三处状态都得迁移，收益不抵风险 | 模块升级 = **软重启**（LSPosed 里对模块选择软重启，或重启手机）；只重启 App 无效，hook 侧代码仍是旧的 |
| 8 | 事件环的容量与「清空」边界 | 事件持久化在 `Settings.System["greeze_ctl_events_v1"]`，`ModuleTelemetry.loadExistingEvents()` 在拿到 Context 后读回上一次的内容（`ModuleTelemetry.kt:43-53`，ARCHITECTURE §6 同）；上限 `Module.EVENT_RING = 60`（`core/Keys.kt:58`），落盘节流到每 5 s 一次且脏检查（`ModuleController.kt:233-254`）；`global.compactEvents = true` 时环上限压到 20（`ModuleController.kt:163`、`ModuleTelemetry.kt:39`）。**重启即清空的是日志环**：`Logx` 的 `ArrayDeque` 是纯内存，上限 `Module.LOG_RING = 120`（57 行） | **此处修正立项假设**：事件环跨重启保留（只被 60 条上限截断，或被 `clearEvents` 主动清空）；日志环才是重启即失 | 重启后事件还能看到上一次的尾巴；日志只剩本次开机的 |
| 9 | 名单补刀默认关闭 | `GlobalPolicy.patchLists` 默认 `false`（`core/GlobalPolicy.kt:18`）；默认 `false`；为真时**开机与每次策略 revision 变化都会自动 `applyLists()`**（`ModuleController.kt:147、174`），也可手动下发 `OP_APPLY_LISTS`（360）。落到 `patchListField("mNoRestrictAppSet", …)` / `("mGrabTicketsAppList", …)`（`ModuleController.kt:366-375`） | 云控会重新下发 `MILLET_NO_RESTRICT_APP` / `cloud_white_list`，写字段必被覆盖；模块的主策略是「打在消费点」（`filter`/`isNoRestrictApp`/`isTempWhite`），字段补刀只是锦上添花 | 默认不改写；打开后每次策略变更都会重写 GreeZe 的两个名单字段，云控下发仍可能盖回来 |
| 10 | facade 是超集，一批包装没有任何调用点 | 有定义、但全仓（facade 之外）零调用：`freezePids`、`freezeUids`、`freezeAction`、`thawUids`、`thawPids`、`thawUidsByType`、`frozenUids`、`freezeState`、`processesOfUid`、`lastThawedTime`、`isUidPartiallyFrozen`、`isTempWhite`、`isNoRestrictApp`、`isAllowWakeUpList`、`isJobRestrict`、`isFreezeBinderEnabled`、`checkReason`、`listFieldSnapshot`、`listFieldSnapshotOn`、`dumpField`、`triggerIdleAppAction`、`triggerLaunchMode`（`GreezeFacade.kt` 全文） | 它们是给「诊断页/下一步功能」预留的反射封装；注意 `isTempWhite`/`isNoRestrictApp`/`isAllowWakeUpList` 这三个包装也**没有调用点**——hook 是拦 GreeZe 自己的同名方法，并不反过来调它们。`freezeUids` 的包装同样没被用：`freeze-uids` hook 直接 `chain.proceed(args)`，不经过 facade | **不要**把 facade 的方法表当成「已实装能力」清单。真正被调到的只有：`freezeUid`、`thawUid`、`thawAll`、`triggerQuickFreezeMode`、`setFreezeBinderEnabled`、`patchListField`、`packagesOfUid`、`isUidFrozen`、`setUdpRestrict`、`setJobRestrict`、`setWakeLockLimit`、`captureInstance`、`captureContext`、`resolveContext` |
| 11 | 命令/策略通道不做来源校验 | `ModuleController.processCommand()` 直接读 `Settings.System["greeze_ctl_command_v1"]` 并按 `seq` 执行（`ModuleController.kt:287-309`），不校验写入者；`PolicyStore` 同样只读（`PolicyStore.kt:84-89`） | Settings 里没有「谁写的」这一列；唯一门槛是 `WRITE_SETTINGS` appop | 任何持有该 appop 的 App 都能改策略、下发 `thawAll`/`freezePkg`。这是通道选型的固有代价（ARCHITECTURE §2） |

> 另有两条**与 README 不一致**的安全模式细节，放在 §5.4。

---

## 3. 未在真机确证

契约 §H 的条目 + 代码里的显式假设，合并成一张表。**「若为假的后果」列是评估风险用的，不是预测。**

| # | 断言 | 出处 | 代码依赖点 | 若为假的后果 | 验证方法 |
|---|---|---|---|---|---|
| 1 | `filter(int,String,int)` 第三个参数是**场景位集** | 契约 §H-11（只确证签名，未确证 `flags` 语义） | `R.asInt(chain.getArg(2))` 直接与 `maskKeep`/`maskDrop` 做位与（`HookInstaller.kt:272`、`PolicyEngine.kt:74-78`） | 掩码全部失效（位与恒不命中）→ 退化为 `original`；不会误冻 | 调试构建里把 `reason` 打进 logcat，与 `GreezeManagerService.getAurogonCheckReason(uid)` 的返回值对照 |
| 2 | `filter` 返回 `true` = 允许冻结 | 报告 §2.2；CHANGELOG 也标注「极性来自报告，未上机」 | `filterVerdict` 的 `NEVER → false`、`drop → true`（`PolicyEngine.kt:71、74-83`） | 极性反了就是灾难（「永不冻结」变「一定冻结」）。**但已有逃生阀**：`global.filterPolarityInverted = true` 会把模块覆写整体取反（`PolicyEngine.kt:79-83`、`GlobalPolicy.kt:25-31`、诊断页开关），系统原值 `original` 永不被改写 | 先只配一个无害 App，对照 `/sys/fs/cgroup/uid_<UID>/cgroup.freeze`；若表现相反就翻开关 |
| 3 | `SERVICE_NAME` 的字面值是 `"greeze"` | 契约 §H-3（**强推断**，`<clinit>` 里被内联，静态读不到） | `GreezeFacade.SERVICE_NAME`（`GreezeFacade.kt:394-397`）→ `ServiceManager.getService("greeze")`（88-99） | 只少一条实例兜底路径；构造器 hook 与 `sInstance` 字段仍有效 | `adb shell service list \| grep -i greeze` |
| 4 | `updateWakeLockLimit(int,boolean)` 是孤儿方法，且是实例方法 | 契约 §H-1（在 `method_ids` 里、不在 `class_data` 里，无 `access_flags`） | probe 按名+元数 2 解析（`GreezeProbe.kt:145`）；`setWakeLockLimit` 用 `callUnit`（`GreezeFacade.kt:312`） | 解析不到 → 静默 no-op（`callUnit` 返回 false，无人检查）；解析到但不是实例方法 → `targetFor` 给 null 后 `invokeRaw` 直接返回（`GreezeFacade.kt:132、148-150`） | 冻结后 `adb shell dumpsys power \| grep -i wakelock` 看该 uid 的 wakelock 是否受限 |
| 5 | `getFrozenUids(int)` 的参数是 **module**（0/1/2/9999），不是 uid | 契约 §H-5 + §A.4 | `frozenUids(module = 0)`（`GreezeFacade.kt:246-253`）——**当前无调用点** | 目前无影响；一旦接进 UI 就可能全表扫错 | `dumpsys greeze` 与返回值对照 |
| 6 | `*-IA` 变体未被运行时加载 | 契约 §H-7（`AurogonFilterManager-IA`、`GreezeManagerService-IA` 存在于 dex，签名未比对） | `GreezeProbe` 的类候选表只有非 IA 名字（`GreezeProbe.kt:77-93`） | 若真机走 IA 分支：`discover` 返回 null → 模块整体 inert（`ModuleController.kt:68-72`），系统行为 100% 原生，不会崩 | 启动后看 `greeze_ctl_heartbeat_v1` 是否存在；不存在即 inert |
| 7 | `needThawWhenScreenOn(int)` 存在 | 报告 §2.8 提到；**契约 §A 的方法表里没有** | hook `screen-on-thaw`（`HookInstaller.kt:230-236`） | 状态 `missing`，`thawOn.screenOn` 无效；无其它影响 | 心跳里看 `"id":"screen-on-thaw"` 的 `state` |
| 8 | `isRestrictBackgroundAction(...)` 存在（5 参） | 报告 §1.4 提到；契约 §A.5 只有 `isRestrictReceiver` 的 flags 行 | probe spec（`GreezeProbe.kt:148`），无 hook、无调用 | 只影响能力位 `resources` 的判定（53-58 行是 or 关系，缺一个不影响） | 不需要 |
| 9 | `onForegroundActivitiesChanged` 的两个 int 分别是 pid / uid（顺序未定） | 契约 §A.8（只有 `(IIZ)V`）+ §H-8；代码注释 `HookInstaller.kt:59-63` | `resolveUid()` 经验法（532-552），失败回退 `a1`；判定结果已进心跳 note（`uid=arg0/arg1/unresolved`，556-573） | 冻结目标可能错误（详见 HOOK_MAP §13）。缓解：只有 `IMMEDIATE` 策略的包才会真的写；判定结果可观测 | `adb shell settings get system greeze_ctl_heartbeat_v1 \| grep -o '"id":"foreground"[^}]*'`，与 `dumpsys activity activities` 的 pid/uid 对照 |
| 10 | 构造器 hook 的 `chain.thisObject` 指向正在构造的实例 | libxposed API 102 的 `Chain#getThisObject` 语义（`.probe/src/io/github/libxposed/api/XposedInterface.java`） | `bodyConstructor` 用它喂 `captureInstance`（`HookInstaller.kt:243`） | 实例捕获失败 → 只剩 `sInstance` 字段与 `ServiceManager` 两条路 | 看日志 `GreeZe init() observed; instance=constructor-hook` 里的来源串 |
| 11 | `FreezeState` 三态的稳定语义（`PARTIALLY_FROZEN` 何时落回 `UNFROZEN`） | 契约 §F 只确证 ordinal/value；报告 §9.2 列为未验证 | `GreezeFacade.freezeState()`（220-234）优先 `getValue()`，再 ordinal，再按名字 | 只影响 UI 徽章；当前 UI 根本不显示真实状态（§2-1） | `dumpsys greeze` 对照 |
| 12 | `getPackageNamesFromUid(uid)` 对 `uid=0`、隔离 uid、双开 uid 的返回 | 契约 §A.4 只给签名 | `packagesFor()` → 被 `filter`/`freeze-uid`/`freeze-uids`/`foreground` 四处依赖（`HookInstaller.kt:519-528`） | 返回空 → `NEVER` 静默失效、`filter` 的 pkg 只能靠 `arg1`、`resolveUid` 永远走 AOSP 回退 | 真机上 `adb shell dumpsys package <pkg> \| grep userId` 对照模块日志 |
| 13 | `freezeUids` 真的**不经过** `freezeUid` 落 cgroup（所以才需要单独过滤数组） | 代码注释的假设（`HookInstaller.kt:383-391`）；契约 §A.2 只给签名，没有调用关系 | `bodyFreezeUids` 的过滤逻辑（392-417） | 若它内部其实走 `freezeUid`：过滤是冗余但无害（两条路径都拦）；若不走且没过滤：批量冻结绕过 `NEVER` | 真机上让某 App 命中批量冻结，看 `/sys/fs/cgroup/uid_<UID>/cgroup.freeze` 与事件 `"d":"freezeUids filtered …"` |
| 14 | `ExceptionMode.PROTECTIVE` 的兜底细节（proceed 前/后异常的不同处理） | libxposed API 102 javadoc 原文（`.probe/src/.../XposedInterface.java`），未在 LSPosed 实现上验证 | `.setExceptionMode(PROTECTIVE)`（`HookInstaller.kt:99`） | 我们的 body 自己已 try/catch，所以最坏情况只是「少一层保险」 | 故意在调试构建里抛一次异常，观察是否传播到 AMS |

---

## 4. 版本漂移对照表

一句话：**所有解析都按「名字 + 元数」做，解析不到记 MISSING，任何解析失败都不抛异常，结果可在诊断页读到。**

| OTA 改了什么 | 解析/降级点 | 降级路径 | 可观测后果 |
|---|---|---|---|
| 类改名、挪包、切到 `*-IA` | `GreezeProbe.MANAGER_CANDIDATES` / `FILTER_CANDIDATES` / `IMMOBULUS_CANDIDATES` / `LIST_APPS_CANDIDATES` / `FREEZE_STATE_CANDIDATES`（`GreezeProbe.kt:77-93`） | 候选全不命中 → `discover()` 返回 null（185-190）→ `ModuleController.start()` 记一行日志后直接 return（68-72） | 模块整体 inert；**心跳不存在**；UI 诊断页表现为「无心跳/离线」；系统行为 100% 原生 |
| 方法改名 | `METHOD_SPECS` 的 `names` 数组，部分键自带别名（`isTempWhite`/`isTempWhiteList`、`isAllowWakeUpList`/`isAllowWakeupList`，`GreezeProbe.kt:138-140`） | 该键解析不到 → hook 状态 `MISSING`（`HookInstaller.kt:90-94`）；manager 上的键还会在 notes 里加一行「xxx 未找到」（`GreezeProbe.kt:208-210`） | 心跳 `hooks[].state = "missing"`；对应能力退化（HOOK_MAP 每节的「MISSING 行为」） |
| 参数个数变了（如 `freezeUid` 少一个 `abnormalType`） | `R.method()` 先按元数过滤；元数不匹配但**同名只有一种元数**时接受替换；多种元数并存则返回 null（`module/R.kt:41-74`） | 接受替换 → hook 仍装；多义 → `MISSING` | 参数取值全靠**位置索引**（`getArg(0..2)`，`HookInstaller.kt:270-272` 等）：尾部增减不影响，**头部或中间插参数才会错位**。`freeze-uid` 的 `uid` 恒为 `arg0`、`freeze-uids` 的数组恒为 `arg0`，所以它们的拦截仍安全 |
| 返回类型变了（`boolean` → `int`，或 `String` → `boolean`） | `R.asBool`/`asInt`/`asLong`/`asString`（`R.kt:145-166`）；body 里统一先 `as? Boolean`，类型不符就返回原始值（如 `HookInstaller.kt:265、294、319、441`） | 不改写 | 判定退化为「跟随系统」，不会误改 |
| 可见性改了（public → private/包内可见） | `R.method()` 里 `setAccessible(true)`（`R.kt:67-73`）；字段走父类链逐级 `setAccessible`（81-103） | 仍失败 → null → `MISSING` | 同上 |
| 字段改名 | `FIELD_SPECS`（`GreezeProbe.kt:165-183`）+ 解析时的四级回退：spec 指定类 → `immobulus` → `manager` → `listApps`（231-234） | `f(key)` 为 null | `listFieldSnapshot` 返回空、`patchListField` 返回 false（`GreezeFacade.listFieldSnapshot` / `patchListField`）；默认 `patchLists=false`，所以通常无感 |
| 枚举/返回值常量名变了 | `GreezeFacade.freezeState()`：先 `getValue()`，再 `ordinal`，再按名字（220-234）；`FreezeState.fromOrdinal/fromName` 兜底 `UNKNOWN`（`core/State.kt`） | `UNKNOWN` | UI 显示未知，不影响冻结 |
| 单例入口变了（`sInstance` 改名、`getService` 消失） | `resolveInstance()` 四级：`sInstance` 字段 → `ServiceManager` → `getService()` 静态方法 → 任意同类型静态字段（`GreezeFacade.kt:72-128`）；**永不调 `getInstance()`**（契约 §H-4） | 全失败 → `hasInstance=false`，所有实例方法调用返回 null | 心跳 `instanceOk=false`；冻结/解冻命令返回 `fail`；不会造僵尸实例 |
| `onForegroundActivitiesChanged` 挪到别的内部类 | `findForegroundCallback()` 扫 `declaredClasses`（`GreezeProbe.kt:258-271`） | 扫不到 → `MISSING`，能力位 `foreground=false` | 「立即冻结」失效 |
| 批量入口改名/消失（`freezeUids`） | probe spec `MethodSpec("freezeUids", arrayOf("freezeUids"), 5)`（`GreezeProbe.kt:103`） | `MISSING`（无独立能力位） | 批量冻结路径上的 `NEVER` 失效；单 uid 路径不受影响 |
| GreeZe 整体不存在（非 HyperOS / 被裁掉） | `discover()` 首步（`GreezeProbe.kt:186-190`） | 同「类改名」 | 模块 inert |

诊断页读什么：`adb shell settings get system greeze_ctl_heartbeat_v1`（`Heartbeat` 编解码见 `core/TelemetryCodec.kt:14-103`），字段含 `build`（`Build.FINGERPRINT`，`ModuleController.publishHeartbeat()`）、`greezeClass`、`instanceOk`、`hooks[]`、`caps{}`、`bootGuard`、`safeMode`。UI 侧同一份数据在 `ui/vm/MainViewModel.kt:289-310` 的 `diagnosticsReport()` 里导成文本。

---

## 5. 安全阀

### 5.1 四层关闭方式（效果不同，别混）

| 层 | 开关 | 代码 | 效果 |
|---|---|---|---|
| 启动守卫（自动） | `persist.sys.hypergreeze.guard`，阈值 `3` | `module/SafetyGuard.kt:34-59`；阈值 `Module.BOOT_GUARD_LIMIT = 3`（`core/Keys.kt:61`）；稳定窗口 `STABLE_WINDOW_MS = 90_000`（64 行，`ModuleController.kt:104-107`） | 连续 3 次开机没活到 90 s 的稳定窗口 → 本次及后续开机进被动模式（`hooks.passive = true`，`ModuleController.kt:95`） |
| 安全模式（手动） | `persist.sys.hypergreeze.safe = 1` + 原因串 `persist.sys.hypergreeze.safe_reason` | `SafetyGuard.kt:40`、`51`、`78` | 下次开机直接被动；原因会进心跳的 `safeModeReason` |
| 总开关（策略） | 策略 JSON 的 `global.enabled = false` | `core/GlobalPolicy.kt:9`；判定入口 `PolicyEngine.enabled`（`PolicyEngine.kt:18`），每个 body 都先查 | hook 仍装着，但每个 body 都返回原始值（等于模块不存在），心跳 `enabled=false` |
| 作用域（框架） | LSPosed 里取消「系统框架」作用域 | `META-INF/xposed/scope.list` 只有一行 `system` | 最彻底：模块根本不被加载进 system_server |

被动模式的关键语义：**hook 照样装**（`HookInstaller.install()` 不看 `passive`，只有 body 看），所以诊断页能显示「我是被动，原因是 X」（`HookInstaller.kt:556-573` 的 `snapshot()` 把 note 改成 `"safe mode: passive"`）。这样出问题时还能看到证据，而不是一片空白。

### 5.2 守卫自身的失效模式（诚实说明）

| 情况 | 行为 |
|---|---|
| `android.os.SystemProperties` 反射失败 | `SysProp.available = false`（`module/SysProp.kt`）→ 守卫读不到也写不了 → `overLimit=false`、`manual=false` → **fail-open**（不被动）。守卫不可用不会导致模块停用，也不会误判 |
| 属性写入被拒（非 system uid 或 SELinux） | `SysProp.set` 返回 false 但没人检查 → 计数不增长，等同于守卫失效 |
| 稳定窗口内崩溃但没重启 | 计数已经 +1，要连续 3 次才会触发；单次崩溃不被动 |
| 进了被动、之后想恢复 | 清零两个属性（§5.3 第三条），重启；或 UI 下发 `OP_CLEAR_SAFE_MODE`（`ModuleController.kt:349-353` 会把 `installer.passive` 置回 false 并清属性） |

### 5.3 逃生 adb 命令（三条）

```bash
adb shell settings delete system greeze_ctl_policy_v1
adb shell setprop persist.sys.hypergreeze.safe 1
adb shell "setprop persist.sys.hypergreeze.safe 0; setprop persist.sys.hypergreeze.guard 0"
```

| 条 | 作用 | 依据 |
|---|---|---|
| 1 | 删策略 → `PolicyCodec.decode(null)` 返回 `Policy.EMPTY` → 所有 App `FOLLOW`，模块变成纯跟随 | `core/PolicyCodec.kt:30-31`；`Policy.EMPTY`（`core/Policy.kt`） |
| 2 | 置安全模式 → 下次开机 hook 装但不接管（包括「刚刷完机发现 bootloop」的场景，此时 Settings 可能都进不去，属性更可靠） | `SafetyGuard.kt:40` |
| 3 | 解除安全模式 + 清守卫计数（恢复自动接管）；`setprop persist.sys.*` 在**部分 user 构建**上需要 `adb root`，没 root 时改用文末的作用域撤销 | `SafetyGuard.kt:70-76` |

另一条（不是命令，但最可靠）：LSPosed 里取消本模块的「系统框架」作用域 → 重启。

### 5.4 已知不一致：两个安全模式入口、一个死键

| 现象 | 证据 | 结论 |
|---|---|---|
| UI 的「请求安全模式」按钮**要下次开机才生效** | UI 写 `Settings.System["greeze_ctl_safe_mode_v1"] = "1"`（`ui/data/Repos.kt:69-72`）；hook 侧在拿到 Context 后由 `consumeSafeModeRequest()` 读取（`ModuleController.kt:145、182-189`），把 `persist.sys.hypergreeze.safe` 拉高并清掉请求标志 | 生效条件：本次开机 hook 侧跑到了 Context 阶段（即不是 bootloop 到起不来）；效果从**下一次**开机起算，与 UI 文案一致。真要立刻停用，用 §5.3 第 2 条 |
| README 的 `settings put system greeze_ctl_boot_guard_v1 0` 是「请求」而不是「立即生效」 | 该键由 `ModuleController.consumeGuardReset` 在拿到 Context 后消费（清 `persist.sys.hypergreeze.guard` 并删键）；真正权威状态在属性里 | 要立即清就用 `adb shell setprop persist.sys.hypergreeze.guard 0`（见 §5.3） |
| `greeze_ctl_safe_mode_reason_v1` 会被自检覆盖 | 同一键既当「上次安全模式原因」（`ModuleController.kt:184` 读），又被 `SettingsGateway.selfTest()` 当临时探针写入再读回（`Repos.kt:74-85`） | 若在点「请求安全模式」之前跑过自检，原因串可能是 `hypergreeze-probe-…`；真实原因看心跳的 `safeModeReason`（来自系统属性） |
