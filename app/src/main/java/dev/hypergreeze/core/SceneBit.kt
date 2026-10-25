package dev.hypergreeze.core

/**
 * The 32-bit scenario mask GreeZe itself uses when deciding whether an app may be frozen.
 *
 * Source: `com.miui.server.greeze.*` reason constants recovered from `miui-services.jar`
 * (see docs/HOOK_MAP.md and the feasibility report §2.3). Bit values are powers of two; the
 * table is duplicated here on purpose because the hook side must not depend on GreeZe classes
 * being loadable at *compile* time.
 *
 * Semantics used by HyperGreeze:
 *  - putting a bit into `keep`  => force the exemption  => `filter()` returns false
 *  - putting a bit into `drop`  => drop the exemption   => `filter()` returns true
 *  - [LOCKED_DROP] bits refuse to be dropped because doing so breaks visible system behaviour.
 */
enum class SceneBit(val value: Int, val label: String, val group: Group) {
    RESERVED(1, "保留位 (0x1)", Group.POLICY),

    AUDIO(2, "音频播放", Group.MEDIA),
    DOWNLOAD(4, "下载中", Group.POLICY),
    GPS(8, "定位中", Group.LOCATION),
    BLUETOOTH(16, "蓝牙", Group.CONNECTIVITY),
    VISIBLE(32, "可见窗口", Group.UI),
    NO_RESTRICT(64, "白名单", Group.POLICY),
    REMOTE_VIEW(128, "远程视图", Group.UI),
    NO_ICON(256, "无图标", Group.POLICY),
    TOP(512, "前台 TOP", Group.UI),
    BACKUP(1024, "备份中", Group.POLICY),
    IME(2048, "输入法", Group.UI),
    VIBRATOR(4096, "马达", Group.MEDIA),
    WIDGET(8192, "桌面小部件", Group.UI),
    VPN(16384, "VPN", Group.CONNECTIVITY),
    MUTI_WINDOW(32768, "分屏/小窗", Group.UI),
    FG_CONNECTION(65536, "前台服务连接", Group.SERVICE),
    VALID(131072, "VALID_APP", Group.SERVICE),
    CONTROLLER(262144, "CONTROLLER_APP", Group.SERVICE),
    FOREGROUND_SERVICE(524288, "前台服务", Group.SERVICE),
    VIRTUAL_DISPLAY(1048576, "虚拟显示", Group.SERVICE),
    ZYGOTE(2097152, "ZYGOTE_APP", Group.SERVICE),
    INSTRUMENTATION(4194304, "调试中", Group.SERVICE),
    BROADCAST_LAUNCH(8388608, "广播拉起", Group.SERVICE),
    CAMERA(16777216, "相机", Group.MEDIA),
    DYNAMIC_ISLAND(33554432, "灵动岛", Group.UI),
    WINDOW_OVERLAY(67108864, "悬浮窗", Group.UI),
    TTS(134217728, "TTS", Group.MEDIA),
    APP_FUNCTION(268435456, "APP_FUNCTION", Group.SERVICE),
    CARWITH_CAST(536870912, "车机投屏", Group.SERVICE),
    BIND_SYSTEMUI(1073741824, "绑定 SystemUI", Group.SERVICE),
    ;

    enum class Group(val label: String) {
        MEDIA("多媒体"),
        UI("界面"),
        CONNECTIVITY("连接"),
        LOCATION("定位"),
        SERVICE("服务"),
        POLICY("策略"),
    }

    companion object {
        /** Dropping these exemptions makes the app freeze while it is visibly in use. */
        val LOCKED_DROP: Set<SceneBit> = setOf(AUDIO, CAMERA, TOP, IME, VISIBLE, VIBRATOR)

        /**
         * "The app is being used right now" bits. Used by the foreground-independent trigger:
         * `filter()` is called by GreeZe whenever it evaluates an app, so a call *without* any of
         * these bits is a strong hint that the app is no longer in front — which is what
         * IMMEDIATE mode needs when the ROM moved its foreground callback.
         */
        val IN_USE: Set<SceneBit> = setOf(
            TOP, VISIBLE, MUTI_WINDOW, IME, DYNAMIC_ISLAND, WINDOW_OVERLAY, REMOTE_VIEW,
        )

        /** Preselected as "keep" for a new custom policy: what breaks calls / camera most often. */
        val DEFAULT_KEEP: Set<SceneBit> = setOf(AUDIO, CAMERA, BLUETOOTH)

        /** Preselected as "drop": stop honouring GreeZe's own whitelist for this app. */
        val DEFAULT_DROP: Set<SceneBit> = setOf(NO_RESTRICT, FG_CONNECTION, FOREGROUND_SERVICE)

        fun fromMask(mask: Int): Set<SceneBit> =
            entries.filterTo(LinkedHashSet()) { (mask and it.value) != 0 }

        fun toMask(bits: Iterable<SceneBit>): Int {
            var out = 0
            for (bit in bits) out = out or bit.value
            return out
        }

        fun describe(mask: Int): String =
            fromMask(mask).joinToString("、") { it.label }.ifEmpty { "无" }
    }
}
