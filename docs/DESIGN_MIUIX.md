# Miuix 设计令牌与实现

模块的界面不是「Material 换皮」，而是按 HyperOS 的 Miuix 设计语言重画的一套最小控件集。所有数值都能追到来源：miuix 源码（`Colors.kt` / `Card.kt` / `Button.kt` / `Switch` / `TextStyles`）与 HyperOS 设计令牌（`accentBlueFill`、`fontSystem*`、`spaceToken`）。

## 1. 颜色

| 语义 | 亮色 | 暗色 |
|---|---|---|
| primary | `#3482FF` | `#4788FF` |
| onPrimary | `#FFFFFF` | `#FFFFFF` |
| primaryContainer | `#EAF2FF` | `#2B3B54` |
| secondary（开关关态轨道） | `#E6E6E6` | `#505050` |
| secondaryContainer | `#F0F0F0` | `#434343` |
| background | `#F7F7F7` | `#000000` |
| card | `#FFFFFF` | `#242424` |
| cardPressed | `#EBEBEB` | `#313131` |
| divider | `#E0E0E0` | `#393939` |
| outline | `#D9D9D9` | `#404040` |
| textPrimary | `#000000` | `90% #FFFFFF` |
| textSecondary | `60% #000000` | `50% #FFFFFF` |
| textHint | `40% #000000` | `40% #FFFFFF` |
| textDisabled | `#B2B2B2` | `#666666` |
| danger | `#E94634` | `#F12522` |
| success | `#0CCE94` | `#00BA7C` |
| warning | `#FF6A05` | `#F97A1F` |
| info | `#29A9FF` | `#36ACFC` |
| scrim | `30% #000000` | `60% #000000` |
| sliderTrack | `6% #000000` | `15% #FFFFFF` |
| frozenFully | `#3482FF` | `#4788FF` |
| frozenPartial | `#DD5900` | `#F97A1F` |
| unfrozen | `#00B884` | `#00BA7C` |

冻结三态徽章用「同色文字 + 同色 10% 透明底」，对应 HyperOS 的 `accent*Fill/Content/Subtle` 四层用法里的 Content + Subtle。

## 2. 尺寸

| 令牌 | 值 | 说明 |
|---|---|---|
| cardRadius | 16dp | 大卡片 |
| dialogRadius | 32dp | 对话框 |
| sheetRadius | 28dp | 底部弹层 |
| inputRadius | 16dp | 搜索框 |
| segmentedContainer / Item | 12dp / 8dp | 分段控件 |
| pagePadding | 16dp | 页面左右边距 |
| cardSpacing | 8dp | 卡片间距 |
| rowMinHeight | 56dp | 列表行 |
| rowPaddingH / V | 16dp / 12dp | 列表行内边距 |
| dividerThickness | 0.75dp | 分隔线（不是 1dp） |
| dividerInset | 16dp | 分隔线左缩进 |
| switchWidth / Height / Thumb / Inset | 50 / 28 / 20 / 4dp | **不是** 52×32 |
| buttonMinHeight | 46dp | 主/次按钮 |
| segmentedHeight / ItemHeight | 40 / 32dp | 容器 / 项 |
| sliderHeight / KnobRadius | 28dp / 10dp | |
| topBarCollapsed | 52dp | 折叠标题栏 |
| emptyIllustration | 60dp | 空状态插图 |
| sheetHandle | 45×4dp | 弹层把手 |

## 3. 排版

| 用途 | 字号/行高 | 字重 |
|---|---|---|
| 页面大标题 | 32/42 | Medium |
| 折叠标题 | 20/27 | Medium |
| 分区标题 | 20/27 | Normal |
| 小分区标题 | 14/17 | Bold |
| 列表主标题 | 16/21 | Medium |
| 列表副标题 | 13/17 | Normal |
| 列表右值 | 14/19 | Normal |
| 正文 | 17/23 | Normal |
| 按钮 | 17/23 | Medium |
| 说明 | 13/17 | Normal |
| 极小注 | 11/15 | Normal |
| 数值 | 24/29 | SemiBold |

字体栈就是 `FontFamily.SansSerif`：在 HyperOS 上它落到 MiSans，在其它 ROM 上落到系统无衬线，不需要打包字体。

## 4. 超椭圆圆角（这个仓库里唯一"不常规"的实现）

Miuix 的卡片圆角不是圆弧，是连续曲率。原实现（`miuix` 的 `SquirclePath.kt`）用的是：

* 角部「瓦片」尺寸 = `cornerRadius × 1.1`
* 三次贝塞尔手柄比例 `0.643`

`ui/components/Foundation.kt` 里的 `squirclePath()` 就是把它逐行移植过来，并通过 `Shape.createOutline` 暴露成 `Shapes.card / dialog / sheet / field`。没有引入 miuix 的 shader 版（`miuix-squircle` 的 `Modifier.squircleSurface`），原因是要保持零第三方 UI 依赖；路径版在静态填充/裁剪上没有可见差异，只是缺少 shader 的抗锯齿补偿。

## 5. 控件清单

| 控件 | 文件 | 关键行为 |
|---|---|---|
| `MiuixText` | Foundation.kt | 把颜色/对齐塞进 `TextStyle`，避免依赖 Material 的 `Text` |
| `MiuixCard` | Foundation.kt | 超椭圆 + 按压缩放（`spring(0.8, 600)` → 0.985）+ 按压背景切换 |
| `MiuixRow` | Foundation.kt | 单行最多一个右对齐元素组（值 → 8dp → 箭头），这是 Miuix 列表可读性的根 |
| `MiuixDivider` | Foundation.kt | 0.75dp + 左缩进 |
| `MiuixSwitch` | Controls.kt | 50×28、滑块 20dp、位移 22dp、`spring(0.7, 987)`、按压滑块放大 1.12 |
| `MiuixButton` | Controls.kt | Primary/Secondary/Danger/Text 四态，胶囊或 8dp 圆角，按压缩放 0.94 |
| `MiuixSegmented` | Controls.kt | 容器 40dp / 项 32dp，选中项为卡片色（无阴影） |
| `MiuixSlider` | Controls.kt | Canvas 自绘（6dp 轨道、10dp 滑块、1dp 主色描边），`detectTapGestures` + 横向拖拽，支持 `step` 吸附 |
| `MiuixTriSelector` | Controls.kt | 三态小控件（不改/豁免/不豁免），被锁定项显示为「锁定」 |
| `MiuixChip` | Controls.kt | 胶囊筛选片 |
| `MiuixTextField` | Controls.kt | 45dp 高、卡片底、`BasicTextField` |
| `PageScaffold` | Overlays.kt | 大标题随滚动消失 + 52dp 折叠栏淡入（`derivedStateOf` 判滚动位置） |
| `MiuixBottomSheet` | Overlays.kt | 遮罩淡入 + 卡片上滑，把手 + 关闭箭头 + 点击遮罩关闭 |
| `MiuixTabBar` | Overlays.kt | 四项底栏，选中态主色 |
| `MiuixSnackbar` | Overlays.kt | 反色容器 + 16dp 圆角 |
| `HgIcons` | Icons.kt | 自绘 `ImageVector`（返回、chevron、勾、警告、首页/应用/事件/诊断/盾牌） |

## 6. 与 Material 3 的硬差异（评审用）

| 维度 | Miuix（本实现） | Material 3 |
|---|---|---|
| 阴影 | 0dp | 1–6dp tonal elevation |
| 圆角 | 超椭圆（连续曲率） | 圆弧 |
| 卡片圆角 | 16dp | 12dp |
| 分隔线 | 0.75dp + 缩进 | 1dp 通栏 |
| 开关 | 50×28 无描边 | 52×32 + 2dp 描边 |
| 按钮高度 | 46dp | 40dp |
| 大标题 | 32sp → 20sp | 28sp → 22sp |
| 主色 | 固定 `#3482FF` | 动态取色（Monet） |
| 触摸反馈 | 缩放（Sink 0.94/0.985） | Ripple |

## 7. 已知取舍

* 没有实现分段控件的滑块位移动画（需要 `SubcomposeLayout` 测量，收益小、风险大）。
* 没有实现底部弹层的手势下拉关闭（只做了点遮罩/点箭头）。阈值参考值记录在案：位移 150dp 或速度 800dp/s。
* 没有 `Skeleton` 骨架屏，加载态用空状态文案替代。
