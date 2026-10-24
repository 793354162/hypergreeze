# 使用指南（UX_GUIDE）

一句话：**先看「这个应用现在会怎样」，再决定动哪个开关；改完不确定就去看事件页，出问题就用首页的「还原」。**
边界：本文只讲「怎么设、设了会发生什么」。做不到的部分在 [LIMITS.md](LIMITS.md)，撤回能力在 [REVERSIBILITY.md](REVERSIBILITY.md)，性能在 [PERFORMANCE.md](PERFORMANCE.md)。

---

## 1. 决策指南（最重要的一张表）

「我想要 …」→ 怎么设。「等价策略」是该 App 在 `greeze_ctl_policy_v1` 的 `apps.<包名>` 下的那一项（不是整份文档；整份是 `{"v":2,"apps":{"<包名>":{…}}}`），方便用 adb 复现。

| 我想要 | 怎么设（UI 路径） | 等价策略 | 代价 / 坑 |
|---|---|---|---|
| 微信不想被冻 | 应用 → 微信 → 冻结模式 = **永不冻结** | `{"mode":"never"}` | 它会把 GreeZe 的白名单判定也翻成「是」（`PolicyEngine.whitelistVerdict`），所以此时再配 drop 位没意义；推送**不会**因此变快；`freezePids` 这条入口没 hook |
| 微信通话/视频要通 | 详情 → 模式 = **立即冻结** → 专家 → 场景掩码 → **音频播放 = 保持**、**相机 = 保持** | `{"mode":"immediate","keep":16777218}` | keep 只在 GreeZe 真的把 AUDIO/CAMERA 算进 reason 时生效；`keep` 赢过 `drop` |
| 某个 App 一退后台就冻 | 详情 → 模式 = **立即冻结** → 「退后台多久后冻结」拉到 `0 ms`；确认首页「退后台立即冻结」是开的（默认开） | `{"mode":"immediate","delayMs":0}` | 关掉那个全局开关后，`immediate` 只剩掩码效果，**不会**主动下发冻结 |
| 只让某个子进程例外 | 专家 → 子进程例外 → 输入完整进程名（如 `com.x:push`）→ 「添加为『永不冻结』」 | `{"processes":{"com.x:push":"never"}}` | 只有 `freezeProcess(RunningProcess,…)` 这条路径带进程名；子进程 `immediate` 与子进程掩码覆盖都不生效（LIMITS §2-5） |
| 更省电 | 立即冻结 + 掩码把**白名单**设为 drop + 资源联动打开 切断网络 / 限制 JobScheduler / 限制 Wakelock | `{"mode":"immediate","drop":64,"resources":{"net":true,"job":true,"wakelock":true}}` | 三项的撤回能力不同：net 只能放开、job 没有逆接口、wakelock 可写回原值（见 REVERSIBILITY §2） |
| 完全关掉模块 | 首页 → **总开关**关 | `{"global":{"enabled":false}}` | hook 仍装着（诊断页照常显示），每个判定都放行；要连加载都停，去 LSPosed 取消「系统框架」作用域 |
| 撤销一切 | 首页 → 「还原模块做过的改动」→ 确认 | 命令 `revertAll` | 会**顺手把总开关关掉**（策略保留）；想再开就是打开总开关 |
| 卸载前清理 | 首页 → 「彻底清理（卸载前）」→ 确认 | 命令 `clearData` | 策略配置一起删；清理后心跳/事件会被写回来，正确顺序是**清理 → 卸载 → 重启**（REVERSIBILITY §4.1） |
| 白名单里的 App 也想冻 | 专家 → 场景掩码 → **白名单 = 丢弃** | `{"mode":"immediate","drop":64}` | 前提是该 ROM 走 `isNoRestrictApp` / `isTempWhite`——诊断页看这两个 hook 的 `hits` 有没有涨 |
| 亮屏就解冻 | 专家 → 解冻条件 → **亮屏解冻 = 开** | `{"thawOn":{"screenOn":true}}` | 只有 `screenOn` 与 `externalWake` 真接了线；另外 4 个开关存了不生效（LIMITS §2-6） |
| 冻结但别断网 | 专家 → 资源联动 → **切断网络 = 关** | `{"resources":{"net":false}}` | 「关」不等于「不动」：代码会真的调 `udpPackageRestrict(uid,false)` 把网络放开，而且账本记不到原值（反直觉点 5） |
| 想先知道「会不会被冻」 | 现在没有入口：详情页顶部只有后果句，`Explain.filterPreview` 已实现但页面没接 | — | 试算函数与单测都在（`core/Explain.kt`、`ExplainTest`），但**不能**当成已上线的功能讲 |

> 三个「一次就够、但容易点错」的按钮：首页的「全部解冻」只发 `thawAll`；详情页的「立即冻结」是**一次性命令**；详情页的「恢复为跟随系统」只删这一条策略，不撤销已施加的资源限制。

整份策略文档的形状与一次可复现的写入（把上面「等价策略」那一项塞进 `apps.<包名>` 即可）：

```bash
adb shell settings put system greeze_ctl_policy_v1 \
  '{"v":2,"global":{"enabled":true,"forceFreezeOnBackground":true},"apps":{"com.tencent.mm":{"mode":"immediate","keep":16777218,"drop":64}}}'
sleep 5
adb shell settings get system greeze_ctl_heartbeat_v1 | jq '{revision,enabled}'
```

---

## 2. 两档界面

开关存在 `global.uiExpert`（`core/GlobalPolicy.kt:37`，默认 `false`），随策略一起写进 `greeze_ctl_policy_v1`（`PolicyCodec.encodeGlobal` 里有 `uiExpert`），所以**跨重启保留**。它是全局开关，不是逐 App。

| 位置 | 简单模式（`uiExpert=false`） | 专家模式（`uiExpert=true`） |
|---|---|---|
| 详情页顶部「这个应用现在会怎样」 | 有（`Explain.appLines`，最多 6 行） | 同左 |
| 冻结模式 | 四态选择器 + 一句说明 | 同左 |
| 时机 | **只有一个**「退后台多久后冻结」滑杆（0–3000 ms，步长 100） | 「时机」分组：冻结延时 + **反抖动冷却**（0–60000 ms，步长 1000） |
| 场景掩码（32 位） | 不显示 | 显示，进底部面板逐位三态切换 |
| 子进程例外 | 不显示 | 显示，按完整进程名登记 |
| 资源联动（6 项） | 不显示 | 显示，每项三态（跟随/开/关）+ 一句后果 |
| 解冻条件（6 项） | 不显示 | 显示，每项三态 + 一句后果 |
| 底部动作（立即冻结/解冻该应用/恢复为跟随系统） | 有 | 有 |
| 「专家选项」开关 | 有（三个动作按钮上方） | 有 |

两点容易误解：

- **简单/专家只影响详情页**。首页的全局开关、GreeZe 模式 chip、还原入口，以及应用页/事件页/诊断页，两档完全一样（`HomePage` / `AppsPage` 都不读 `uiExpert`）。
- `UiState.expertMode` 这个字段没人用；真正决定显示的是 `AppDetailPage` 里读的 `state.policy.global.uiExpert`。

---

## 3. 术语表（说后果，不背参数）

| 术语 | 一句话 | 说人话的后果 |
|---|---|---|
| **follow**（跟随系统） | 模块对这个 App 完全不表态 | 行为和装模块前一模一样，这是所有 App 的默认值 |
| **never**（永不冻结） | 拒绝带 uid 的冻结请求，并把白名单判定改成「是」 | 后台不会被冻；但进程没跑就是没跑，推送不会因此变快 |
| **immediate**（立即冻结） | 退到后台、延时到点后**由模块主动**下发冻结 | 后台很快停；回前台靠系统自己解冻 |
| **custom**（自定义） | 只执行你显式打开的位/开关，不继承任何默认 | 没打开的都不生效，包括你以为「默认应该有」的那些 |
| **keep**（保持） | 命中这个原因位时强制豁免 | 给「正在用」留证据：通话、相机、音乐 |
| **drop**（丢弃） | 即使命中这个原因位也照冻 | 主要用途是压过 GreeZe 自己的白名单 |
| **reason 位** | GreeZe 判定时给出的 32 位场景掩码 | 它是「GreeZe 认为现在正在发生什么」，不是你的设置；值对不上就看事件页的 `reason=` |
| **LOCKED_DROP** | 不允许丢的 6 个位：音频播放 / 相机 / 前台 TOP / 输入法 / 可见窗口 / 马达 | 丢掉它们等于「前台用着也会被冻」，所以 UI 直接锁死、手改 JSON 也会被清位（反直觉点 2） |
| **binder 冻结** | GreeZe 的**全局**开关：冻结进程时把它的 Binder 调用代为应答 | 一个开关管全机，不是逐 App；改完要能改回来靠的是改之前的 getter 读数 |
| **部分冻结** | GreeZe 的 `PARTIALLY_FROZEN` 状态（只冻了部分进程） | 当前 UI 不显示真实冻结态，看到「未知」是设计而不是故障 |
| **账本** | 模块每次外部改动的流水，最多 200 条 | 它是「还原」的唯一依据，也是卸载残留的证据 |
| **安全模式** | 钩子照装，但每个判定都放行（被动） | 出问题时的保命档；从 UI 请求是**下次开机**生效 |
| **抑制（suppressed）** | 还原之后给「资源/名单/binder 再施加」按下的暂停键 | 它**不拦**冻结与模式触发；真正让模块停下的是总开关被关掉 |

---

## 4. 每个页面一张表

### 4.1 首页

| 项 | 内容 |
|---|---|
| 你在这页能做什么 | 看「当前效果」一句话摘要；看钩子 `x/y`、已配置数、永不冻结数、事件数、Hook 状态、GreeZe 实例、心跳年龄；改 4 个全局开关（总开关 / 退后台立即冻结 / 记录事件 / 补写名单）；看默认冻结延时与 `revision`；「全部解冻」；「请求安全模式」；点 GreeZe 模式 chip；进「还原」与「彻底清理」；看最近 6 条事件 |
| 最容易误解的地方 | ① 「钩子 0/8」通常不是没装，是**心跳还没到**（`hookAlive` 要求心跳 < 120 s）；分母是这一 ROM 上登记到的全部落点（含构造器捕获），不同机器数字不同，别拿固定 8 当判据。② 「全部解冻」≠「还原」：它只发 `thawAll`，不反做资源/名单/binder，也不清账本。③ 「安全模式」按钮写的是**请求标志**，下次开机才生效；要立刻停手请关总开关。④ 「补写 GreeZe 名单」默认关，开了也会被云控盖回去。⑤ 摘要里的数字是**策略意图**，不是实测状态 |

### 4.2 应用

| 项 | 内容 |
|---|---|
| 你在这页能做什么 | 按应用名/包名搜索；4 个筛选：全部 / 已配置 / 系统应用 / 用户应用；每行看图标、名称、`包名 · uid`、当前模式标签；点进详情 |
| 最容易误解的地方 | ① 行上显示的是 `mode.label`（**策略意图**），不是「现在冻没冻」。② 「已配置」用的是 `AppPolicy.isEffective`：只要 mode 不是 follow、或有掩码位、或有子进程覆盖，就算已配置——所以「跟随系统 + 只加了一个 keep 位」也会出现在这一栏。③ 没有批量设置，只能一个个改。④ 图标是空白圆底只说明图标没取到，不代表应用有问题 |

### 4.3 详情

| 项 | 内容 |
|---|---|
| 你在这页能做什么 | 看顶部「这个应用现在会怎样」（最多 6 行，超出会显示「其余 N 项设置展开可看」）；选四态；调时机；（专家）改场景掩码 / 子进程 / 资源联动 / 解冻条件；**三个动作按钮上方**有「专家选项」开关；底部三个动作：立即冻结 / 解冻该应用 / 恢复为跟随系统 |
| 最容易误解的地方 | ① 「立即冻结」是**一次性命令**（`freezePkg`），不会把模式改成「立即冻结」。② 「解冻该应用」用的是应用列表里的 uid：列表还没加载完时点了**没有任何反应**，也不会报错。③ 「恢复为跟随系统」只删掉这条策略，**不会**撤销已经施加到系统上的资源限制——那要用首页的「还原」。④ 掩码面板里锁定位点不动是设计。⑤ 子进程例外只在 `freezeProcess` 路径生效 |

### 4.4 事件

| 项 | 内容 |
|---|---|
| 你在这页能做什么 | 4 个筛选：全部 / 冻结 / 跳过 / 错误；清空事件；逐条看时间、类型、包/uid、`reason`、结果串、`detail` |
| 最容易误解的地方 | ① 事件是**环形**的：默认 60 条（`compactEvents` 时 20 条），而且**跨重启保留**（`loadExistingEvents`）；「清空」是显式动作。② 「错误」筛的是 `type=error` 的事件；hook body 内部异常只进诊断页的 `errors` 计数与日志，不会自动变成一条 error 事件。③ 事件是「判定/命令」的流水，不是改动清单——要查改动去账本（首页的还原入口） |

### 4.5 诊断

| 项 | 内容 |
|---|---|
| 你在这页能做什么 | 看环境（模块版本、框架与 API、进程、安全模式、守卫计数、`revision`、构建指纹）；看每个 hook 的 `state` / `hits` / `note`；看能力探测 `caps`；看 `WRITE_SETTINGS` 状态、跑写入自检、打开 filter 极性反转、刷新；导出诊断报告、补写名单、请求/解除安全模式；看最近 24 行日志 |
| 最容易误解的地方 | ① 「写入自检」会往 `greeze_ctl_safe_mode_reason_v1` 写一个 `hypergreeze-probe-…` 串，**会覆盖**上次安全模式的原因（LIMITS §5.4）。② 「解除安全模式」是立即的（清属性 + 把 `passive` 复位），「请求安全模式」是下次开机。③ 被动模式下 `state` 仍然是 `installed`、`note` 写 `safe mode: passive`，这是刻意的（能解释自己为什么被动）。④ `hits` 只增不减，重启归零 |

---

## 5. 反直觉点（诚实清单）

### 5.1 为什么「永不冻结」不保证推送及时

冻结的本质是 `cgroup` 把整个进程停住，FCM / MiPush 的 handler 根本没有机会执行——这是物理互斥，不是配置问题（LIMITS §1 第 3 条）。模块能做的是**事后解冻**，不是「冻着还能收」。另外 `never` 拦的是带 uid 的入口（`filter` / `freezeUid` / `freezeUids` / `freezeProcess`）；`freezePids(int[])` 没有 hook，所以它不是数学意义上的「所有冻结请求」。

### 5.2 为什么 `drop AUDIO` 被禁止

把「音频播放」这一位丢掉，意味着 GreeZe 认为你正在通话/放音乐时也允许冻结——后台音频会断。所以它进了 `LOCKED_DROP`（`SceneBit.LOCKED_DROP`：音频/相机/TOP/输入法/可见窗口/马达），并且是**三层清位**：解码期 `PolicyCodec.stripLocked`、UI 回写时 `MainViewModel.updateApp` 再按一次、`AppPolicy.withMask` 第三次；掩码面板里那一位直接锁成不可选。

### 5.3 为什么冻结徽章有时是「未知」

心跳里**没有** per-uid 冻结字段（`Heartbeat` 只有 `hooks` + `caps`），而 UI 是普通 App，拿不到 GreeZe 的 Binder。所以 `MainViewModel.freezeStateOf()` 只敢对 `never` 说「未冻结」，其余一律 `UNKNOWN`；而且这个函数与 `FreezeBadge` 组件目前**都没接到页面上**——界面上真正渲染的徽章文本是 `mode.label`（策略意图），不是冻结态。看到「未知」是因为我们只肯说能证明的话。

### 5.4 为什么改了策略要等一下才生效

四条路径时延完全不同：

| 路径 | 时延 | 依据 |
|---|---|---|
| 策略内容观察者 | 毫秒级（取决于 provider 有没有发通知） | `PolicyStore.start()` 注册 `ContentObserver` |
| 5 s tick 轮询（兜底） | ≤ 5 s | `ModuleController.TICK_MS` |
| 心跳里的 `revision` | ≤ 30 s | 每 6 个 tick 发一次心跳 |
| UI 自己的轮询 | 2.5 s | `MainViewModel.onStart()` |
| **资源开关** | 更慢：只在「强制冻结前」应用，且同一 `revision` 每个 uid 只应用一次 | `HookInstaller.applyResources()` 的 `resourceAppliedRev` 门控 |

所以「改完立刻看诊断页数字」很可能还是旧值；资源开关要等**下一次强制冻结**才落地。

### 5.5 为什么「资源联动 = 关」不等于「什么都不做」

`applyResources()` 对非 null 的一律调 setter：`net=false` 会真的调 `udpPackageRestrict(uid,false)`（把网络放开），`wakelock=false` 会调 `updateWakeLockLimit(uid,false)`。这与 `ResourceSwitches` 的类注释「never relaxes a system-side restriction」不完全一致，**以代码为准**。只有 `job` 是「仅 true 才调」。三态的「跟随系统」才是真的不动。

### 5.6 为什么「彻底清理」之后设置键又出现了

清理只删**当时**那 9 个键；紧接着 `processCommand()` 收尾会发布一次心跳、落盘一次命令回执事件，日志也会在 15 s 节流到点后写回。这不是清理失败，是模块还活着。正确姿势见 REVERSIBILITY §4.1。

---

## 6. 与 ROADMAP P1 验收的对应

| ROADMAP P1 验收项（`ROADMAP.md:64-68`） | 现状 | 怎么验 |
|---|---|---|
| 新用户不进「专家」也能完成：设一个 never、设一个 immediate、全部解冻 | 满足：never/immediate 都在详情页的模式选择器里；「全部解冻」在首页 | 装好后只点这三处，全程不碰「专家选项」 |
| 每个掩码位旁有中文后果说明，`LOCKED_DROP` 明确写「不允许」 | **部分满足**：位名是中文 + 面板顶部有一段总说明 + 锁定位不可选；但逐位的后果句（`Explain.bitEffect`）**没有 UI 入口** | 进掩码面板看锁定态与顶部说明；逐位后果句目前只能在代码/单测里读 |
| 试算结果与 `filterVerdict` 逐条一致（单测覆盖） | **部分满足**：`PolicyEngineTest` 与 `ExplainTest` 各自钉同一张语义表，但没有一个差分测试把两者直接对比；也没有 UI 入口 | 跑 `:app:testReleaseUnitTest`，看两个测试类是否都绿 |

> 这三条里，「没有入口」的两项是**未接线**，不是没实现。文档不把它们写成已完成。
