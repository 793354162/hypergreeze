# HyperGreeze

> 逐 App 精确接管 HyperOS **GreeZe** 冻结行为的 LSPosed 模块（**libxposed API 102**）＋ HyperOS / Miuix 风格管理界面。

[![build](https://github.com/793354162/hypergreeze/actions/workflows/build.yml/badge.svg)](https://github.com/793354162/hypergreeze/actions/workflows/build.yml)

**下载**：见 [Releases](https://github.com/793354162/hypergreeze/releases)。所有 APK 使用同一张固定签名证书（指纹见 [docs/BUILD_AND_SIGN.md](docs/BUILD_AND_SIGN.md)），可在旧版上原地覆盖安装。

设计依据：`LSPosed-Greeze逐App冻结控制模块可行性分析.md`（GreeZe 的静态逆向结论）＋ 字节码反射契约。

---

## 它到底做了什么

HyperOS 的 GreeZe 把「冻结 / 解冻 / 名单 / 资源限制 / 状态查询」实现成 `com.miui.server.greeze.GreezeManagerService` 的普通 Java 方法。HyperGreeze 注入 `system_server`，**在 GreeZe 的决策发生处**挂钩，用一份 JSON 策略逐 App 改写它的判断。

| 能力 | 状态 | 落点 |
|---|---|---|
| 每个 App 四态（跟随 / 永不 / 立即 / 自定义） | ✅ | `freezeUid`、`freezeUids`、`freezeProcess`、`isTempWhite`、`isNoRestrictApp` |
| 32 位场景掩码（音频 / 相机 / 前台服务 / 白名单…） | ✅ | `AurogonFilterManager.filter(uid, pkg, reason)` |
| 退到后台立即冻结（延时 + 冷却 + 去重） | ✅ | `onForegroundActivitiesChanged` → 异步 `freezeUid` |
| 子进程例外（微信 `:push` 等） | ✅（仅 `freezeProcess` 路径） | `freezeProcess` + `RunningProcess` 反射 |
| 资源联动：网络 / Job / Wakelock | ✅ | `udpPackageRestrict` / `jobRestrict` / `updateWakeLockLimit` |
| 资源联动：闹钟 / 广播 / CPU 限频 | ⛔ 只存策略 | 没有可靠的 per-uid 消费点（见 [docs/LIMITS.md](docs/LIMITS.md)） |
| 事件审计（冻结 / 跳过 / 失败原因串） | ✅ | `freezeUid` 返回值 + 命令回执 |
| 命令通道（解冻全部 / 立即冻结 / 快速模式 / 安全模式…） | ✅ | `Settings.System` + `seq` 水位 |
| 安全模式（连续 3 次异常启动自动被动） | ✅ | `persist.sys.hypergreeze.guard` |
| 单线程冻结 / 单 binder 事务 / 冻结后仍收推送 | ⛔ 物理不可能 | cgroup v2 + Android 推送模型边界 |

**它绝不写 GreeZe 的系统属性**（`persist.sys.gz.*`、`persist.sys.greeze.*`），也不 hook 任何与冻结无关的服务。卸载模块后系统行为立即回到原生。

---

## 安装

1. 设备：HyperOS / MIUI（Android 11+，存在 `com.miui.server.greeze.*`），已安装 **支持 libxposed API 102 的 LSPosed**。
2. 装 APK → LSPosed 里启用本模块，**作用域只勾「系统框架」**（模块自带 `scope.list = system`）。
3. 打开 HyperGreeze → 授予「修改系统设置」（写策略需要）。
4. 软重启（LSPosed 里对本模块选择重启，或重启设备）。

第一次进入时首页会显示「等待 Hook 心跳」；出现心跳即代表 hook 已装好，诊断页能看到每个落点的 `installed / missing` 与命中次数。

## 逃生（先记住这三条）

```bash
adb shell settings delete system greeze_ctl_policy_v1      # 清空策略 -> 纯跟随系统
adb shell settings put system greeze_ctl_safe_mode_v1 1    # 请求安全模式（下次开机不挂钩子）
adb shell settings put system greeze_ctl_boot_guard_v1 0   # 清启动守卫计数（重启后生效）
# 终极手段：LSPosed 里取消「系统框架」作用域后重启
```

安全模式还有一条不依赖 App 的路径：`adb shell setprop persist.sys.hypergreeze.safe 1`。

## 为什么可以信它

* **只读一次配置，只改判定结果**：任何未配置的 App，`PolicyEngine` 一律返回系统原值。
* **hook 体三道防线**：先 `proceed()` 拿真实结果 → 自己的判定包 `try/catch` → 异常返回真实结果；框架层再套 `ExceptionMode.PROTECTIVE`。
* **无网络权限**：模块不申请 `INTERNET`。
* **反射不写死描述符**：按「方法名 + 参数个数」解析，OTA 改签名时降级为 `missing` 而不是崩 system_server。
* **启动守卫**：连续 3 次未进入 90s 稳定窗口，第 3 次开机自动被动（hook 仍装着，但全部放行）。
* **固定签名 + CI 可复现**：每次构建都用仓库内同一张证书，CI 会打印指纹供核对；vendored 的 Xposed API jar 每次与 Maven Central 原物比对 sha256。

## 构建

只有 CI 构建（本机不需要 Android SDK）：push 即构建，打 `v*` tag 会自动发布 Release。

```bash
git tag v1.0.1 && git push origin v1.0.1
```

为什么只在 CI 构建、为什么 vendored `libxposed-api-102.jar`、如何轮换签名 —— [docs/BUILD_AND_SIGN.md](docs/BUILD_AND_SIGN.md)。

## 文档

| 文档 | 内容 |
|---|---|
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | 三层架构、启动时序、一次「退后台」的完整调用链、线程模型 |
| [docs/HOOK_MAP.md](docs/HOOK_MAP.md) | 每个 hook 的落点 / 语义 / `proceed()` 位置 / 失败模式 / 验证命令 |
| [docs/POLICY_SCHEMA.md](docs/POLICY_SCHEMA.md) | Settings 键表、策略 JSON 全字段、32 位掩码表、命令 op、心跳与事件字段、adb 手改示例 |
| [docs/LIMITS.md](docs/LIMITS.md) | 做不到的、故意不做的、未确证的、版本漂移降级路径 |
| [docs/DESIGN_MIUIX.md](docs/DESIGN_MIUIX.md) | Miuix 设计令牌（颜色/尺寸/排版）、超椭圆圆角实现、控件清单、与 M3 的差异 |
| [docs/TEST_PLAN.md](docs/TEST_PLAN.md) | 真机验收清单（54 项，含取证命令与失败判定） |
| [docs/BUILD_AND_SIGN.md](docs/BUILD_AND_SIGN.md) | CI 流程、固定签名、依赖校验、排障 |
| [CHANGELOG.md](CHANGELOG.md) | 迭代记录（含踩过的坑） |

## 已知边界（摘要）

* UI **不显示真实 per-uid 冻结状态**：那需要 hook 侧为每个 uid 做一次 Binder 往返。列表里的徽章是「策略意图」，只有 `never` 能确定「没被冻」。
* `onForegroundActivitiesChanged(int, int, boolean)` 的两个 int 谁是 uid **未在真机确证**；代码用「哪个参数能解析出包名」的经验法判定，并在诊断页显示 `uid=arg0/arg1/unresolved`。
* `filter()` 返回 `true` = 允许冻结，这个极性来自静态分析。若在某个 ROM 上表现相反，诊断页有「反射 filter 极性反转」开关（只反转模块的改写，不动系统答案）。
* 热重载未启用（未声明 `autoHotReload`）；升级模块后需软重启。

完整清单见 [docs/LIMITS.md](docs/LIMITS.md)。

## 许可

代码 MIT（见 [LICENSE](LICENSE)）。超椭圆圆角数学移植自 miuix（Apache-2.0）；设计令牌派生自 miuix 与 HyperOS 设计令牌；`libs/libxposed-api-102.jar` 是 `io.github.libxposed:api:102.0.0` 的 `classes.jar`（Apache-2.0，仅 compileOnly）。
