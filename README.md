# HyperGreeze

> 逐 App 精确接管 HyperOS **GreeZe** 冻结行为的 LSPosed 模块（**libxposed API 102**）＋ HyperOS/Miuix 风格管理界面。
>
> 设计依据：[`LSPosed-Greeze逐App冻结控制模块可行性分析.md`](../LSPosed-Greeze逐App冻结控制模块可行性分析.md)

**状态：** 见 [CHANGELOG.md](CHANGELOG.md) 与 CI 徽章。策略引擎、Hook 层、UI 三层均为真实实现，非占位。

---

## 这是什么

HyperOS 的 GreeZe 把「冻结/解冻/名单/资源限制/状态查询」全部实现为 `com.miui.server.greeze.GreezeManagerService` 的普通 Java 方法（静态分析：380 个方法 / 201 个字段）。HyperGreeze 注入 `system_server`，**在 GreeZe 的决策发生处**挂钩，用一份 JSON 策略逐 App 改写它的判断：

| 能力 | 实现方式 |
|---|---|
| 每个 App 四态（跟随 / 永不 / 立即 / 自定义） | `freezeUid` + `isTempWhite` + `isNoRestrictApp` 拦截 |
| 32 位场景掩码（音频/相机/前台服务/白名单…） | `AurogonFilterManager.filter(uid, pkg, reason)` 求交/清位 |
| 退到后台立即冻结（含延时、冷却） | `onForegroundActivitiesChanged` 触发 → 异步 `freezeUid` |
| 子进程级例外（微信 `:push` 等） | `freezePids` / `getProcessByUid` |
| 资源联动（网络/闹钟/Job/Wakelock/广播/CPU） | GreeZe 资源 API（逐项可开关） |
| 事件审计（冻结/解冻/跳过/失败原因码） | 拦截返回串回写环形缓冲 |
| 安全模式（连续 3 次异常启动自动停用 hook） | 见 `SafetyGuard` |

**做不到的**（cgroup v2 与 Android 推送模型的硬边界，不是实现偷懒）：单线程冻结、单 binder 事务冻结、冻结后仍实时收推送。详见 [docs/LIMITS.md](docs/LIMITS.md)。

---

## 安装

1. 设备：HyperOS / MIUI（Android 11+，需存在 `com.miui.server.greeze.*`），已装 **LSPosed（支持 libxposed API 102）**。
2. 装 APK → LSPosed 里启用本模块，**作用域只勾「系统框架」**（模块自带 `scope.list = system`）。
3. 打开 HyperGreeze → 授予「修改系统设置」权限（写策略需要）。
4. 重启（或在 LSPosed 里对本模块选择软重启）。

策略完全由模块自己维护，**不写任何 GreeZe 系统属性**；卸载模块后系统行为立即回到原生。

## 逃生（先记住）

```bash
adb shell settings delete system greeze_ctl_policy_v1     # 清空策略 -> 纯跟随系统
adb shell settings put system greeze_ctl_safe_mode_v1 1   # 强制安全模式（下次开机不挂钩子）
adb shell settings delete system greeze_ctl_boot_guard_v1 # 清掉启动守卫计数
# 或：LSPosed 里取消「系统框架」作用域后重启
```

## 构建

只有 CI 构建，产物固定签名（`keystore/hypergreeze.jks`，证书 SHA-256 见 [docs/BUILD_AND_SIGN.md](docs/BUILD_AND_SIGN.md)）。
本地构建说明、为什么 vendored `libxposed-api-102.jar`、如何换签名，全部在 docs 里。

```bash
gh workflow run build            # 或直接 push
```

## 文档

| 文档 | 内容 |
|---|---|
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | 三层架构、调用时序、线程模型、状态机 |
| [docs/HOOK_MAP.md](docs/HOOK_MAP.md) | H1–H8 每个 hook 的落点、语义、失败模式、验证命令 |
| [docs/POLICY_SCHEMA.md](docs/POLICY_SCHEMA.md) | 策略 JSON 完整字段表 + adb 手改示例 + 通道选型论证 |
| [docs/DESIGN_MIUIX.md](docs/DESIGN_MIUIX.md) | Miuix 设计令牌与组件规格（颜色/圆角/排版/动效） |
| [docs/LIMITS.md](docs/LIMITS.md) | 硬上限、已确证/未确证清单、版本漂移对策 |
| [docs/BUILD_AND_SIGN.md](docs/BUILD_AND_SIGN.md) | CI 流程、固定签名、依赖来源校验、发布 |
| [docs/TEST_PLAN.md](docs/TEST_PLAN.md) | 真机验收清单（含 100 次 fg/bg 抖动测试） |
| [CHANGELOG.md](CHANGELOG.md) | 迭代记录 |

## 许可

代码 MIT。分析报告与字节码结论来自本仓库外的 GreeZe 逆向归档，不复述其原始字节。
