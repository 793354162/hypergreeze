# Changelog

所有改动按「为什么」而不是「改了什么文件」记录。

## [1.0.1] — 2026-10-25

* **`processes`（子进程例外）真正接线**：新增 `freeze-process` hook，从 `RunningProcess` 反射读 uid / processName，命中进程级 `NEVER` 时不 `proceed()`。此前该字段只存不用。
* **`freeze-uids` 批量路径**：新增 hook，把 `NEVER` 的 uid 从数组里滤掉（全被滤掉时直接返回空列表，不调 `proceed`）。此前批量冻结可以绕过单 uid 拦截。
* **全局开关接线**：`patchLists` / `compactEvents` / `applyFreezeBinder` 现在在策略加载与每次 revision 变化时执行（`applyGlobalSideEffects`）；此前三者只存不用。
* **事件补发射点**：`THAW`（三个解冻命令）与 `BOOT`（拿到 Context 后一条）；`BLOCK` / `ERROR` 仍无写入点（异常只进计数与日志）。
* **守卫清零可用 Settings 请求**：`settings put system greeze_ctl_boot_guard_v1 0` 会被 hook 消费（此前是死键，README 的逃生命令无效）。
* **事件页文案修正**：事件环是持久化的（`loadExistingEvents`），不会因重启清空；重启丢的是内存日志环。
* 事件落盘节流：tick 5s，心跳 30s，`flushEvents` 由 dirty 标志守卫。

## [1.0.0] — 2026-10-25

第一版：把可行性分析里的「影子式 MVP」和「强制式」两条路径都实现了，UI 按 Miuix 重画。

### 能力

* **逐 App 四态**（跟随系统 / 永不冻结 / 立即冻结 / 自定义）。`FOLLOW` 是唯一默认值，任何未配置的 App 都不会被动到。
* **32 位场景掩码**：`keep` 强制豁免、`drop` 强制不豁免，`LOCKED_DROP`（AUDIO/CAMERA/TOP/IME/VISIBLE/VIBRATOR）在解码阶段就会被清位，手改 JSON 也绕不过。
* **退后台立即冻结**：带 `delayMs` 与 `cooldownMs`，同一 uid 任务去重，冻结前查 `isUidFrozen`。
* **子进程覆盖**：按完整进程名（`pkg:sub`）登记进程级 `NEVER`；只有 `freezeProcess(RunningProcess,…)` 这条路径带进程名，所以子进程 `IMMEDIATE` 与子进程掩码覆盖不生效（见 `docs/LIMITS.md`）。
* **资源联动**：net / job / wakelock 三项走已确证的 setter；alarm / broadcast / cpu 只有开关位，没有可靠落点，UI 上标注而未接线。
* **解冻条件**：`needThawWhenScreenOn` 被 hook；`externalWake` 接到 `isAllowWakeUpList`；其余（netChange/notification/audioFocus/charging）目前只存策略，需要在真机上确认消费点后再接。
* **事件审计**：`freezeUid` 的真实返回串、跳过原因、命令回执都进环形缓冲。
* **命令通道**：10 个 op（解冻全部/解冻 uid/冻结 uid/冻结包名/快速模式/重置 cgroup/清事件/清安全模式/补写名单/binder 开关），用 `seq` 水位防水位回放。
* **安全模式**：`persist.sys.hypergreeze.guard` 计数，连续 3 次未进入稳定窗口 → 被动模式；`persist.sys.hypergreeze.safe` 可手动置位。两条路径都不依赖 `Context`，因此在最早阶段就生效。

### 关键实现决定（附理由）

1. **不做描述符精确匹配**。GreeZe 的所有方法按「名字 + 元数」解析，元数对不上但名字唯一时接受；名字有歧义时判 `missing`。理由：MIUI OTA 改参数是最常见的破坏方式，而模块不该因为多了一个 `boolean` 就整个失效。
2. **绝不调 `getInstance()`**。字节码显示它是「为空就 new」的 DCL，在早期调用会造出僵尸实例。实例来自：构造函数 hook → `init(Context)` hook → `sInstance` 静态字段直读 → `ServiceManager.getService("greeze")`（该类 `super` 是 `IGreezeManager$Stub`，`Lifecycle.onStart()` 会 `publishBinderService`）。
3. **`freezeUid` 是唯一"先判定再 proceed"的 hook**。其余 hook 都是先 `proceed()` 拿真实结果再改写——只有这样才能既尊重系统又不吞异常。
4. **安全守卫用系统属性而不是 Settings**。守卫必须在 `Context` 存在之前可读，否则 bootloop 防护本身就是死锁。
5. **UI 不用 Material**。Miuix 不是 Material；混用会让圆角语义、触摸反馈、排版比例全错。图标也是自绘 `ImageVector`。
6. **`Settings.System` 作为唯一通道**。它需要 `WRITE_SETTINGS`，但换来的是「无 SELinux 问题 + 跨重启持久 + 双向 + 无新增 Binder 攻击面」。写失败时 UI 明确报错，不假装成功。
7. **`libs/libxposed-api-102.jar` vendored**。官方 `api:102.0.0` AAR 声明 `minCompileSdk=37`，公开 SDK 只到 36。CI 每次从 Maven Central 重下 AAR 比对 sha256，保证 vendored 文件没被动过。

### 已知缺陷 / 未验证（真机前请先读 `docs/LIMITS.md`）

* `onForegroundActivitiesChanged(int, int, boolean)` 的两个 int 谁是 uid **未确证**。代码用「哪个参数能解析出包名」的经验判定，并在日志里记录决策。
* `AurogonFilterManager.filter` 第三参是位集还是单值 **未确证**；`filter` 返回 `true` = 允许冻结，这个极性来自报告，未上机。
* UI 不显示真实 per-uid 冻结状态（只在策略层面确定「NEVER 一定没被冻」）。
* 热重载未启用（`autoHotReload` 未声明），升级需软重启。

### 修掉的坑（留档，避免复发）

| 现象 | 根因 |
|---|---|
| `Set up Android SDK` 失败 `Failed to find package 'tools'` | `android-actions/setup-android@v3` 仍装已移除的 `tools` 包 → 改用 runner 自带 SDK |
| `Keystore file ... not found` | `storeFile` 相对 app 模块解析，代码里却用 `rootProject.file()` |
| `Invalid TOML catalog definition: Unexpected '\ufeff'` | 用带 BOM 的方式重写了 `libs.versions.toml` |
| `Unresolved reference: KeyboardArrowRight` | `material-icons-core` 图标子集不含它 → 全部改自绘图标 |
| `Unresolved reference: PolicyEngine` | `PolicyEngine` 在 `dev.hypergreeze.module` 包里，却按 `core` 包导入 |
