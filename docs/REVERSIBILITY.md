# 可还原性（REVERSIBILITY）

**承诺**：模块对系统做的每一次外部改动，先落一行账；点「还原」时按账本倒序反做，反不了的原话告诉你原因。
**边界**：能撤回的是「模块自己做出的外部改动」；撤不回的是「没有逆接口的那几项」——`JOB_RESTRICT`、`QUICK_MODE`，以及 `NET_RESTRICT` 的**原值**（没有 getter）。逐项见下表。

> 依据：`core/Journal.kt`（账本与编解码）、`module/Effector.kt`（唯一写入口 + `revertAll()`）、`module/ModuleController.kt`（命令通道与心跳）、`module/greeze/GreezeFacade.kt`（反射封装）。GreeZe / cgroup 层面的硬边界不在本文重复，见 [LIMITS.md](LIMITS.md) §1。
> 行号为当前工作树实测，函数名是稳定锚点。

---

## 0. 三条判据（先看这个，再看表）

| 判据 | 代码位置 | 含义 |
|---|---|---|
| `EffectOp.reversible` | `core/Journal.kt:29-37`（枚举项） | 这一类 op **语义上**有没有反做动作 |
| `JournalEntry.ok` | `core/Journal.kt:78` | 这次调用本身成功没有（`freezeUid` 只认返回 `"OK"`） |
| 真能撤 = `ok && reversible` | `JournalEntry.undoable`（`Journal.kt:124`）、`JournalRing.undoableCount`（`Journal.kt:290`） | 心跳里的 `.journal` 就是这个计数 |

所以「不可还原」有两层，别混：

| 层 | 例子 | `revertAll()` 里的表现 |
|---|---|---|
| 设计上没有逆操作（`reversible=false`） | `THAW`、`JOB_RESTRICT`、`QUICK_MODE`、`REVERT` | 进 `skipped` 或直接跳过（`THAW`/`REVERT` 静默，`JOB_RESTRICT`/`QUICK_MODE` 带原因） |
| 有逆操作但不知道原值（`before == null`） | `NET_RESTRICT`（恒 null）、getter 缺失时的 `WAKELOCK_LIMIT`/`FREEZE_BINDER` | 按 op 分派：要么只「放开」，要么写「原值未知」进 `skipped` |

---

## 1. 九个 EffectOp 逐张表

### 1.1 `FREEZE`（`wire="freeze"`，`reversible=true`）

| 项 | 内容 |
|---|---|
| 谁触发 | `Effector.freeze()`（`Effector.kt:68`）← 命令 `freezeUid` / `freezePkg`（`ModuleController.execute`） |
| `before` | **恒 null**：冻结前不问 GreeZe 原来冻没冻 |
| `after` | GreeZe 的返回串；拿不到时写字符串 `"null"` |
| `ok` | `result == "OK"`，其它串（含 `SKIP_BY_MOD`）一律算失败 |
| 逆操作 | `facade.thawUid(entry.uid)`（不带 reason/tag，走默认） |
| 能否还原 | 能。但只保证「解冻」，不保证「回到冻结前那种冻结方式」 |
| 失败/不可还原表现 | `skipped += "解冻失败 uid N"` |

⚠️ **自动冻结不记账**：退后台立即冻结走 `HookInstaller.forcedFreeze()` → `facade.freezeUid(...)`，**不经过 Effector**。理由写在 `Effector.kt:16-19`：它持续发生，会把 200 格账本挤空。这类冻结由 `revertAll()` 开头的 `thawAll()` 兜住。

### 1.2 `THAW`（`wire="thaw"`，`reversible=false`）

| 项 | 内容 |
|---|---|
| 谁触发 | `Effector.thaw()`（命令 `thawUid`）、`Effector.thawAll()`（命令 `thawAll` / `resetCgroup`） |
| `before` / `after` | 都是 null（解冻没有「设成什么值」） |
| `ok` | `thawUid` 的布尔结果；`thawAll` 是三个变体里任意一个落地即 true（`GreezeFacade.thawAll`） |
| 逆操作 | 无。想冻回去得知道原来的冻结方式（delay/mask），那不是一行账能表达的（`Journal.kt:17-19`） |
| 能否还原 | 不能，也不需要：解冻不制造新状态 |
| 失败/不可还原表现 | 静默跳过（`when` 分支 `EffectOp.THAW, EffectOp.REVERT -> Unit`），不进 `skipped`，不污染汇总 |

### 1.3 `NET_RESTRICT`（`wire="net"`，`reversible=true`）

| 项 | 内容 |
|---|---|
| 谁触发 | `Effector.netRestrict()`（`Effector.kt:96`）← `HookInstaller.applyResources()` 在**强制冻结前**调用 |
| `before` | **恒 null**，故意留空：`udpPackageRestrict` 没有 getter，读不到旧值（代码注释 `// No getter exists for this one`） |
| `after` | `"true"` / `"false"` |
| `ok` | `callUnit` 是否真的落进反射调用（void，函数名 `udpPackageRestrict`） |
| 逆操作 | 仅当 `after == "true"` 时调 `setUdpRestrict(uid, false)`，即「放开」 |
| 能否还原 | **只能放开，不能恢复原值**。原本开着的恢复不了，原本关着的也无从判断 |
| 失败/不可还原表现 | `after=="false"` → `skipped += "网络本就是放开的（uid N）"`；调用失败 → `skipped += "网络放开失败（uid N）"` |

⚠️ 连带后果：把 UI 里的「切断网络」显式设成**关**，`applyResources` 照样会调 `setUdpRestrict(uid,false)`（`engine.resource("net", pkg)?.let{...}` 只判非 null），也就是说这一下可能**放开系统原本的限制**，而账本记不到原值。见 [UX_GUIDE.md](UX_GUIDE.md) 反直觉点 5。

### 1.4 `JOB_RESTRICT`（`wire="job"`，`reversible=false`）

| 项 | 内容 |
|---|---|
| 谁触发 | `Effector.jobRestrict()`（`Effector.kt:112`）← `applyResources`，且仅当资源开关为 `true` |
| `before` | null（`isJobRestrict` 有 getter，但这里**没读**——读了也没有解除接口） |
| `after` | 常量 `"true"` |
| `ok` | `callUnit("jobRestrict", uid)` 是否落地 |
| 逆操作 | **没有**。`jobRestrict(int)` 是 void setter，GreeZe 没提供「解除 Job 限制」的方法 |
| 能否还原 | 不能 |
| 失败/不可还原表现 | 一定进 `skipped`：`"Job 限制没有解除接口（重启后由系统重建）"`；同时它出现在还原面板的「无法撤回」清单里（`reversible=false`） |

### 1.5 `WAKELOCK_LIMIT`（`wire="wakelock"`，`reversible=true`）

| 项 | 内容 |
|---|---|
| 谁触发 | `Effector.wakelockLimit()`（`Effector.kt:126`）← `applyResources` |
| `before` | `facade.isWakeLockLimit(uid)?.toString()` → `"true"`/`"false"`；getter 不存在时 **null** |
| `after` | `"true"` / `"false"` |
| `ok` | `callUnit("updateWakeLockLimit", uid, limit)`；这是契约里的 orphan 方法（LIMITS §3-4） |
| 逆操作 | `setWakeLockLimit(uid, entry.before.toBooleanStrictOrNull())`（解析不出就跳过） |
| 能否还原 | 能，前提是 `before` 读到了 |
| 失败/不可还原表现 | `before` 为 null → `"Wakelock 原值未知（uid N）"`；写回失败 → `"Wakelock 还原失败（uid N）"` |

### 1.6 `FREEZE_BINDER`（`wire="freezeBinder"`，`reversible=true`）

| 项 | 内容 |
|---|---|
| 谁触发 | `Effector.freezeBinder()`（`Effector.kt:141`）← `global.applyFreezeBinder`（策略加载/revision 变化时 `applyGlobalSideEffects`）或命令 `setFreezeBinder` |
| `before` | `facade.isFreezeBinderEnabled()?.toString()`（getter 名 `getFreezeBinderEnable`）；缺失时 null |
| `after` | `"true"` / `"false"` |
| `ok` | `callUnit("setFreezeBinderEnableCloud", enabled)` |
| 逆操作 | `setFreezeBinderEnabled(before)`（同样用 `toBooleanStrictOrNull()` 解析 `before`） |
| 能否还原 | 能，前提是 `before` 读到了。这是**全局**开关，不是逐 App |
| 失败/不可还原表现 | `"freezeBinder 原值未知"` / `"freezeBinder 还原失败"` |

### 1.7 `LIST_PATCH`（`wire="listPatch"`，`reversible=true`）

| 项 | 内容 |
|---|---|
| 谁触发 | `Effector.patchList()`（`Effector.kt:154`）← `ModuleController.applyLists()`（`global.patchLists=true` 时随 revision 自动跑，或命令 `applyLists`） |
| `target` | 字段名：`mNoRestrictAppSet`（never 集合）/ `mGrabTicketsAppList`（immediate 集合） |
| `before` | 本次**删掉**的包名，逗号连接（通常为空串） |
| `after` | 本次**加入**的包名，逗号连接 |
| `note` | `add=N remove=M` |
| `ok` | `facade.patchListField(...)` 是否成功（字段不存在 / 类型不是 Mutable 集合 → false） |
| 逆操作 | `patchListField(target, removed, added)`——把加进去的删掉、把删掉的加回来 |
| 能否还原 | 能，但只在**同一进程生命周期内**有意义：字段是内存态，重启即恢复原样，且云控会重新下发（LIMITS §2-9） |
| 失败/不可还原表现 | 两边都空 → `"名单改动为空（字段名）"`；调用失败 → `"名单还原失败（字段名）"` |

### 1.8 `QUICK_MODE`（`wire="quickMode"`，`reversible=false`）

| 项 | 内容 |
|---|---|
| 谁触发 | `Effector.quickMode()`（`Effector.kt:168`）← 命令 `quickMode`（首页的 GreeZe 模式 chip） |
| `before` | null（不是状态，是一次触发） |
| `after` | mode 字符串（如 `quick_freeze`） |
| `ok` | `callUnit("triggerQuickFreezeMode", mode, uid, reason)` |
| 逆操作 | **没有**。触发本身不构成可持续反做的状态 |
| 能否还原 | 不能 |
| 失败/不可还原表现 | 一定进 `skipped`：`"模式触发没有逆操作（mode=xxx）"` |

### 1.9 `REVERT`（`wire="revert"`，`reversible=false`）

| 项 | 内容 |
|---|---|
| 谁触发 | `Effector.revertAll()` 自己在收尾时写一条（`Effector.kt:276-281`） |
| `target` / `after` | `"revertAll"` / `"done=N skipped=M"` |
| `note` | 还原摘要前 300 字（含每条 skipped 原文） |
| 逆操作 | 无（它不是改动，是「做过一次还原」这件事的证据） |
| 能否还原 | 不适用 |
| 失败/不可还原表现 | 它自己不会被再还原：下一轮 `revertAll()` 碰到它走 `Unit`，不产生新行 |

---

## 2. 汇总矩阵

| op | 有反做动作 | 需要 `before` | 实际能撤的条件 | 撤不掉时的文案（原文） |
|---|---|---|---|---|
| `FREEZE` | 是（解冻） | 否 | `ok` | `解冻失败 uid N` |
| `THAW` | 否 | — | — | （静默，不提示） |
| `NET_RESTRICT` | 是（放开） | 否（恒 null） | `after=="true"` 且调用成功 | `网络放开失败（uid N）` / `网络本就是放开的（uid N）` |
| `JOB_RESTRICT` | 否 | — | — | `Job 限制没有解除接口（重启后由系统重建）` |
| `WAKELOCK_LIMIT` | 是（写回） | 是 | `before` 可解析且写回成功 | `Wakelock 原值未知（uid N）` / `Wakelock 还原失败（uid N）` |
| `FREEZE_BINDER` | 是（写回） | 是 | 同上 | `freezeBinder 原值未知` / `freezeBinder 还原失败` |
| `LIST_PATCH` | 是（反向交换） | 是（`after`/`before` 两列） | 至少一边非空且调用成功 | `名单改动为空（字段）` / `名单还原失败（字段）` |
| `QUICK_MODE` | 否 | — | — | `模式触发没有逆操作（mode=xxx）` |
| `REVERT` | 否 | — | — | （静默，不提示） |

---

## 3. 还原流程（逐步，可对着日志核）

| 步 | 发生什么 | 代码 |
|---|---|---|
| 1 | 用户在底部面板点「确认还原」 | `ui/pages/RevertSheet.kt:108-113` |
| 2 | **先**把 `global.enabled` 写成 `false`（下一次策略写入，`revision` +1） | `ui/HyperGreezeApp.kt:175-182`（`vm.setGlobal { copy(enabled=false) }`） |
| 3 | **再**发命令 `OP_REVERT_ALL`（`seq = System.currentTimeMillis()`） | `MainViewModel.revertAll()` → `Settings.System[greeze_ctl_command_v1]` |
| 4 | hook 侧读到命令、解码、`seq <= lastCommandSeq` 则丢弃 | `ModuleController.processCommand()`（`ModuleController.kt:305-327`） |
| 5 | 进 `execute(OP_REVERT_ALL)` → `Effector.revertAll()` | `ModuleController.kt:400` |
| 6 | `suppressed = true`、记下当时 revision | `Effector.kt:189-190` |
| 7 | **先** `facade.thawAll()`（把没记账的自动冻结一起收掉） | `Effector.kt:192` |
| 8 | 账本快照 `filter{ it.ok }` 后 `asReversed()`，从最新往回按 op 分派 | `Effector.kt:188、200-266` |
| 9 | `clearJournal()`（写 `"[]"`）→ 再写一条 `REVERT` 行 → `flushJournal()` | `Effector.kt:268、276-282` |
| 10 | 记一条 `info … revert` 事件并落盘 | `Effector.kt:283-288` |
| 11 | 命令收尾：写 `cmd:revertAll` 事件 → `publishHeartbeat()`，心跳 `.suppressed=true`、`.journal=0` | `ModuleController.kt:318-326`、`273-301` |
| 12 | 策略保留：`apps` 一条没删，唯一改掉的全局位是 `enabled=false` | `PolicyCodec.encodeGlobal` |
| 13 | 想恢复：把总开关打开。这本身就是一次策略写入 → `revision` 变化 → `suppressed` 解除 | `Effector.onPolicyRevision()`（`Effector.kt:34-36`）← `ModuleController.applyGlobalSideEffects` |

### 3.1 `suppressed` 到底挡什么

| 被挡 | 不被挡 |
|---|---|
| `netRestrict` / `jobRestrict` / `wakelockLimit` / `freezeBinder` / `patchList`（这五个函数第一行 `if (suppressed) return`） | `freeze()` / `thaw()` / `quickMode()`（**没有** suppressed 判断） |

所以「还原后不再自动施加」是**两条腿**：`suppressed` 挡住资源类再施加，`enabled=false` 挡住每条 hook body（`if (passive || !engine.enabled) return original`）。只靠 `suppressed` 不够。

### 3.2 一个真实的时序坑

UI 侧顺序是「先写策略、再发命令」，但两者是**不同的 Settings 键**，hook 侧不保证按这个顺序观察。最坏情况：命令先被执行（`suppressedAtRevision = R`），随后策略写入让 revision 变成 `R+1` → `onPolicyRevision` 立刻把 `suppressed` 解掉。此时唯一拦住再施加的是 `global.enabled=false`；如果用户紧接着又把总开关打开，模块会立刻恢复施加——这是设计意图，不是 bug，但排障时要按这个顺序想。

---

## 4. 卸载残留对照表

| 残留物 | 卸载后是否残留 | 怎么清 |
|---|---|---|
| hook（`HookInstaller.ORDER` 里的落点 + 构造器 hook） | **当前进程留到重启**；重启后不再注入（APK 已不在，LSPosed 不加载） | 重启；或在 LSPosed 取消「系统框架」作用域后重启 |
| 内存名单 `mNoRestrictAppSet` / `mGrabTicketsAppList` | **不残留**（`patchListField` 直接改 GreeZe 进程内的字段） | 重启即消失；未重启前没有「清空名单」的命令，只能重启 |
| 已施加的资源限制（net / job / wakelock） | **残留到重启**（写进了 GreeZe 的内部状态） | 「还原改动」尽量反做（net 只能放开、wakelock 写回 `before`）；job 只能等重启 |
| `freezeBinder` 全局开关 | **未确证**（`setFreezeBinderEnableCloud` 的持久化语义没上机） | 「还原改动」用 `getFreezeBinderEnable` 的旧值写回；读不到旧值就跳过并明说 |
| 我方 9 个 `Settings.System` 键（`Keys.ALL`） | **永久残留** | 「彻底清理」= `clearAllData()` 逐键 `putString(null)`；或手动 `settings delete` |
| `persist.sys.hypergreeze.guard` / `.safe` / `.safe_reason` | **永久残留** | 「彻底清理」清零/清空（`ModuleController.clearAllData`） |
| 冻结态本身（`cgroup.freeze=1`） | 取决于 GreeZe 是否自行解冻（未确证） | 还原第一步的 `thawAll()`；或手动下发 `thawAll` |

### 4.1 「彻底清理」不是永久干净（重要）

`OP_CLEAR_DATA` = `revertAll()` + `clearAllData()`（`ModuleController.kt:402-405`）。`clearAllData()` 删 `Keys.ALL` 的 9 个键并清零 3 个属性——但 hook 还在跑，紧接着：

| 键 | 清理后会不会回来 | 为什么 |
|---|---|---|
| `greeze_ctl_heartbeat_v1` | **立刻回来** | `processCommand()` 收尾就是 `publishHeartbeat()`（`ModuleController.kt:326`） |
| `greeze_ctl_events_v1` | **5 s 内回来** | 命令回执写了一条事件，`eventsDirty=true`，下一次 tick 落盘 |
| `greeze_ctl_logs_v1` | 15 s 节流到点后回来 | `flushLogs` 的 `LOG_FLUSH_INTERVAL_MS` |
| `greeze_ctl_journal_v1` | 不会（除非又有新改动） | `clearJournal` 已把 `journalDirty` 置 false |
| `greeze_ctl_policy_v1` / `greeze_ctl_command_v1` | 不会（除非用户再动 UI） | 没有自动重写路径 |

结论：**「彻底清理」的正确用法是「清理 → 卸载 → 重启」**。想立刻验证干净，用 adb 逐键 `settings delete`；否则下一次心跳就把键写回来了。

---

## 5. 手动核验（adb）

```bash
# 1) 读账本原文（一条 JSON 数组，字段名被压短：s/ts/op/t/u/p/b/a/ok/r/n）
adb shell settings get system greeze_ctl_journal_v1
adb shell settings get system greeze_ctl_journal_v1 | jq -c '.[] | {seq:.s,op,t:.t,uid:.u,ok,b:.b,a:.a}'

# 2) 心跳里的两个字段（journalUndoable / suppressed）
adb shell settings get system greeze_ctl_heartbeat_v1 | jq '{journal,suppressed,revision,lastSeq}'

# 3) 直接发还原命令（等价于点按钮；seq 必须大于心跳里的 lastSeq）
SEQ=$(date +%s%3N)
adb shell settings put system greeze_ctl_command_v1 "{\"seq\":$SEQ,\"op\":\"revertAll\"}"
sleep 3

# 4) 还原结果：账本应只剩一条 revert 行，journal 归零，事件里有 cmd:revertAll
adb shell settings get system greeze_ctl_journal_v1 | jq 'length, .[0]'
adb shell settings get system greeze_ctl_events_v1 | jq '.[]|select(.d=="cmd:revertAll" or .res=="revert")'

# 5) 删账本键，验证「模块不会崩」（ROADMAP P0 第 4 条验收）
adb shell settings delete system greeze_ctl_journal_v1
adb shell pidof system_server
adb shell settings get system greeze_ctl_journal_v1
```

第 5 条的确切语义：`loadExistingJournal()` 只在拿到 `Context` 时读一次；删键只删**持久化副本**，内存账本还活着。若此后再没有任何新改动（`journalDirty=false`），`flushJournal()` 会提前返回，键保持删除状态；一旦有新的资源改动，下一次 5 s tick 会把整份账本写回去。

### 5.1 读不到的东西（别在文档里假装能读）

| 想核验 | adb 能直读吗 | 原因 / 替代 |
|---|---|---|
| `isWakeLockLimit(uid)` | **不能** | 它是 GreeZe 的实例方法，只有 system_server 内通过反射可调；模块没把它发布到心跳。替代：账本该行的 `b` 字段；`dumpsys power` 前后对比 |
| `getFreezeBinderEnable()` | **不能** | 同上（`GreezeFacade.isFreezeBinderEnabled`）。替代：账本 `b`；`dumpsys greeze \| grep -i binder`（字段名未确证） |
| `isJobRestrict(uid)` | **不能** | 有包装但零调用点（LIMITS §2-10）。替代：`dumpsys jobscheduler` |
| 真实 per-uid 冻结态 | **不能从模块读** | 心跳没有 per-uid 字段（LIMITS §2-1）。替代：`cat /sys/fs/cgroup/uid_<UID>/cgroup.freeze` |
| GreeZe 侧限制状态 | 间接 | `adb shell dumpsys greeze`（服务名是 `greeze`，见 `GreezeFacade.SERVICE_NAME`；`dumpsys greezed` 不是这个名字） |

---

## 6. 不变量（改代码时别破坏）

| 不变量 | 代码 | 说明 |
|---|---|---|
| 容量 200 | `Module.JOURNAL_RING = 200`（`Keys.kt:68`）+ `JournalRing(Module.JOURNAL_RING)`（`ModuleTelemetry.kt:44`） | 超了丢最旧（`items.removeAt(0)`），不报警。容量是「UI 能往上翻多少行」，不是「能撤多少件事」 |
| `clear()` 不重置 `seq` | `JournalRing.clear()`（`Journal.kt:283-285`） | 计数器不占空间；重号会让「这一行做过没有」失去意义 |
| `seq` 恢复后继续单调 | `JournalRing.add()` 的 `entry.seq > 0` 分支 + `if (seq >= nextSeq) nextSeq = seq + 1` | 从磁盘读回的旧账保住原号，新行接着往下数（`JournalTest.restoring a persisted ledger keeps the original seq numbers` 有断言） |
| `seq` 的去重在**命令**通道 | `ModuleController.processCommand()` 的 `if (command.seq <= lastCommandSeq) return` | 账本的 `seq` 不做去重，它只保证单调；真正的「同一条命令重复投递只执行一次」在命令侧 |
| `decode` 只认数组 | `JournalCodec.decode()` 用 `JSONArray(raw)`，其余进 catch → 空表 | `{}`、`"just a string"`、`[1,2,3]`、`null` 全部解成空表，不抛（`JournalTest.decode never throws and returns empty on garbage`） |
| 脏 op 只丢一条 | `EffectOp.fromWire` 精确匹配 + `decodeEntry` 返回 null | `{"op":"NET"}`/`{"op":"teleport"}` 被丢，同文档里合法的行照活 |
| 洪泛保护 | `FLOOD=1000` → 只留最后 `KEEP_ON_FLOOD=200` | 解码**不做**容量截断：300 条仍解 300 条（容量是 ring 的事） |
| 失败行留在账本 | `record(ok=false)` 照样 `add`，但 `undoableCount` 不算 | 否则「我明明点过」没有证据 |
| `ok` 默认 true | `JournalCodec.okFlag()` 用两参 `optBoolean("ok", true)` | 读不懂的 `ok` 不能误记成「调用失败」 |
| 别拿字符串比账本 | `JournalCodec` 用 `org.json.JSONObject`（HashMap，字段顺序不保证） | 要比就比解码后的结构 |
| 「清空」= 剩一条 `revert` | `clearJournal()` 后 `record(REVERT)` + `flushJournal()` | 所以还原之后 `jq 'length'` 是 `1`、`.journal` 是 `0`，不是「键被删了」 |

---

## 7. 与 ROADMAP P0 验收的对应

| ROADMAP 验收项（`ROADMAP.md:44-47`） | 怎么算过 | 现状 |
|---|---|---|
| 改 5 个应用的资源开关 → 账本 5 条 → 还原 → 账本清空、一条 revert 事件、getter 回原值 | 5 个 App 都设 `immediate` 且各开一项资源，逐个触发强制冻结；账本每行一个 `(uid, 资源)` 组合 | 代码就绪；**每条资源只在「强制冻结前 + 同一 revision 每 uid 一次」应用**（`HookInstaller.applyResources` 的 `resourceAppliedRev` 门控），所以「5 条」需要 5 次真实触发，不是 5 次点击 |
| `jobRestrict` 如实标记不可还原 + 给原因 | 还原摘要里出现「Job 限制没有解除接口（重启后由系统重建）」，同时在面板的「无法撤回」清单里 | 就绪 |
| 还原后 30 秒内不再自动重新施加 | `heartbeat.suppressed=true` 且 30 s 内 `journal` 保持 0、资源行不再新增 | 就绪（`suppressed` + `enabled=false` 两条腿，见 §3.1） |
| `settings delete system greeze_ctl_journal_v1` 后不崩 | `system_server` pid 不变、心跳继续更新、UI 账本显示为空 | 就绪（`decode(null)` = 空表），但见 §5 第 5 条的「写回」语义 |
