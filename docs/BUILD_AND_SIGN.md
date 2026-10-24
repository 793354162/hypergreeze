# 构建与签名

## 1. 只在 CI 构建（有意为之）

仓库不提供本地构建脚本的使用说明作为主路径，因为本模块的编译环境有两个硬约束，交给 CI 固定比在每台机器上装一遍可靠：

1. **`io.github.libxposed:api:102.0.0` 是 AAR，且其 `aar-metadata.properties` 声明 `minCompileSdk=37`。** 公开 SDK 仓库目前最高只到 `platforms;android-36`，直接 `compileOnly("io.github.libxposed:api:102.0.0")` 会让 AGP 在 `checkAarMetadata` 直接失败。
   → 因此把该 AAR 的 `classes.jar` 原样放进 `libs/libxposed-api-102.jar`，以 `compileOnly(files(...))` 引用。它不进 APK（`compileOnly`），运行时由框架提供。

2. **签名必须固定。** 每次构建都共用仓库内的 `keystore/hypergreeze.jks`，这样任何一次升级都是原地覆盖安装，LSPosed 也不会把模块当成两个不同的包。

## 2. 依赖来源校验

CI 每次都拿 Maven Central 上的原始构件和仓库里的 jar 比对，确保 vendored 文件没被替换：

```yaml
curl -fsSL -o api.aar https://repo1.maven.org/maven2/io/github/libxposed/api/102.0.0/api-102.0.0.aar
unzip -o -q api.aar classes.jar -d apix
test "$(sha256sum apix/classes.jar | cut -d' ' -f1)" = "$(sha256sum libs/libxposed-api-102.jar | cut -d' ' -f1)"
```

当前值：

| 文件 | SHA-256 |
|---|---|
| `libs/libxposed-api-102.jar`（= `api-102.0.0.aar!/classes.jar`） | `a515dd7a53cd7a47c05e101dff77d61acb3091a97b20a885b9ea3494412db985` |

依赖坐标与仓库（记录在案）：

```
group=io.github.libxposed
artifact=api
version=102.0.0
repo=https://repo1.maven.org/maven2/   (mavenCentral; 不是 api.xposed.info)
```

> `api.xposed.info` 是**旧** `de.robv.android.xposed:api:82` 的仓库；`io.github.libxposed` 组在那边 404。
> `io.github.libxposed:annotation:1.0.0` 是普通 jar，没有 `minCompileSdk` 问题，所以仍然用 `compileOnly` 正常引用。

## 3. 版本矩阵（为什么是这些版本）

| 组件 | 版本 | 原因 |
|---|---|---|
| Gradle | 8.11.1 | AGP 8.7 要求 ≥8.9；6/7 的 DSL 差异会带来无谓风险 |
| AGP | 8.7.3 | 8.x 末段稳定版，支持 `compileSdk 35`；不用 9.x 以免踩 DSL 变更 |
| Kotlin | 2.0.21 | 与 `org.jetbrains.kotlin.plugin.compose` 同版本，Compose 编译器随语言版本走 |
| Compose BOM | 2024.09.00 | 稳定且与 Kotlin 2.0.x 的组合被大量项目验证 |
| compileSdk / targetSdk | 35 | runner 镜像自带；`minSdk 30`（HyperOS 设备都在这之上） |
| JDK | 21（toolchain 17） | AGP 8.7 要求 17+，用 21 跑 Gradle、产物按 17 编译 |

UI **只用** `compose.ui` / `compose.foundation`，不依赖 `material` / `material3` / `material-icons`：
Miuix 不是 Material，混进来只会让主题、触摸反馈和圆角语义互相打架。图标是自绘 `ImageVector`（`ui/components/Icons.kt`）。

## 4. 固定签名

```
keystore/hypergreeze.jks        PKCS12, RSA 4096, 有效期 36500 天
keystore/keystore.properties    storePassword/keyAlias/keyPassword（公开，见下）
alias                           hypergreeze
```

**证书指纹（核验用）**

```
SHA-256  DC:40:BF:A8:57:D2:0A:2A:40:B5:5B:1E:EB:A1:69:C2:C8:DC:3B:40:80:1A:82:B2:69:5E:9B:F9:4B:CC:11:9C
SHA-1    C4:26:A0:0A:0D:BD:0B:25:97:8F:19:D5:88:C3:C6:9E:13:4B:AC:08
```

CI 在 `Report signing certificate` 步骤会跑 `apksigner verify --print-certs`，把指纹打进构建日志，可与上面两行逐字对照。

**为什么把私钥放在公开仓库里？** 因为这是模块发布的唯一目的：一个固定身份的 APK。它不保护任何机密（模块不含密钥、不含联网能力），泄露的后果只有「别人也能签出同包名 APK」——而这对任何自签名 Android 应用都成立，且前提是已经能装到你的手机上。反过来，私钥丢失会导致所有人无法原地升级，代价更大。

**轮换策略**：只有一种情况换签名——现有 keystore 私钥泄露并已被用来发布恶意 APK。换签名意味着包名身份变化，用户必须先卸载旧版。轮换时更新本节指纹，并在 `CHANGELOG.md` 里留一条醒目记录。

## 5. 发版流程

```bash
# 1) 改 app/build.gradle.kts 的 versionCode / versionName
# 2) 提交
git commit -am "release: 1.1.0"
# 3) 打 tag 并推：CI 会自动 build 并创建 GitHub Release（附带 release APK）
git tag v1.1.0 && git push origin v1.1.0
```

CI 的 `Publish release (tags only)` 步骤用 `softprops/action-gh-release@v2` 上传 `app/build/outputs/apk/release/*.apk`。

## 6. 产物

```
app/build/outputs/apk/release/app-release.apk     固定签名，装这个
app/build/outputs/apk/debug/app-debug.apk         同样用固定签名（debug 也签 release key，便于覆盖安装测试）
signing.txt                                       本次构建的证书指纹
```

APK 里必须存在（CI 会检查，缺了直接 fail）：

```
META-INF/xposed/module.prop
META-INF/xposed/scope.list
META-INF/xposed/java_init.list
```

## 7. 排障

| 症状 | 原因 / 处理 |
|---|---|
| `Invalid TOML catalog definition ... Unexpected '\ufeff'` | `libs.versions.toml` 被写入了 UTF-8 BOM。TOML 不允许 BOM，重新保存为无 BOM UTF-8。 |
| `Dependency ... requires ... compileSdk 37` | 有人把 vendored jar 换回了 maven 坐标。改回 `compileOnly(files(...))`。 |
| `Keystore file ... not found for signing config 'release'` | `keystore.properties` 里的 `storeFile` 是**相对 app 模块**的路径（`../keystore/hypergreeze.jks`），解析时用 `file(...)` 而不是 `rootProject.file(...)`。 |
| `android-actions/setup-android@v3` 失败 `Failed to find package 'tools'` | 该 action 仍会装已移除的 `tools` 包。CI 已改为直接用 runner 自带 SDK + `sdkmanager` 补装 platform/build-tools。 |
| `Unresolved reference: KeyboardArrowRight` | `material-icons-core` 的图标子集不含该图标。本仓库已不依赖它，用 `HgIcons`。 |
