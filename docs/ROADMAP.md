# 迭代路线（1.1 起）

四个目标，按"会让用户后悔的程度"排序。每一条都写了**验收标准**；做不到的写进 `LIMITS.md`，不假装。

> 状态说明：✅ = 代码完成且 CI（含单测）覆盖；🧪 = 需要真机；⛔ = 明确不做，见 LIMITS。

## P0 可恢复性 —— 模块做的每一件事都能撤回

### 问题

模块会通过反射调用 GreeZe 的内部方法，其中一些**不在模块自己的数据里**：

| 动作 | 现状 | 撤回难度 |
|---|---|---|
| `freezeUid` | 冻结 cgroup | `thawUid` 可逆 |
| `thawAll` | 解冻全部 | 不可逆（但无害） |
| `udpPackageRestrict(uid,true)` | 切断该 uid 网络 | 有逆接口，但**没有 getter 读原值** |
| `jobRestrict(uid)` | 限制 JobScheduler | 只有 void setter，**没有解除接口** |
| `updateWakeLockLimit(uid,x)` | 限制 Wakelock | 有 getter，可精确还原 |
| `setFreezeBinderEnableCloud(x)` | 全局 binder 冻结开关 | 有 getter，可精确还原 |
| `patchListField(...)` | 改写 GreeZe 的内存名单 | 可逆（记下加了什么） |
| `triggerQuickFreezeMode(...)` | 触发系统模式 | 不可逆（只能等系统自己结束） |

### 已实现

1. **副作用账本**：`core/Journal.kt` + Settings 键 `greeze_ctl_journal_v1`（≤200 条）。每条记 `op / target / uid / pkg / before / after / ok / revision`；没有 getter 的项 `before` 恒为 `null`，不假装知道旧值。
2. **单一入口**：`module/Effector.kt`。所有会改变系统状态的调用都从这里走，hook 里不允许直接调 `GreezeFacade` 的写方法。
3. **逆操作**：`Effector.revertAll()` 从最新一条倒着做；能反的反，反不了的按名字进 `skipped`。
4. **两个按钮**：
   - **还原改动**（`OP_REVERT_ALL`）：解冻全部 → 反做资源/名单/binder → `suppressed=true` 停止再施加 → 清账本；策略保留。
   - **彻底清理**（`OP_CLEAR_DATA`）：还原 + 删除全部设置键与 `persist.sys.hypergreeze.*`，并把 telemetry 静音（否则心跳会把键写回来）。
5. **界限诚实**：`Job` 限制与模式触发标为不可还原；`NET_RESTRICT` 标为"半可还原"（只能放开）。资源开关**只加不减**：`net/wakelock` 的 `false` 是 no-op，绝不放松系统已有状态。

### 验收

- [x] 每次外部改动落一条账本（Effector 的 9 个 record 点）
- [x] 还原后账本清空、事件里有一条 revert 汇总、`suppressed` 进心跳
- [x] `jobRestrict` / `QUICK_MODE` 在 UI 与文档里标注不可还原
- [x] 还原后不会自动重新施加（`suppressed` 由策略 revision 变化解除）
- [x] `settings delete system greeze_ctl_journal_v1` 后模块正常（解码容错有单测）
- [x] 清理后模块不再写自己的键（`ModuleTelemetry.silenced`）
- [ ] 🧪 真机：改 3 个应用资源 → 还原 → 用 `isWakeLockLimit` / `getFreezeBinderEnable` 核对回到原值

## P1 易理解性 —— 细粒度不等于难懂

### 问题

配置维度：4 态 × 32 位掩码 × 子进程 × 6 资源 × 6 解冻条件。用户原话："功能这么细的情况下势必会造成理解难"。

### 已实现

1. **两档界面**（`global.uiExpert`）：默认简单模式 = 四态 + 一个延时滑块 + 一句话后果；专家模式才出现掩码/子进程/资源/解冻条件。
2. **后果导向文案**（`core/Explain.kt`，355 行 + 24 个单测）：说"即使 GreeZe 认为它在白名单，也会被冻结"，不说"drop 包含 64"。
3. **首页效果摘要**：一句话说清整体效果（"3 个应用永不冻结 · 2 个退后台立刻冻 · 其余跟随系统"）。
4. **详情页顶部**："这个应用现在会怎样"，最多 6 行，先说结论。
5. **策略试算**：`帮助与试算` 页输入包名 + 原因位，本地算出会不会冻；与 `filterVerdict` 同一套规则。
6. **术语表**：同一页内嵌 11 条术语 + 32 位逐位后果说明（keep / drop 两句），锁定项标「锁定」。

### 验收

- [x] 新用户不进「专家」也能完成：设 never、设 immediate、全部解冻
- [x] 每个掩码位有中文后果说明，LOCKED_DROP 明确写"不允许"
- [x] 试算结果与 `PolicyEngine.filterVerdict` 一致（`ExplainTest` + `PolicyEngineTest` 双向覆盖）
- [x] 首页/详情页有自然语言摘要

## P2 性能 —— 挂在 system_server 里的东西必须轻

### 已实现

| 位置 | 之前 | 现在 |
|---|---|---|
| `HookInstaller.engineProvider()` | 每次 hook 调用 new `PolicyEngine` | `PolicyStore` 缓存，仅策略变更时重建 |
| `hit()` | 每次调用进 `computeIfAbsent`（lambda） | 先 `get`，缺失才 `computeIfAbsent` |
| `filterVerdict` | `appPolicy()` 查两次 | 单次查表 + 局部变量 |
| UI 轮询 | 每 2.5s 解 60 事件 + 120 日志 | 原始串未变则不解析；变了在 `Dispatchers.Default` 解 |
| `IconCache` | 96 项 / 96px | 200 项 / 72px |
| 事件 flush | hook 体内直接写 Settings | 5s tick + dirty 守卫；拦截路径不再写 |
| 账本 flush | — | 同上，5s tick |

### 验收

- [x] hook 热路径无 per-call 对象分配（除装箱）
- [x] 拦截/强制冻结路径不再触发 Settings 写
- [x] UI 主线程不再解析 JSON（原始串变化时切到 Default）
- [ ] 🧪 100 次 fg/bg 后 `dumpsys meminfo` 无异常增长（需真机）

## P3 外观 —— Miuix 不是"Material 换色"

### 已实现

- 行按压反馈（`cardPressed` 背景 + 箭头变色 + 0.985 缩放）
- 详情页头部：应用图标 + 模式徽章 + 一句话
- 应用列表骨架屏（6 行 shimmer，`rememberInfiniteTransition`）
- Tab 选中态：图标后 46×26 胶囊 + 主色
- 徽章/三态控件基线对齐、`MiuixDivider` 0.75dp

### 验收

- [x] 按住任意列表行有可见反馈
- [x] 列表首次加载显示骨架而不是空白
- [ ] 🧪 深色模式下对比度检查（`#242424` 卡片 / 90% 白文字）

## 不在本轮

- 真机验证：`uid=arg0/arg1`、`filter` 极性、`needThawWhenScreenOn` 是否存在
- 单线程冻结、单 binder 事务冻结（cgroup v2 边界）
- 闹钟/广播/CPU 限频接线（没有可靠的 per-uid 消费点）
