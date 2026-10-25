# Changelog

所有改动按「为什么」而不是「改了什么文件」记录。

## [1.2.0] — 2026-10-25

修复「HyperOS 上 `WRITE_SETTINGS` 显示已授权、但 `Settings.System.putString` 返回 false，导致策略根本存不进去」。

### 下行通道改为双通道（provider 优先）

* App 把策略/命令写进自己的 SharedPreferences，通过**只读 `ContentProvider`**（`content://dev.hypergreeze.policy/policy`）暴露；`system_server` 直接 `query`。这条路不依赖任何权限，ROM 无法拒绝。
* `Settings.System` 退化为**镜像 + 迁移回退**：能写就写（`adb shell settings get` 仍可用），写不进去不影响主通道。
* 写入成功判定改为 `SharedPreferences.commit()` 的结果；UI 不再因为缺 `WRITE_SETTINGS` 就禁止保存（该权限现在只影响镜像与安全模式请求）。
* provider 只允许 `uid 1000 / 0 / 自身` 读取，`insert/update/delete` 一律抛异常 —— 导出一个只读通道不会变成改策略的入口。
* **防止策略被清空**：周期 tick 只读 Settings（避免每 5 秒拉起 App 进程）；当 Settings 不可读且没有 provider 应答时**保留**当前策略。此前这种组合会把策略静默重置成「全部跟随」。
* 诊断页新增「策略通道」：显示 hook 侧最后一次读到策略的来源（provider / settings / none）；写入自检改为真实链路（prefs → provider → 读回）并单独报告 Settings 镜像是否可用。

### `foreground` hook 不再 missing

在 `GreezeManagerService` 的内部类里找不到 `onForegroundActivitiesChanged(int,int,boolean)` 时，依次回退到 `AurogonFilterManager` / `AurogonImmobulusMode` 的内部类，以及 AOSP 的 `ActivityTaskManagerService` / `ActivityManagerService` 同名 3 参回调。诊断页的 note 会显示实际落点类名。

### 诊断报告导出到文件

* 不再用剪贴板（内容太长，粘贴板存不下）。报告写入 `getExternalFilesDir/reports/hypergreeze-<时间>.txt`，通过 `FileProvider` + 系统分享面板导出。
* 报告本身也压缩：固定字段 + hooks 全量 + caps 单行 + 事件/账本/日志各上限 15 条。

### CI

* 新增断言：APK 的 manifest 必须同时包含 `dev.hypergreeze.policy` 与 `dev.hypergreeze.files` 两个 authority（防止 provider 被误删而「静默退回不可用」）。

## [1.1.0] — 2026-10-25

主题：**可恢复性、易理解性、性能、外观**（详见 `docs/ROADMAP.md`）。

### 可恢复性（P0）

* **副作用账本**（`core/Journal.kt` + 键 `greeze_ctl_journal_v1`，≤200 条）：每次改系统状态记一条，带 `before/after`、ok、策略 revision。
* **单一写入口**（`module/Effector.kt`）：hook 层不再直接调 `GreezeFacade` 的写方法，漏记在结构上不可能发生。
* **还原**（`OP_REVERT_ALL`）：`thawAll` → 账本倒序反做 → 清账本 → `suppressed` 抑制再次施加；策略保留。
* **彻底清理**（`OP_CLEAR_DATA`）：还原 + 删全部设置键与属性；同时把 `ModuleTelemetry.silenced` 置真，心跳不会把键写回来。
* UI：首页「模块改动 · 可还原」入口 + 确认弹层（列出将还原的条目、单独列出不可还原项、两个语义不同的按钮）。
* **资源开关只加不减**：`net`/`wakelock` 的 `false` 变成 no-op。之前会真的调 `udpPackageRestrict(uid,false)` 放开网络——既违背「不放松系统限制」的设计，也没有 getter 能记原值。

### 易理解性（P1）

* **`core/Explain.kt`**：把配置翻译成人话（四态后果、详情页 3–6 行结论、首页一句话摘要、原因位中文、逐位后果、策略试算）。
* **两档界面**（`global.uiExpert`）：默认只显示四态 + 一个延时滑块；掩码/子进程/资源/解冻条件收进「专家选项」。
* **首页效果摘要** + **详情页顶部「这个应用现在会怎样」**（带应用图标与模式徽章）。
* **新页面「帮助与试算」**：11 条术语 + 32 位逐位后果（keep/drop 各一句，锁定项标注）+ 输入包名与原因位本地试算。

### 性能（P2）

* `PolicyStore` 缓存 `PolicyEngine`：此前**每次 hook 调用都 new 一个**（`filter` 是高频路径）。
* `hit()` 快路径：先 `get` 再 `computeIfAbsent`，热路径不再分配 lambda。
* `filterVerdict` 单次查表（原来查两次）。
* UI 轮询：原始串未变不解析；变化时切 `Dispatchers.Default`。
* 拦截/强制冻结路径不再直接写 Settings（改由 5s tick 统一 flush）。
* `IconCache` 200 项 / 72px。

### 外观（P3）

* 列表行按压反馈（`cardPressed` 背景 + 缩放 + 箭头变色）。
* 应用列表骨架屏（shimmer）。
* Tab 选中态胶囊。
* 详情页头部图标 + 徽章；徽章与三态控件基线对齐。

### 测试

* 新增 `ExplainTest`（24 用例）、`JournalTest`（16 用例）；总用例从 28 → 68。
* 账本编解码对 `org.json` 不保证键序这件事做了容错（不依赖字段顺序，逐字段读取）。

## [1.0.2] — 2026-10-25

运行时无改动，补测试与 CI 门禁：

* 新增 3 个纯 JVM 测试类（28 个用例）：`PolicyCodecTest`（往返/容错/锁定位剥离/数值钳制）、`SceneBitTest`（位唯一性/掩码往返/锁定集合/枚举覆盖）、`PolicyEngineTest`（四态、keep/drop、极性反转、进程覆盖、延时回落、主开关放行）。
* CI 增加 `:app:testReleaseUnitTest` 步骤，测试失败即构建失败；测试报告随 artifact 上传。
* 用真实 `org.json` artifact 替换 android.jar 的桩实现，保证编解码测试走的是运行时同一份代码。

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
