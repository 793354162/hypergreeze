# HyperGreeze 真机验收清单

> 对象：已实现完成的 LSPosed 模块 `dev.hypergreeze`（libxposed **api 102**，`versionName 1.0.0`）。
> 目标机型：带 GreeZe（`com.miui.server.greeze.*`）的 HyperOS，Android 12+（`minSdk = 30`）。
> 用法：按阶段顺序执行，逐条打勾。每条都给了「操作 / 期望 / 失败判定 / 取证」，取证命令可直接粘。
> 约定：`<PKG>` 用被测包名，`<UID>` 用该包 uid；`shell` 权限读不到 `cgroup.freeze` 时，命令前加 `su -c`。

---

## 判定口径（先统一，再看结果）

| 代号 | 失败类别 | 特征 | 首先查什么 |
|---|---|---|---|
| **F1** | 能力缺失 | 心跳 `hooks[].state = missing`，`caps` 里对应键为 `false` | 该 ROM 是否真有这个类/方法（`GreezeProbe` 的 probe 日志） |
| **F2** | 模块未接管 | logcat 没有 `HyperGreeze` 行，或心跳 `ts` 长时间不更新 | LSPosed 作用域、模块是否启用、`scope.list` 是否进包 |
| **F3** | 策略未生效 | 钩子全 `installed`、`hits` 在涨，但目标行为没变 | 策略 JSON 是否被解码（心跳 `revision`）、`enabled`、`mode`、掩码值 |

同一现象先按 F1→F2→F3 顺序排掉，避免把「类没找到」误判成「策略写错」。

---

## 阶段 0：环境与清场基线

- [ ] **0.1 构建产物**
  - 操作：`./gradlew :app:assembleRelease`（`release` 用仓库内 `signingConfigs.release`，见 `app/build.gradle.kts:52-58`）
  - 期望：产出 `app/build/outputs/apk/release/app-release.apk`
  - 失败判定：签名缺失或变体名不符 → 换 `:app:assembleDebug`（`debug` 也挂同一签名，`applicationIdSuffix = ""`）
  - 取证：
    ```bash
    ls -l app/build/outputs/apk/release/
    ```

- [ ] **0.2 安装 + 授权 appop**
  - 操作：
    ```bash
    adb install -r app/build/outputs/apk/release/app-release.apk
    adb shell appops set dev.hypergreeze android:write_settings allow
    adb shell appops get dev.hypergreeze android:write_settings
    ```
  - 期望：`android:write_settings: allow`
  - 失败判定：返回 `default`/`deny` → 写入策略全部失败，事件环永远空（F3 的假象）
  - 取证：同上；UI 诊断页「写入自检」应显示写入与读回一致

- [ ] **0.3 清场（每次重跑验收前都做）**
  - 操作：
    ```bash
    adb shell settings delete system greeze_ctl_policy_v1
    adb shell settings delete system greeze_ctl_command_v1
    adb shell settings delete system greeze_ctl_events_v1
    adb shell settings delete system greeze_ctl_heartbeat_v1
    adb shell setprop persist.sys.hypergreeze.guard 0
    adb shell setprop persist.sys.hypergreeze.safe 0
    ```
  - 期望：`settings get` 返回 `null`；两个属性读回 `0`
  - 失败判定：删除被拒 → 记录机型/ROM，后续用「写空策略 `{}`」代替删除
  - 取证：
    ```bash
    adb shell settings get system greeze_ctl_policy_v1
    adb shell getprop persist.sys.hypergreeze.guard
    ```

---

## 阶段 1：安装与作用域

- [ ] **1.1 APK 里三件套齐全**
  - 操作：解包 APK 看 `META-INF/xposed/`
    ```bash
    unzip -l app/build/outputs/apk/release/app-release.apk | grep META-INF/xposed
    unzip -p app/build/outputs/apk/release/app-release.apk META-INF/xposed/java_init.list
    unzip -p app/build/outputs/apk/release/app-release.apk META-INF/xposed/scope.list
    unzip -p app/build/outputs/apk/release/app-release.apk META-INF/xposed/module.prop
    ```
    Windows 无 `unzip` 时：`tar -tf app-release.apk | Select-String META-INF/xposed`
  - 期望：三行条目 `java_init.list` / `module.prop` / `scope.list`；内容分别为
    - `java_init.list` → `dev.hypergreeze.module.HyperGreezeModule`
    - `scope.list` → `system`
    - `module.prop` → `minApiVersion=102`、`targetApiVersion=102`、`staticScope=true`、`exceptionMode=protective`
  - 失败判定：条目缺失 → 打包配置被改过（源文件在 `app/src/main/resources/META-INF/xposed/`）；`scope.list` 不是 `system` → 作用域错，F2
  - 取证：上面四条命令的输出存档

- [ ] **1.2 LSPosed 只勾「系统框架」**
  - 操作：LSPosed Manager → 模块 → HyperGreeze → 启用；作用域只勾「系统框架 / System Framework」
  - 期望：启用开关为开；作用域列表只有一项
  - 失败判定：未勾系统框架 → 阶段 2 全部 `missing`（F2）；勾了普通应用 → 无害，但会在那些进程里多打一条 `not system_server -> detach()`
  - 取证：
    ```bash
    adb shell logcat -d -s HyperGreeze | head -5
    ```
    出现 `not system_server -> detach()` 说明该进程被误纳入作用域

- [ ] **1.3 重启使其生效**
  - 操作：`adb reboot`，等 `sys.boot_completed=1`
  - 期望：`adb shell getprop sys.boot_completed` 返回 `1`，且设备正常进入桌面
  - 失败判定：卡开机动画 / 重启循环 → 立刻执行阶段 9 的逃生 9.2 与 9.3
  - 取证：
    ```bash
    adb wait-for-device
    adb shell getprop sys.boot_completed
    adb shell uptime
    ```

---

## 阶段 2：启动自检

- [ ] **2.1 logcat 关键行齐全**
  - 操作：
    ```bash
    adb logcat -c
    adb reboot
    adb wait-for-device
    # 等 60~120 s 后再抓
    adb logcat -d -s HyperGreeze | tail -40
    ```
  - 期望（按出现顺序，至少包含）：
    | 行 | 含义 |
    |---|---|
    | `onModuleLoaded process=android systemServer=true api=102 module=1.0.0 framework=…` | 入口活着，API 对得上 |
    | `onSystemServerStarting; classLoader=…` | 进入 `system_server` 引导阶段 |
    | `GreeZe probe: manager=… filter=… immobulus=… listApps=… methods=N fields=M` | 反射探测完成 |
    | `hooks installed=8/8 passive=false guard=1` | 8 个钩子全装、非被动、守卫计数 1 |
    | `context acquired after N attempts` | 拿到 `Context`（最多重试 20×1.5 s） |
    | `restored heartbeat: lastCommandSeq=<seq> revision=<rev>` | 水位/版本恢复 |
    | `stable window reached; boot guard cleared` | 90 s 稳定窗口通过，守卫清零 |
  - 失败判定：
    - 没有 `onSystemServerStarting` → F2（作用域/启用）
    - 有 `GreeZe classes not found on this ROM; HyperGreeze stays inert` → F1，本机型不适用本模块
    - `hooks installed=<n>/8` 且 n<8 → F1，对照 2.3 找出具体缺失项
  - 取证：`adb logcat -d -s HyperGreeze > logx.txt`

- [ ] **2.2 心跳存在且新鲜**
  - 操作：
    ```bash
    adb shell settings get system greeze_ctl_heartbeat_v1
    ```
  - 期望：返回 JSON；`ts` 与当前时间差 < 120 s；`proc` = `system_server`；`greeze` = `true`；`instance` = `true`；`api` = `102`
  - 失败判定：`null` 或 `ts` 落后 → F2；`instance=false` → 单例没捕获到（看 `greezeClass` 与 `ServiceManager` 路径日志），强制冻结/命令大概率返回 `fail`
  - 取证：
    ```bash
    adb shell settings get system greeze_ctl_heartbeat_v1 | jq '{ts,api,greeze,instance,revision,lastSeq,safeMode,bootGuard}'
    ```

- [ ] **2.3 诊断页 8 个 hook 全部 installed**
  - 操作：UI → 「诊断」页，看钩子列表；或直接解心跳
    ```bash
    adb shell settings get system greeze_ctl_heartbeat_v1 \
      | jq -r '.hooks[] | "\(.id)\t\(.state)\thits=\(.hits)\terr=\(.errors)\t\(.note // "")"'
    ```
  - 期望：以下 8 个 `id` 都是 `installed`，且首页统计显示 `钩子 8/8`
    | id | 目标 |
    |---|---|
    | `capture-init` | `GreezeManagerService.init(Context)` |
    | `filter` | `AurogonFilterManager.filter(int,String,int)` |
    | `no-restrict` | `AurogonImmobulusMode.isNoRestrictApp(String)` |
    | `wakeup-list` | `AurogonImmobulusMode.isAllowWakeUpList(String)` |
    | `temp-white` | `GreezeManagerService.isTempWhite(String)` |
    | `freeze-uid` | `GreezeManagerService.freezeUid(int,long,int,String,int)` |
    | `foreground` | `GreezeManagerService$13.onForegroundActivitiesChanged(int,int,boolean)` |
    | `screen-on-thaw` | `GreezeManagerService.needThawWhenScreenOn(int)` |
  - 失败判定：某项 `missing` 且 `note` 里有 `target missing` → 该 ROM 方法名/参数个数不同（F1）。注意 `capture-ctor-N` 也会出现在数组里，它**不计入** `installedHooks/8`；缺失它只影响单例捕获。
  - 取证：上面那条 `jq`

- [ ] **2.4 `hits` 在涨**
  - 操作：读一次心跳 → 正常用机 60 s（切前台/后台几次、亮灭屏各一次）→ 再读一次
    ```bash
    adb shell settings get system greeze_ctl_heartbeat_v1 > hb1.json
    # ……用机 60 s……
    adb shell settings get system greeze_ctl_heartbeat_v1 > hb2.json
    jq -r '.hooks[]|"\(.id) \(.hits)"' hb1.json; echo ---; jq -r '.hooks[]|"\(.id) \(.hits)"' hb2.json
    ```
  - 期望：`filter`、`foreground`、`screen-on-thaw` 至少各涨一次（`no-restrict`/`temp-white`/`wakeup-list` 取决于系统调用频率）；`errors` 保持 0
  - 失败判定：所有 `hits` 都不动 → 钩子是装上了但目标方法根本没被调用（F1/F3 之间，先看 `errors` 与阶段 3 的对照实验）；`errors` 持续增长 → hook 体内部异常，抓 `logcat -s HyperGreeze` 里的 `body failed`
  - 取证：`hb1.json` / `hb2.json` 存档

- [ ] **2.5 能力表 `caps` 与机型一致**
  - 操作：
    ```bash
    adb shell settings get system greeze_ctl_heartbeat_v1 | jq '.caps'
    ```
  - 期望：18 个键齐全（`manager` 恒 `true`）；`filter`/`immobulus`/`freezeUid`/`foreground`/`thawAll` 在本机型应为 `true`
  - 失败判定：`manager` 为 `false` 且心跳仍存在 → 逻辑矛盾，记录并上报；`resources=false` → 三个资源 setter 一个都不存在，阶段 6 整段可跳过
  - 取证：同上

- [ ] **2.6 初始状态干净**
  - 操作：读心跳的 `safeMode` / `bootGuard` / `revision`
  - 期望：`safeMode=false`、`bootGuard=1`（首次启动）、`revision=0`（未写策略）
  - 失败判定：`safeMode=true` 且没手动开过 → 查 `getprop persist.sys.hypergreeze.guard` 与 `safe_reason`
  - 取证：
    ```bash
    adb shell getprop persist.sys.hypergreeze.guard
    adb shell getprop persist.sys.hypergreeze.safe
    adb shell getprop persist.sys.hypergreeze.safe_reason
    ```

---

## 阶段 3：策略生效（单 App → `never`）

- [ ] **3.1 写入 `never` 策略**
  - 操作（二选一）：
    - UI：应用页 → `<PKG>` → 模式选「永不冻结」
    - adb：
      ```bash
      adb shell settings put system greeze_ctl_policy_v1 \
        '{"v":2,"apps":{"<PKG>":{"mode":"never"}}}'
      ```
  - 期望：30 s 内（tick）或立刻（observer）心跳 `revision` 变化；事件页出现 `info … policy-reload (rev=N)`
  - 失败判定：`revision` 一直是 0 → 写入没落地（appop）或字符串被 shell 改写（F3）
  - 取证：
    ```bash
    adb shell settings get system greeze_ctl_policy_v1
    adb shell settings get system greeze_ctl_heartbeat_v1 | jq '.revision'
    adb shell settings get system greeze_ctl_events_v1 | jq '.[] | select(.d=="policy-reload" or (.d|startswith("policy")))'
    ```

- [ ] **3.2 取 uid 与 cgroup 基线**
  - 操作：
    ```bash
    adb shell dumpsys package <PKG> | grep -m1 userId=
    UID=$(adb shell dumpsys package <PKG> | grep -m1 userId= | tr -dc '0-9')
    adb shell cat /sys/fs/cgroup/uid_$UID/cgroup.freeze
    ```
  - 期望：读到 `0`（未冻结）
  - 失败判定：路径不存在 → 该机型 cgroup v2 布局不同，改用 `adb shell cat /sys/fs/cgroup/uid_$UID/cgroup.freeze` 的等价路径或 `dumpsys greeze` 判定
  - 取证：把 `dumpsys greeze | head -60` 一起存档做基线

- [ ] **3.3 制造一次「系统想冻结」的机会**
  - 操作：
    1. 打开 `<PKG>`，停留 10 s；
    2. 回桌面（`adb shell input keyevent KEYCODE_HOME`）；
    3. 灭屏静置 5~10 min（GreeZe 的冻结判定与机型策略相关，时间不够就看不到尝试）；
    4. 亮屏后立刻取证
  - 期望：`<PKG>` 的 `cgroup.freeze` 仍为 `0`；事件环出现 `skip uid=<UID> … reason=… -> SKIP_BY_MOD (never-freeze policy)`
  - 失败判定：
    - `cgroup.freeze=1` 且 logcat 有 `freeze uid=<UID>` → 策略没命中（F3），回到 3.1 检查 `mode` 与 `revision`
    - 完全没有 `skip` 事件，但也**没有**冻结 → 这次 GreeZe 压根没尝试，结论无效，重跑 3.3（用 3.4 的对照证明 GreeZe 确实会冻）
  - 取证：
    ```bash
    adb shell cat /sys/fs/cgroup/uid_$UID/cgroup.freeze
    adb shell settings get system greeze_ctl_events_v1 | jq '.[] | select(.pkg=="<PKG>")'
    adb logcat -d | grep -E "freeze|SKIP_BY_MOD|uid=$UID" | tail -30
    ```

- [ ] **3.4 对照组：未配置的 App 仍被冻结**
  - 操作：挑一个行为相似的 `<PKG_B>`（同样打开→回桌面→灭屏静置），且没有为它配置任何策略
  - 期望：`<PKG_B>` 的 `cgroup.freeze` 变 `1`，说明 GreeZe 在本次实验条件下确实会冻结
  - 失败判定：`<PKG_B>` 也没被冻 → 实验条件不成立，3.3 的结论作废（回到 3.3 延长静置时间）
  - 取证：
    ```bash
    UID_B=$(adb shell dumpsys package <PKG_B> | grep -m1 userId= | tr -dc '0-9')
    adb shell cat /sys/fs/cgroup/uid_$UID_B/cgroup.freeze
    ```

- [ ] **3.5 只影响该 uid**
  - 操作：3.3 与 3.4 同批次完成后，对比两个 uid 的 `cgroup.freeze` 与事件环
  - 期望：`<PKG>` = `0`、`<PKG_B>` = `1`；事件环里 `freeze` 事件的 `uid` 不含 `<UID>`
  - 失败判定：`<PKG>` 被冻 → 见 3.3；`<PKG_B>` 也没冻 → 见 3.4；其他无关 uid 出现异常事件 → 记 F1（uid 归因）
  - 取证：两条 cgroup 读取 + 事件环全量存档

---

## 阶段 4：立即冻结时延（目标 ≤ 1 s）

- [ ] **4.1 `delayMs = 0`**
  - 操作：写入策略后跑测量脚本
    ```bash
    adb shell settings put system greeze_ctl_policy_v1 \
      '{"v":2,"apps":{"<PKG>":{"mode":"immediate","delayMs":0}}}'
    sleep 5
    UID=$(adb shell dumpsys package <PKG> | grep -m1 userId= | tr -dc '0-9')
    adb shell am start -n <PKG>/<LAUNCHER_ACTIVITY> >/dev/null; sleep 3
    START=$(date +%s%3N); adb shell input keyevent KEYCODE_HOME >/dev/null
    while :; do
      V=$(adb shell cat /sys/fs/cgroup/uid_$UID/cgroup.freeze | tr -d '\r')
      [ "$V" = "1" ] && { echo "frozen after $(( $(date +%s%3N) - START )) ms"; break; }
      [ $(( $(date +%s%3N) - START )) -gt 5000 ] && { echo "TIMEOUT >5000 ms"; break; }
    done
    ```
  - 期望：`frozen after ≤1000 ms`（每次 `adb shell` 往返约 50~150 ms，判据按 1 s 上限）
  - 失败判定：`TIMEOUT` → 先看事件环有没有 `freeze … (forced pid=N)`；没有则看 logcat `foreground callback: uid is argX`（uid 归因未定）与 `forced freeze … result=`（GreeZe 侧挡住了）
  - 取证：
    ```bash
    adb shell settings get system greeze_ctl_events_v1 | jq '.[] | select(.d!=null and (.d|startswith("forced")))'
    adb logcat -d -s HyperGreeze | grep -E "forced freeze|foreground callback|uid is arg"
    ```

- [ ] **4.2 `delayMs = 500`**
  - 操作：同 4.1，把 `delayMs` 改成 `500`
  - 期望：`frozen after` 落在 `500~1500 ms`
  - 失败判定：小于 500 → `delayMs` 没生效（检查策略是否被解码，`revision` 是否变化）；仍然超时 → 同 4.1
  - 取证：同上；另外可用事件环 `ts` 与「按下 HOME 的本机时刻」求差，排除 adb 往返开销

- [ ] **4.3 记录实测分布**
  - 操作：4.1 / 4.2 各重复 5 次
  - 期望：给出两组的 min/中位/max
  - 失败判定：任一组的 max > 3000 ms → 记为「时延不达标」，同时记录 ROM 版本与是否省电模式
  - 取证：把 10 次输出贴进验收记录

---

## 阶段 5：掩码位（微信：keep AUDIO/CAMERA，drop NO_RESTRICT）

- [ ] **5.1 写入掩码**
  - 操作：
    ```bash
    adb shell settings put system greeze_ctl_policy_v1 \
      '{"v":2,"apps":{"com.tencent.mm":{"mode":"immediate","keep":16777218,"drop":64}}}'
    ```
    （`16777218 = AUDIO(2) + CAMERA(16777216)`，`64 = NO_RESTRICT`）
  - 期望：UI 应用详情页底部「当前掩码」显示 `keep=0x1000002 drop=0x40`
  - 失败判定：显示的掩码与写入不一致 → 解码问题（F3）
  - 取证：
    ```bash
    adb shell settings get system greeze_ctl_policy_v1
    ```

- [ ] **5.2 `LOCKED_DROP` 三层清位**
  - 操作：手写一份把 6 个锁定位全塞进 `drop` 的 JSON，再分别看 Settings 原值与 UI 显示
    ```bash
    adb shell settings put system greeze_ctl_policy_v1 \
      '{"v":2,"apps":{"com.tencent.mm":{"mode":"custom","drop":16783906}}}'
    adb shell settings get system greeze_ctl_policy_v1      # 原样保留手写值
    # 然后在 UI 里改任一开关（触发一次 App 侧编码回写），再看：
    adb shell settings get system greeze_ctl_policy_v1      # drop 里的锁定位已被清掉
    ```
  - 期望：UI「当前掩码」中 `drop` 不含 `AUDIO/VISIBLE/TOP/IME/VIBRATOR/CAMERA`（`0x01001A22` 这几位）；App 回写后的 JSON 里 `drop` 只剩未锁定部分（本例为 0，字段整体消失）
  - 失败判定：UI 仍显示锁定位 → `stripLocked` 未生效（F1/F3）；App 回写后仍带锁定位 → `withMask`/`updateApp` 的两次清位被绕过
  - 取证：
    ```bash
    adb shell settings get system greeze_ctl_policy_v1 | jq '.apps["com.tencent.mm"]'
    ```

- [ ] **5.3 通话不被冻结（keep AUDIO）**
  - 操作：微信语音通话 ≥ 2 min；通话中切到别的 App 让微信退后台，保持在后台 ≥ 60 s
  - 期望：通话不中断、音频连续；`cgroup.freeze` 保持 `0`；事件环若出现该 uid，应为 `skip … reason=2`
  - 失败判定：通话被掐断 / 音频掉 → keep 未命中（F3）：核对 `keep` 是否含 `2`、通话期间事件环里 `reason` 的实际值
  - 取证：
    ```bash
    adb shell cat /sys/fs/cgroup/uid_$UID/cgroup.freeze
    adb shell settings get system greeze_ctl_events_v1 | jq '.[] | select(.pkg=="com.tencent.mm")'
    adb logcat -d | grep -E "reason=2|AUDIO" | tail -20
    ```

- [ ] **5.4 相机使用中不被冻结（keep CAMERA + VISIBLE/TOP）**
  - 操作：微信内拍照 / 视频通话（前置或后置），保持 60 s；期间让微信短暂退到后台再回来
  - 期望：取景不黑、不闪断；`cgroup.freeze=0`
  - 失败判定：取景中断 → 同上；另注意 `CAMERA` 属 `LOCKED_DROP`，若有人手工把该位塞进 `drop`，解码期就会被清掉，不会造成此现象
  - 取证：同 5.3，`reason` 里关注 `16777216 / 32 / 512`

- [ ] **5.5 `drop NO_RESTRICT` 生效**
  - 操作：保持 5.1 的策略，走一遍 3.3 的「回后台 + 灭屏静置」
  - 期望：若该机型 GreeZe 默认把微信放进白名单（`isNoRestrictApp` 返回 `true`），加了 `drop=64` 后白名单判定改判 `false`，于是模块的强制冻结能落下：事件环出现 `freeze uid=… pkg=com.tencent.mm … (forced …)`，`cgroup.freeze=1`
  - 失败判定：白名单判定没被翻转 → 看 `no-restrict` / `temp-white` 两个钩子的 `hits` 是否增长；不增长说明该机型走的是别的判定函数（F1）
  - 取证：
    ```bash
    adb shell settings get system greeze_ctl_heartbeat_v1 | jq '.hooks[]|select(.id=="no-restrict" or .id=="temp-white")'
    adb shell settings get system greeze_ctl_events_v1 | jq '.[] | select(.t=="freeze")'
    ```

---

## 阶段 6：资源联动（net / job / wakelock 各一次）

> 前提：心跳 `.caps.resources == true`；三个 setter 只在**强制冻结前**被调用一次（同一 `revision` + 同一 uid 只应用一次，见 `HookInstaller.kt:420-428`）。因此**每验证一个开关都要先改策略让 `revision` 变化**。

- [ ] **6.1 `net`**
  - 操作：
    ```bash
    adb shell settings put system greeze_ctl_policy_v1 \
      '{"v":2,"apps":{"<PKG>":{"mode":"immediate","resources":{"net":true}}}}'
    # 走一遍 4.1 的「打开→HOME」，触发强制冻结
    ```
  - 期望：logcat 无 `invoke udpPackageRestrict failed`；GreeZe 侧该 uid 的网络限制标志变化（`dumpsys greeze` 里该 uid 的 restrict 项）
  - 失败判定：出现 `invoke … failed` → 该方法在本 ROM 不存在（F1），`caps.resources` 仍可能为 `true`（四个方法有其一即真）
  - 取证：
    ```bash
    adb logcat -d -s HyperGreeze | grep -E "invoke|failed" | tail -20
    adb shell dumpsys greeze | grep -i -A3 "restrict" | head -40
    ```

- [ ] **6.2 `job`**
  - 操作：策略改成 `"resources":{"job":true}`（`revision` 变），再触发一次强制冻结
  - 期望：无 `invoke jobRestrict failed`；`dumpsys jobscheduler` 里该包被标记受限
  - 失败判定：`invoke failed` → F1；无任何变化 → 该方法返回 void 也算「调用落地」，需结合 jobscheduler 侧状态一起判断，单看返回值不够
  - 取证：
    ```bash
    adb shell dumpsys jobscheduler | grep -i -B2 -A6 "<PKG>"
    ```

- [ ] **6.3 `wakelock`**
  - 操作：策略改成 `"resources":{"wakelock":true}`（`revision` 变），再触发一次强制冻结
  - 期望：无 `invoke updateWakeLockLimit failed`；`dumpsys power` 里该 uid 的 wakelock 限制项变化
  - 失败判定：`invoke failed` → F1（`updateWakeLockLimit` 在部分 ROM 是签名漂移的 orphan 方法）
  - 取证：
    ```bash
    adb shell dumpsys power | grep -i -E "wakelock|wake lock" | head -30
    ```

- [ ] **6.4 只应用一次的行为被确认**
  - 操作：保持同一 `revision`，连续触发两次强制冻结
  - 期望：第二次不再重复调用 setter（日志里只有第一轮出现相关痕迹）——这是设计，不是缺陷
  - 失败判定：把「第二次没有调用」误判为失败 → 记录为「符合设计」；若第一次都没调用 → 回 6.1
  - 取证：`adb logcat -d -s HyperGreeze | grep -c invoke`

---

## 阶段 7：反抖动（连续 fg/bg 100 次）

- [ ] **7.1 前置：设冷却**
  - 操作：
    ```bash
    adb shell settings put system greeze_ctl_policy_v1 \
      '{"v":2,"apps":{"<PKG>":{"mode":"immediate","thawCooldownMs":5000}}}'
    ```
  - 期望：`revision` 变化，UI 详情页显示 `5000 ms`
  - 失败判定：`5000` 被截断成 0 → 解码问题
  - 取证：UI 截图 + `settings get`

- [ ] **7.2 100 次循环**
  - 操作：
    ```bash
    for i in $(seq 1 100); do
      adb shell am start -n <PKG>/<LAUNCHER_ACTIVITY> >/dev/null 2>&1
      adb shell input keyevent KEYCODE_HOME >/dev/null 2>&1
    done
    ```
  - 期望：全程无重启、无 ANR 弹窗；`system_server` 的 pid 不变
  - 失败判定：pid 变化 / `uptime` 归零 / 出现 `FATAL EXCEPTION IN SYSTEM PROCESS` → 立即逃生（阶段 9）
  - 取证：
    ```bash
    adb shell pidof system_server
    adb shell uptime
    adb logcat -b crash -d | tail -40
    ```

- [ ] **7.3 事件不刷屏**
  - 操作：
    ```bash
    adb shell settings get system greeze_ctl_events_v1 | jq 'length'
    adb shell settings get system greeze_ctl_events_v1 \
      | jq '[.[] | select(.t=="freeze")] | length'
    adb shell settings get system greeze_ctl_events_v1 \
      | jq '[.[] | select(.res=="cooldown")] | length'
    ```
  - 期望：总数 ≤ 60（环形上限）；`freeze` 数量远小于 100；出现若干 `res = "cooldown"` 的 `skip`
  - 失败判定：`freeze` ≈ 100 且无 `cooldown` → 冷却未生效（F3，检查 `thawCooldownMs` 是否真的落到策略里）；总数 > 60 → 环形上限失效
  - 取证：上面三条 + `jq '.[0:5]'` 看样本

- [ ] **7.4 冷却语义抽查**
  - 操作：单次实验——HOME 后 1 s 内再切回再 HOME，重复 3 次
  - 期望：只有第一次产生 `freeze`，其余是 `skip … -> cooldown (forced)`
  - 失败判定：三次都 `freeze` → 冷却窗口按「上次尝试时间」比较（`SystemClock.elapsedRealtime()`），若时间基准异常会失效；记录并上报
  - 取证：
    ```bash
    adb shell settings get system greeze_ctl_events_v1 | jq '.[] | select(.d=="forced")'
    ```

---

## 阶段 8：命令通道

> 三个场景：全部解冻、立即冻结、安全模式。全部用可复现的 adb 写法；UI 按钮等价。
> 前提：`appops` 已 allow；`seq` 用当前毫秒时间戳，且必须**大于**心跳里的 `lastSeq`。

- [ ] **8.1 全部解冻（`thawAll`）**
  - 操作：
    ```bash
    SEQ=$(date +%s%3N)
    adb shell settings put system greeze_ctl_command_v1 "{\"seq\":$SEQ,\"op\":\"thawAll\"}"
    sleep 3
    adb shell settings get system greeze_ctl_heartbeat_v1 | jq '.lastSeq'
    adb shell settings get system greeze_ctl_events_v1 | jq '.[] | select(.d=="cmd:thawAll")'
    ```
  - 期望：`lastSeq == SEQ`；事件 `info … -> ok (cmd:thawAll)`；各 uid 的 `cgroup.freeze` 归 `0`
  - 失败判定：
    - 完全没有事件 → `seq ≤ lastSeq`（时钟回拨 / 时区），或 telemetry 未就绪（看 `context acquired` 日志）
    - 事件是 `fail` → 三个 `thawAll` 变体都没落地（`caps.thawAll` 为 `false` 时必现）
  - 取证：上面两条 `jq` + 若干 `cgroup.freeze`

- [ ] **8.2 立即冻结（`freezePkg`）**
  - 操作：
    ```bash
    SEQ=$(date +%s%3N)
    adb shell settings put system greeze_ctl_command_v1 \
      "{\"seq\":$SEQ,\"op\":\"freezePkg\",\"pkg\":\"<PKG>\"}"
    sleep 3
    adb shell settings get system greeze_ctl_events_v1 | jq '.[] | select(.d=="cmd:freezePkg")'
    adb shell cat /sys/fs/cgroup/uid_$UID/cgroup.freeze
    ```
  - 期望：事件 `info … -> OK (cmd:freezePkg)`（返回串取自 GreeZe，可能是 `OK`）；`cgroup.freeze` 变 `1`
  - 失败判定：`no-uid` → 包名拼错 / `Context` 未就绪；`no-greeze` → 门面未建立；`fail` → 反射调用没落地
  - 取证：同上 + `adb logcat -d -s HyperGreeze | grep "command freezePkg"`

- [ ] **8.3 安全模式（属性路径，UI 按钮当前只写 Settings 键）**
  - 操作：
    ```bash
    adb shell setprop persist.sys.hypergreeze.safe 1
    adb reboot
    adb wait-for-device; sleep 60
    adb shell settings get system greeze_ctl_heartbeat_v1 | jq '{safeMode,safeReason,bootGuard}'
    adb shell getprop persist.sys.hypergreeze.safe_reason
    ```
  - 期望：`safeMode=true`、`safeReason` 为「手动请求安全模式」、钩子 `note` 变成 `safe mode: passive`；此时策略写入不产生任何 `freeze`/`skip`
  - 失败判定：`safeMode=false` → 属性写失败或守卫未读到（`SysProp.available` 为假时整段守卫失效，日志会有 `guard unavailable` 之类痕迹）
  - 取证：上面两条 + `adb logcat -d -s HyperGreeze | grep -E "SAFE MODE|passive"`
  - 解除：
    ```bash
    SEQ=$(date +%s%3N)
    adb shell settings put system greeze_ctl_command_v1 "{\"seq\":$SEQ,\"op\":\"clearSafeMode\"}"
    adb shell setprop persist.sys.hypergreeze.safe 0
    adb shell setprop persist.sys.hypergreeze.guard 0
    ```

- [ ] **8.4 其余 7 个 op 抽测**
  - 操作：逐条替换 `op` 字段（参考 `POLICY_SCHEMA.md` §5.3）
    | op | 最小 JSON | 期望返回串 |
    |---|---|---|
    | `thawUid` | `{"seq":S,"op":"thawUid","uid":<UID>}` | `ok` / `fail` |
    | `freezeUid` | `{"seq":S,"op":"freezeUid","uid":<UID>}` | GreeZe 结果串 / `fail` |
    | `quickMode` | `{"seq":S,"op":"quickMode","arg":"immobulus"}` | `ok` / `fail` |
    | `resetCgroup` | `{"seq":S,"op":"resetCgroup"}` | `ok` / `fail`（行为等同 `thawAll`） |
    | `clearEvents` | `{"seq":S,"op":"clearEvents"}` | `ok`，事件环变 `[]` |
    | `applyLists` | `{"seq":S,"op":"applyLists"}` | `ok(never=N,immediate=M)` / `fail` / `no-policy` |
    | `setFreezeBinder` | `{"seq":S,"op":"setFreezeBinder","arg":"1"}` | `ok` / `fail` |
  - 期望：每条都在事件环留下 `detail = "cmd:<op>"`，且心跳 `lastSeq` 递增
  - 失败判定：`unknown-op` 出现即异常（解码阶段已挡掉未知 op，正常路径到不了这里）；`resetCgroup` 若观察到「没有清 cgroup 计数」属预期（当前实现等同 `thawAll`）
  - 取证：`adb shell settings get system greeze_ctl_events_v1 | jq '.[]|select(.d!=null and (.d|startswith("cmd:")))'`

- [ ] **8.5 水位去重**
  - 操作：把同一条命令（同 `seq`）重复写 3 次
  - 期望：只执行一次，事件环只有一条 `cmd:*`
  - 失败判定：出现 3 条 → 水位比较失效（`seq <= lastCommandSeq` 分支被绕过）
  - 取证：`jq '.[]|select(.d|startswith("cmd:"))|.ts'`

---

## 阶段 9：逃生演练（三条各真跑一次）

- [ ] **9.1 逃生一：删策略**
  - 操作：
    ```bash
    adb shell settings delete system greeze_ctl_policy_v1
    sleep 35
    adb shell settings get system greeze_ctl_heartbeat_v1 | jq '{revision,enabled}'
    adb shell settings get system greeze_ctl_events_v1 | jq '.[]|select(.d=="policy-reload")'
    ```
  - 期望：`revision = 0`；事件 `info … policy-reload (rev=0)`；钩子仍 `installed`（模块不卸载，只是全部直通）；目标 App 的冻结行为回到系统默认
  - 失败判定：`revision` 不变 → 观察者与 30 s 轮询双路径都失效（F2）；删除后仍被模块拦冻结 → 引擎仍持旧策略（缓存问题）
  - 回滚：重新 `settings put` 一份合法策略

- [ ] **9.2 逃生二：关 GreeZe 总闸**
  - 操作：
    ```bash
    adb shell setprop persist.sys.gz.enable false
    adb reboot
    ```
  - 期望：系统不再发起冻结；模块日志仍有 `hooks installed=8/8`，但没有新的 `freeze`/`skip` 事件
  - 失败判定：关机后仍有冻结 → 该属性在本 ROM 不是总闸（属机型差异，记录并改用 9.3）
  - 取证：
    ```bash
    adb shell getprop persist.sys.gz.enable
    adb shell settings get system greeze_ctl_heartbeat_v1 | jq '.hooks[]|{id,hits}'
    ```
  - 回滚：`adb shell setprop persist.sys.gz.enable true` + 重启
  - 注意：模块自身**从不修改**这个属性（`GlobalPolicy` 注释明确只做模块开关），所以这条逃生永远不会被模块撤销

- [ ] **9.3 逃生三：取消 LSPosed 作用域**
  - 操作：LSPosed Manager → 模块 → 作用域 → 取消「系统框架」→ 重启
  - 期望：`logcat -s HyperGreeze` 无任何新输出；心跳 `ts` 停在取消前，超过 120 s 后 UI 判为「模块未运行」
  - 失败判定：仍有 `HyperGreeze` 日志 → 作用域没真正生效（检查是否还勾着别的进程）
  - 取证：
    ```bash
    adb logcat -d -s HyperGreeze | tail -5
    adb shell settings get system greeze_ctl_heartbeat_v1 | jq '.ts'
    date +%s%3N
    ```
  - 回滚：重新勾选「系统框架」+ 重启

- [ ] **9.4 逃生演练记录**
  - 操作：把 9.1 / 9.2 / 9.3 的耗时（从执行命令到行为恢复）记进验收表
  - 期望：9.1 ≤ 35 s；9.2 / 9.3 = 一次重启的时间
  - 失败判定：任何一条需要超过一次重启 + 35 s 才能恢复 → 记为「逃生不达标」，后续版本必须修
  - 取证：三条各自的耗时 + 恢复前后的 `cgroup.freeze` 读数
    ```bash
    adb shell cat /sys/fs/cgroup/uid_$UID/cgroup.freeze
    ```

---

## 阶段 10：崩溃恢复（非法 JSON）

- [ ] **10.1 语法错误 → 整份回退**
  - 操作：
    ```bash
    adb shell settings put system greeze_ctl_policy_v1 '{"v":2,"apps":{"com.tencent.mm":'
    sleep 35
    adb shell settings get system greeze_ctl_heartbeat_v1 | jq '{revision,enabled}'
    adb shell pidof system_server
    ```
  - 期望：`settings get` 原样保留那段非法串；hook 侧解析失败 → `Policy.EMPTY`；心跳 `revision = 0`；`system_server` pid 不变；无崩溃
  - 失败判定：`system_server` 重启 / `FATAL EXCEPTION IN SYSTEM PROCESS` / 心跳消失 → 硬失败，立刻逃生
  - 取证：
    ```bash
    adb logcat -b crash -d | tail -40
    adb shell uptime
    adb shell settings get system greeze_ctl_events_v1 | jq '.[]|select(.d=="policy-reload")'
    ```
  - 恢复：重新写合法策略或 `settings delete`

- [ ] **10.2 字段类型错误 → 单字段降级**
  - 操作：
    ```bash
    adb shell settings put system greeze_ctl_policy_v1 \
      '{"v":2,"apps":{"com.tencent.mm":{"mode":"never","keep":"abc"},"<PKG>":{"mode":"never"}}}'
    sleep 35
    adb shell settings get system greeze_ctl_heartbeat_v1 | jq '.revision'
    ```
  - 期望：`mode:"never"` 仍生效（两个包都进 never）；`keep` 降级为 `0`；**不崩、不回退整份**——这是设计：只有整份 JSON 语法错误才回退 `EMPTY`
  - 失败判定：整份被回退（`revision=0` 且 never 不生效）→ 解码粒度错了，属缺陷；崩溃 → 硬失败
  - 取证：同上 + 3.3 的冻结对照

- [ ] **10.3 恢复后功能完整**
  - 操作：写回一份正常策略，重复 3.1 / 4.1 各一次
  - 期望：行为与阶段 3/4 完全一致
  - 失败判定：恢复后策略不生效 → 模块内部有残留状态（记录并上报）
  - 取证：同阶段 3/4

---

## 阶段 11：安全模式（连续未进稳定窗口 → 被动）

> **代码事实（以本文为准）**：`SafetyGuard.begin()` 先把计数 +1，再判 `next >= BOOT_GUARD_LIMIT(3)`（`module/SafetyGuard.kt:34-44`）。所以
> - 从 0 开始：第 3 次启动就会被动（`next = 3`）；
> - 已经连续失败 3 次（`guard = 3`）：**下一次启动**（`next = 4`）被动——这就是「第 4 次启动」的含义。

- [ ] **11.1 用属性模拟到「已失败 3 次」**
  - 操作：
    ```bash
    adb shell setprop persist.sys.hypergreeze.guard 3
    adb reboot
    adb wait-for-device; sleep 60
    ```
  - 期望：本次启动 `next = 4`，`4 >= 3` → 进入被动
  - 失败判定：`guard` 读回不是 3（属性没落盘）→ 改用 11.2 的「从 0 连续三次不进稳定窗口」路径
  - 取证：
    ```bash
    adb shell getprop persist.sys.hypergreeze.guard
    adb logcat -d -s HyperGreeze | grep -E "boot guard|SAFE MODE"
    ```

- [ ] **11.2 被动表现**
  - 操作：
    ```bash
    adb shell settings get system greeze_ctl_heartbeat_v1 | jq '{safeMode,safeReason,bootGuard}'
    adb shell settings get system greeze_ctl_heartbeat_v1 | jq -r '.hooks[]|"\(.id) \(.state) \(.note // "")"'
    ```
  - 期望：`safeMode=true`、`safeReason` 含「连续 4 次启动未进入稳定窗口」、`bootGuard=4`；每个钩子的 `note` 为 `safe mode: passive`；`state` 仍是 `installed`（刻意保留，便于诊断页解释原因）
  - 失败判定：`state` 变 `missing` → 判定为「被动时卸载钩子」，与设计不符；`safeMode=false` → 守卫没读到属性
  - 取证：上面两条 + 诊断页截图

- [ ] **11.3 被动下策略不生效**
  - 操作：写一份 `never` 策略，走 3.3 的流程
  - 期望：不出现 `skip … SKIP_BY_MOD`，也不出现模块发起的 `freeze … (forced …)`；冻结行为完全由系统决定
  - 失败判定：仍出现 `SKIP_BY_MOD` → `installer.passive` 没被设上（`bodyFreezeUid` 的前置判断），属缺陷
  - 取证：
    ```bash
    adb shell settings get system greeze_ctl_events_v1 | jq '[.[]|select(.res=="SKIP_BY_MOD" or .d=="forced")]'
    ```

- [ ] **11.4 被动不会自己解除（重要）**
  - 操作：什么都不做，等 5 min 以上，再读 `guard`
  - 期望：`guard` 保持 4，`safeMode` 保持 `true`——`markStable()` 在 `passive` 时直接 `return`（`SafetyGuard.kt:62-67`），90 s 稳定窗口不会清零
  - 失败判定：`guard` 自己变 0 → 与代码不符，属缺陷
  - 取证：
    ```bash
    adb shell getprop persist.sys.hypergreeze.guard
    adb shell settings get system greeze_ctl_heartbeat_v1 | jq '.safeMode'
    ```

- [ ] **11.5 解除被动**
  - 操作（两条都试一次）：
    ```bash
    # a) 命令通道
    SEQ=$(date +%s%3N)
    adb shell settings put system greeze_ctl_command_v1 "{\"seq\":$SEQ,\"op\":\"clearSafeMode\"}"
    sleep 3
    adb shell settings get system greeze_ctl_heartbeat_v1 | jq '{safeMode,bootGuard}'

    # b) 属性归零 + 重启
    adb shell setprop persist.sys.hypergreeze.safe 0
    adb shell setprop persist.sys.hypergreeze.guard 0
    adb reboot
    ```
  - 期望：a) 立即 `safeMode=false`、`bootGuard` 读回 0（`clearSafeMode()` 同时清属性）；b) 重启后 `safeMode=false`、`bootGuard=1`
  - 失败判定：a) 后仍被动 → `installer.passive` 没被复位；b) 后仍被动 → 属性写失败（检查 `SysProp.available`）
  - 取证：`adb logcat -d -s HyperGreeze | grep "safe mode cleared"`

---

## 阶段 12：回归收尾

- [ ] **12.1 事件/日志/心跳三件套都能读**
  - 操作：
    ```bash
    adb shell settings get system greeze_ctl_events_v1 | jq 'length'
    adb shell settings get system greeze_ctl_logs_v1 | jq 'length'
    adb shell settings get system greeze_ctl_heartbeat_v1 | jq '.ts'
    ```
  - 期望：事件 ≤ 60、日志 ≤ 120、心跳 `ts` 新鲜
  - 失败判定：日志为 `null` → `flushLogs` 15 s 节流还没到，等 20 s 再读
  - 取证：同上

- [ ] **12.2 诊断报告能导出**
  - 操作：诊断页点「诊断报告」
  - 期望：文本含模块版本、框架版本、`GreeZe: present/class/instance`、策略 `rev`、安全模式、每个钩子的 `state/hits/errors`、能力表、最近事件、最近日志
  - 失败判定：字段缺失 → `MainViewModel.diagnosticsReport()` 与心跳字段不一致（版本错配）
  - 取证：截图或复制文本

- [ ] **12.3 无副作用检查**
  - 操作：`adb shell dumpsys battery`、`adb shell dumpsys meminfo system` 对比启用前后
  - 期望：`system_server` 常驻内存增量在个位数 MB 量级；待机电流无肉眼可见劣化
  - 失败判定：明显劣化 → 记为「需长稳/功耗专项」（见附录 A）
  - 取证：
    ```bash
    adb shell dumpsys meminfo system | head -20
    adb shell dumpsys battery
    adb shell dumpsys batterystats --charged | tail -40
    ```

---

## 附录 A：已知不可自动化验证项

以下项目**必须人工判断**或需要外部条件，CI/脚本给不出结论：

| # | 项目 | 为什么自动化不了 | 建议做法 |
|---|---|---|---|
| 1 | 微信语音/视频通话是否真的不中断 | 需要真实对端与音频通路；`cgroup.freeze` 只能间接佐证 | 人工通话 ≥ 2 min，录屏 + 事件环同时取证 |
| 2 | 相机取景是否黑屏/闪断 | 视觉判断，且不同相机 HAL 表现不同 | 人工录像，同时抓 `cgroup.freeze` |
| 3 | 推送是否延迟（`thawOn.notification`） | 该字段当前无消费方（`POLICY_SCHEMA.md` §11），行为由 GreeZe 决定 | 只记录现象，不作为模块验收项 |
| 4 | 「是模块的 keep 位让它没冻」 vs 「它本来就不会冻」 | 系统日志里没有「因模块而跳过」的标记，只能靠事件环 + 对照 app 推断 | 必须配对照组（阶段 3.4） |
| 5 | 31 个已定义 reason 位（bit0–bit30）与真实场景的逐位对应 | `reason` 由 GreeZe 内部计算，不同 HyperOS 版本组合不同；只能黑盒逐位试 | 用事件环里 `reason=` 的实际值反推，写入机型差异表 |
| 6 | 云控下发覆盖（`MILLET_NO_RESTRICT_APP` 等） | 下发时机不可控；模块打在消费点，理论上不受影响 | 观察长周期（≥ 7 天）行为漂移 |
| 7 | 多用户 / 工作资料 / 分身 | 当前实现固定 user 0 语义（`POLICY_SCHEMA.md` §11 #14） | 需 second user 环境专项测试，单机给不出结论 |
| 8 | OTA 后的类名/方法漂移 | 需要一台真实 OTA 过的设备 | 每次 OTA 重跑阶段 2 的 probe 断言 |
| 9 | 长稳/功耗（≥ 24 h） | 时间成本；受使用习惯影响 | 独立长稳任务，记录 `dumpsys batterystats` |
| 10 | AMS `startProcessLocked` 与强制冻结的竞态 | 需要精确时序注入，脚本只能近似 | 阶段 7 的 100 次循环作为近似压力 |
| 11 | 通话中的音频焦点 / HAL 行为 | 依赖音频设备与对端 | 人工 + `dumpsys audio` 存档 |
| 12 | 低内存 / OOM 场景 | 需要构造内存压力 | 独立实验，观察 `system_server` 是否被 LMK |
| 13 | 温度/降频对 `delayMs` 实测值的影响 | 环境不可控 | 记录环境温度与充电状态 |
| 14 | LSPosed 框架版本差异 | API 102 假设，框架侧行为不归本模块 | 记录框架版本，横向对比至少两个版本 |
| 15 | 锁屏/指纹/人脸解锁路径下的解冻 | 需要真实解锁交互 | 人工操作，抓 `cgroup.freeze` 与事件环 |
| 16 | 双开（XSpace/分身）同一 uid 多包名归因 | 同一 uid 对应多个包名，`packageFor` 只取第一个 | 记录为已知歧义，专项验证 |

---

## 附录 B：一次性取证包

```bash
#!/usr/bin/env bash
# 用法: ./collect.sh <PKG>
set -u
PKG=${1:-com.tencent.mm}
OUT="hg-$(date +%Y%m%d-%H%M%S)"
mkdir -p "$OUT"
UID_=$(adb shell dumpsys package "$PKG" | grep -m1 userId= | tr -dc '0-9')

adb shell settings get system greeze_ctl_policy_v1    > "$OUT/policy.json"
adb shell settings get system greeze_ctl_heartbeat_v1 > "$OUT/heartbeat.json"
adb shell settings get system greeze_ctl_events_v1    > "$OUT/events.json"
adb shell settings get system greeze_ctl_logs_v1      > "$OUT/logs.json"
adb shell settings get system greeze_ctl_command_v1   > "$OUT/command.json"
adb shell dumpsys greeze                              > "$OUT/dumpsys-greeze.txt"
adb shell getprop | grep -E 'hypergreeze|persist\.sys\.gz' > "$OUT/props.txt"
adb shell cat "/sys/fs/cgroup/uid_${UID_}/cgroup.freeze" > "$OUT/cgroup-freeze.txt" 2>&1
adb logcat -d -s HyperGreeze                          > "$OUT/logx.txt"
adb logcat -d -b crash                                > "$OUT/crash.txt"
adb shell uptime                                      > "$OUT/uptime.txt"
adb shell pidof system_server                         > "$OUT/pid.txt"

jq -r '.hooks[] | "\(.id)\t\(.state)\thits=\(.hits)\terr=\(.errors)"' "$OUT/heartbeat.json" \
  > "$OUT/hooks.tsv" 2>/dev/null
echo "collected -> $OUT (uid=$UID_)"
```

---

## 附录 C：常用取值速查

| 用途 | 命令 |
|---|---|
| 取 uid | `adb shell dumpsys package <PKG> \| grep -m1 userId=` |
| 取 launcher activity | `adb shell cmd package resolve-activity --brief <PKG> \| tail -1` |
| 看冻结态 | `adb shell cat /sys/fs/cgroup/uid_<UID>/cgroup.freeze` |
| 看 GreeZe 全局 | `adb shell dumpsys greeze \| head -60` |
| 看历史 | `adb shell dumpsys greeze old \| grep -A30 "Greezer History"` |
| 模块日志 | `adb logcat -s HyperGreeze` |
| 系统崩溃 | `adb logcat -b crash -d` |
| 当前时间戳 | `date +%s%3N` |

| 掩码速记 | 值 |
|---|---|
| `AUDIO` | `2` |
| `CAMERA` | `16777216` |
| `AUDIO + CAMERA` | `16777218` |
| `NO_RESTRICT` | `64` |
| `LOCKED_DROP` 全集 | `16783906` (`0x01001A22`) |
| `DEFAULT_KEEP` | `16777234` (`0x01000012`) |
| `DEFAULT_DROP` | `589888` (`0x00090040`) |

| 关键属性 | 含义 |
|---|---|
| `persist.sys.hypergreeze.guard` | 启动守卫计数，`>= 3` 触发被动 |
| `persist.sys.hypergreeze.safe` | `1` = 手动安全模式闩锁 |
| `persist.sys.hypergreeze.safe_reason` | 最近一次被动原因文本 |
| `persist.sys.gz.enable` | GreeZe 自己的总闸；模块对其只做读取（逃生用） |
