# 迭代路线（1.1 起）

四个目标，按"会让用户后悔的程度"排序。每一条都写了**验收标准**，做不到就写进 `LIMITS.md`，不假装。

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

用户要"一切改变都可还原"，那就必须**记录 + 反做 + 说清楚哪几项反不了**。

### 设计

1. **副作用账本**（`core/Journal.kt` + `Settings.System` 键 `greeze_ctl_journal_v1`）
   每次外部改动写一条：`op / target / uid / pkg / before / after / ok / revision`。
   `before` 只在有 getter 时填（wakelock、binder），没有就写 `null` —— 账本必须诚实，不能假装知道旧值。
2. **单一入口**（`module/Effector.kt`）
   所有会改变系统状态的调用都走这里，不允许 hook 里直接调 `GreezeFacade` 的写方法。这样"漏记"在结构上不可能发生。
3. **逆操作**（`Effector.revertAll()`）
   从最新一条往回走，按 op 分派逆操作；能反的反，反不了的进 `skipped` 并原文告诉用户原因。
4. **两个按钮，语义不同**
   - **还原改动**：解冻全部 + 反做资源/名单/binder + 停止再次施加（suppress）+ 清账本。**不动策略**，用户配置留着下次能再开。
   - **彻底清理**：在还原基础上删掉模块写入的所有 `Settings.System` 键与 `persist.sys.hypergreeze.*` 属性。给"我要卸载了"用。
5. **卸载后的残留**（写进文档，不能让用户以为卸载就干净了）
   - hook 立即消失（进程重启）
   - 名单改动立即消失（内存）
   - **网络/Job/Wakelock 限制会留到重启**（写进了 GreeZe 的内部状态）
   - 我方 Settings 键与属性永久保留，除非点"彻底清理"或手动删

### 验收

- [ ] 随便改 5 个应用的资源开关 → 账本出现 5 条 → 点"还原改动" → 账本清空、事件里有一条 revert 汇总、`isWakeLockLimit`/`getFreezeBinderEnable` 回到原值
- [ ] `jobRestrict` 被如实标记为"不可还原"，并给出原因
- [ ] 还原后 30 秒内不再自动重新施加（suppress 生效）
- [ ] `settings delete system greeze_ctl_journal_v1` 后模块不会崩

## P1 易理解性 —— 细粒度不等于难懂

### 问题

配置维度：4 态 × 32 位掩码 × 子进程 × 6 资源 × 6 解冻条件。用户原话："功能这么细的情况下势必会造成理解难"。

### 设计

1. **两档界面**：默认「简单」，只出现四态 + 3 个最常用开关 + 一行后果说明；「专家」才出现 32 位掩码、子进程、资源、解冻条件。开关状态随策略一起存（`global.uiExpert`）。
2. **后果导向文案**：不写"drop 包含 64"，写"即使 GreeZe 认为它在白名单，也会被冻结"（`core/Explain.kt`）。
3. **首页效果摘要**：一句话说清现在的整体效果，而不是罗列配置项。
4. **策略试算**：给一个包名 + 原因位，本地算出"会不会冻"，不用等真机。
5. **帮助页**：术语表（keep/drop/reason/partially frozen…）+ 每个高级项一个「?」。
6. **详情页顶部一句话**：进页面第一眼就知道"这个应用现在会怎样"。

### 验收

- [ ] 新用户不进「专家」也能完成：设一个 never、设一个 immediate、全部解冻
- [ ] 每个掩码位旁有中文后果说明，LOCKED_DROP 明确写"不允许"
- [ ] 试算结果与 `PolicyEngine.filterVerdict` 逐条一致（单测覆盖）

## P2 性能 —— 挂在 system_server 里的东西必须轻

### 问题（已定位）

| 位置 | 问题 | 影响 |
|---|---|---|
| `HookInstaller.engineProvider()` | **每次 hook 调用都 new 一个 `PolicyEngine`** | `filter` 是高频路径，纯浪费 |
| `PolicyStore.engine` getter | 同样每次 new | 每个调用点都在分配 |
| `HookInstaller.hit()` | `ConcurrentHashMap.computeIfAbsent` + lambda | 每调用一次一个 lambda 对象 |
| UI 轮询 | 每 2.5s 解 JSON（60 事件 + 120 日志）在主线程 | 滚动时可能掉帧 |
| `IconCache` | 96 项 / 96px | 长列表回滚会重新解码 |
| `PolicyEngine.filterVerdict` | `appPolicy(pkg)` 调两次 | 每次判定两次 map 查找 |

### 设计

缓存 `PolicyEngine`（策略变更时重建）、计数器快路径、UI 侧"原始串未变就不解析"、图标缓存 200 项 / 72px、`filterVerdict` 单次查表。

### 验收

- [ ] hook 热路径（`filter` / `freezeUid`）零对象分配（除必要的装箱）
- [ ] 100 次 fg/bg 切换后 `hits` 增长但无 GC 抖动（`dumpsys meminfo` 对照）
- [ ] UI 轮询期间主线程无 JSON 解析（可加 trace 验证）

## P3 外观 —— Miuix 不是"Material 换色"

### 设计

- 行按压反馈（`cardPressed` 背景 + 箭头变色）
- 详情页头部：应用图标 + 模式徽章 + 一句话
- 应用列表骨架屏（首次加载不再空白）
- Tab 选中态：图标/文字主色 + 顶部小指示
- 徽章与三态控件的基线对齐
- 深色模式检查（`#242424` 卡片 / `90% 白` 文字）

### 验收

- [ ] 按住任意列表行有可见反馈
- [ ] 列表首次加载显示骨架而不是空白
- [ ] 深色模式下没有对比度低于 4.5:1 的文字

## 不在本轮

- 真机验证（需要用户设备）：`uid=arg0/arg1`、`filter` 极性、`needThawWhenScreenOn` 是否存在
- 单线程冻结、单 binder 事务冻结（cgroup v2 边界）
- 闹钟/广播/CPU 限频接线（没有可靠的 per-uid 消费点）
