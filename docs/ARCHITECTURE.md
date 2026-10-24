# 架构

> 目标读者：要改这个模块的人。读完应该能回答「一次退后台到真正被冻住，中间经过了哪些代码」。

## 1. 三个进程/两套代码

```
┌───────────────────────────────┐         ┌──────────────────────────────────────────┐
│ HyperGreeze APK（普通应用）    │         │ system_server（LSPosed 注入）             │
│                               │         │                                          │
│ ui/         Compose + Miuix   │  写     │ module/                                  │
│ ui/data/    SettingsGateway   │ ──────► │   HyperGreezeModule  (API 102 入口)       │
│ ui/vm/      MainViewModel     │ Settings│   ModuleController   (生命周期/命令/心跳) │
│                               │ .System │   HookInstaller      (8 个 hook)          │
│                               │ ◄────── │   PolicyStore        (读策略 + 观察者)     │
│                               │  读     │   PolicyEngine       (纯判定)             │
└───────────────────────────────┘         │   ModuleTelemetry    (回写心跳/事件/日志)  │
                                          │   SafetyGuard        (启动守卫)            │
                                          │   greeze/GreezeFacade(反射调 GreeZe)       │
                                          └──────────────────────────────────────────┘
                     core/  ← 两侧共享：策略模型 / JSON 编解码 / 位定义 / 命令 / 遥测
```

`core/` 不依赖 Android 以外的任何东西（只用到 `org.json`），因此它在两个进程里的行为完全一致。

## 2. 为什么是 Settings.System 而不是 Binder / socket / XSharedPreferences

| 方案 | 结论 |
|---|---|
| `Settings.System` JSON 键 | **采用**。app 侧只需 `WRITE_SETTINGS` appop；`system_server` 直接 `Settings.System.getString` 读；跨重启持久；天然按用户隔离；写入走 SettingsProvider，没有 SELinux 跨域问题。 |
| Binder / AIDL 服务 | 不走。要给 system_server 加一个可被三方 App 绑定的服务，等于新开一个攻击面，还得处理权限校验。 |
| Unix socket / 文件 | 不走。App 无法写 `/data/system`，system_server 连 App 的 socket 会被 SELinux 拦。 |
| `XSharedPreferences` | 不走。只读语义，写回不实时；且新 API 里它属于 legacy 路径（模块 target 102 后不能调 legacy API）。 |

代价（已在文档里写明）：app 必须拿到 `WRITE_SETTINGS`；写失败时 UI 会明确报错并给出手动授权入口，而不是假装保存成功。

## 3. 启动时序（`ModuleController.start()`）

```
onModuleLoaded             → 挂日志 sink；非 system_server 直接 detach()
onSystemServerStarting     → ModuleController.start()
   ├─ GreezeProbe.discover(classLoader)      按「类名候选 → 方法名+元数」解析，缺失只记 note
   ├─ GreezeFacade                           实例先空，等下面两个钩子喂
   ├─ SafetyGuard.begin()                    只读/写 persist.sys.*，此刻不需要 Context
   ├─ HandlerThread("HyperGreeze")           所有重活都在这里，绝不占用引导线程
   ├─ HookInstaller.install()                8 个落点，逐个 try/catch，失败记 ERROR
   ├─ post(ensureContext)                    Context 可能要等，最多重试 20×1.5s
   └─ postDelayed(markStable, 90s)           稳定窗口：走过就清空启动守卫计数
```

**关键设计**：hook 在策略加载之前就装好了。此时策略为空 → `PolicyEngine` 对任何包都返回 `FOLLOW` → 每个 hook 都原样返回，等于不存在。所以「先装 hook 再拿 Context」不会误导系统。

`ensureContext()` 成功后依次：`ModuleTelemetry.loadExistingEvents()` → 从上次心跳恢复 `lastCommandSeq` → `PolicyStore.start()`（ContentObserver + 30s 兜底轮询）→ 注册命令观察者 → 发一次心跳。

## 4. 一次「退到后台」的完整路径

```
AMS 回调 → GreeZeManagerService$13.onForegroundActivitiesChanged(a0, a1, foreground)
   └─ [hook foreground] chain.proceed() 先让 GreeZe 自己处理
        └─ foreground == false
             ├─ resolveUid(a0, a1)     谁能解析出包名谁就是 uid（未确证参数顺序）
             └─ onBackground(uid, pid)
                  ├─ engine.forceFreezeOnBackground(pkg)?  模式必须是 IMMEDIATE 且总开关打开
                  ├─ pendingFreeze 去重（同一 uid 不叠加任务）
                  └─ work.postDelayed(delayMs) → forcedFreeze(uid, pid, pkg)
                       ├─ isUidFrozen(uid) == true → 记 skip(already-frozen)
                       ├─ 冷却窗口（按上次尝试时间，不用 getLastThawedTime：单位未确证）
                       ├─ applyResources(uid, pkg)      net/job/wakelock 有确证 setter 的三项
                       └─ facade.freezeUid(uid, 0, REASON_MOD, "hypergreeze", 0)
                            └─ 返回值（GreeZe 自己的原因串）写进事件环
```

同时，GreeZe 自己的冻结请求会经过 `freezeUid` hook：**先判定再决定要不要 `proceed()`**——策略是 NEVER 时直接返回 `"SKIP_BY_MOD"`，根本不让它写 cgroup；其它情况先 `proceed()` 拿到真实结果再审计。

## 5. 线程模型

| 线程 | 干什么 | 不许干什么 |
|---|---|---|
| system_server 引导线程 | 调 `start()`，只做解析 + 装 hook | 不做 IO、不读 Settings、不调 GreeZe 业务方法 |
| `HandlerThread("HyperGreeze")` | 强制冻结、命令执行、遥测 flush、日志 flush | 不阻塞等待 |
| system_server 主线程 Handler | Context 重试、心跳节拍、稳定窗口标记 | 不做重活 |
| GreeZe 自己的回调线程 | 进 hook body | body 内只做纯计算 + 投递，不 await |

`PolicyStore` 的 ContentObserver 用主线程 Looper 注册，实际重活转投 worker。

## 6. 状态与不可变性

* `Policy` 是不可变 `data class`，每次编辑产生新实例并把 `revision + 1`。
* hook 侧不缓存 `AppPolicy`，只缓存 `Policy` 对象引用；`PolicyEngine` 每次 `engineProvider()` 现取现建（廉价，只是包一层）。
* uid → 包名缓存（`uidPackages`）在策略 revision 变化时由 `installer.invalidateUidCache()` 清空。
* 事件环是单写者（hook 侧），UI 只读；重启后从 Settings 里读回上一次的内容，再被新事件覆盖。

## 7. 错误处理的三层

1. **hook 体内**：先 `chain.proceed()` 拿真实结果 → 自己的判定包在 try/catch 里 → 异常就返回真实结果。`ExceptionMode.PROTECTIVE` 是第二道保险。
2. **反射层**：`GreezeFacade` 所有调用返回 `null`/默认值并累加 `errorCount`，不抛。
3. **入口**：`onSystemServerStarting` 整体 try/catch，最坏情况是这个 boot 里模块完全无效，但系统照常启动。

## 8. 诊断可见性

hook 侧每 30s（以及策略变更/命令执行后）把一份 `Heartbeat` 写进 Settings：框架版本、GreeZe 类名、实例是否捕获、每个 hook 的 state/hits/errors、能力探测结果、安全模式与守卫计数。UI 轮询读它——这是「一个普通 App 如何观测 system_server 内部」的唯一诚实答案。

## 9. 目录

```
app/src/main/java/dev/hypergreeze/
├── core/       策略模型、JSON 编解码、位定义、命令、遥测编解码（两侧共享）
├── module/     hook 侧：入口、控制器、安装器、反射门面、策略/遥测/守卫
└── ui/         Compose UI：theme(令牌) / components(自绘控件) / pages / data / vm
```
