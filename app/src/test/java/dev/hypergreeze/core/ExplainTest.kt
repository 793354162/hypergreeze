package dev.hypergreeze.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the wording down. Explanations are user-facing text, so "it still compiles" is not enough:
 * a wrong sentence here tells the user the module does the opposite of what the engine does.
 *
 * The assertions are deliberately about *content* (does the line mention the delay, the bit, the
 * original answer) rather than full string equality, except where the exact shape is contractual.
 */
class ExplainTest {

    private val followGlobal = GlobalPolicy()

    private fun policyOf(vararg entries: Pair<String, AppPolicy>): Policy =
        entries.fold(Policy.EMPTY) { acc, entry -> acc.withApp(entry.first, entry.second) }

    // ------------------------------------------------------------------ modes

    @Test
    fun `mode lines are four distinct sentences`() {
        val lines = FreezeMode.entries.map { Explain.modeLine(it) }
        assertEquals(4, lines.size)
        assertEquals(4, lines.toSet().size)
        for (line in lines) {
            assertTrue(line.isNotBlank())
            assertTrue("too long: $line", line.length <= 40)
        }
    }

    // ---------------------------------------------------------------- summary

    @Test
    fun `summary of an empty policy says nothing is changed`() {
        val text = Explain.summary(Policy.EMPTY)
        assertTrue(text, text.contains("没有任何应用"))
        assertTrue(text, text.contains("无影响"))
    }

    @Test
    fun `summary counts never and immediate apps`() {
        val policy = policyOf(
            "a.never1" to AppPolicy(mode = FreezeMode.NEVER),
            "a.never2" to AppPolicy(mode = FreezeMode.NEVER),
            "a.never3" to AppPolicy(mode = FreezeMode.NEVER),
            "a.fast1" to AppPolicy(mode = FreezeMode.IMMEDIATE),
            "a.fast2" to AppPolicy(mode = FreezeMode.IMMEDIATE),
            "a.follow" to AppPolicy(),
        )
        assertEquals(
            "3 个应用永不冻结 · 2 个退后台立刻冻 · 其余跟随系统",
            Explain.summary(policy),
        )
    }

    @Test
    fun `summary names custom apps too`() {
        val policy = policyOf("a.custom" to AppPolicy(mode = FreezeMode.CUSTOM))
        val text = Explain.summary(policy)
        assertTrue(text, text.contains("1 个按自定义规则冻"))
        assertTrue(text, text.contains("其余跟随系统"))
    }

    // --------------------------------------------------------------- app lines

    @Test
    fun `follow app has a single line that says it is untouched`() {
        val lines = Explain.appLines("com.follow", AppPolicy(), followGlobal)
        assertEquals(1, lines.size)
        assertTrue(lines[0], lines[0].contains("跟随系统"))
    }

    @Test
    fun `never app line says it will not be frozen`() {
        val lines = Explain.appLines(
            "com.never",
            AppPolicy(mode = FreezeMode.NEVER),
            followGlobal,
        )
        assertTrue(lines.joinToString(" | "), lines.any { it.contains("不会冻结") })
        assertTrue(lines.joinToString(" | "), lines.none { it.startsWith("会冻结") })
    }

    @Test
    fun `immediate app line mentions the configured delay`() {
        val lines = Explain.appLines(
            "com.fast",
            AppPolicy(mode = FreezeMode.IMMEDIATE, delayMs = 500L),
            followGlobal,
        )
        val text = lines.joinToString(" | ")
        assertTrue(text, text.contains("会冻结"))
        assertTrue(text, text.contains("500"))
        assertTrue(text, text.contains("毫秒"))
    }

    @Test
    fun `custom app line names the kept bit`() {
        val app = AppPolicy(
            mode = FreezeMode.CUSTOM,
            maskKeep = SceneBit.toMask(listOf(SceneBit.AUDIO)),
        )
        val lines = Explain.appLines("com.custom", app, followGlobal)
        val text = lines.joinToString(" | ")
        assertTrue(text, text.contains(SceneBit.AUDIO.label))
        assertTrue(text, text.contains("不冻它"))
    }

    @Test
    fun `drop bit line says it freezes anyway`() {
        val app = AppPolicy(
            mode = FreezeMode.CUSTOM,
            maskDrop = SceneBit.toMask(listOf(SceneBit.NO_RESTRICT)),
        )
        val text = Explain.appLines("com.custom", app, followGlobal).joinToString(" | ")
        assertTrue(text, text.contains(SceneBit.NO_RESTRICT.label))
        assertTrue(text, text.contains("照冻"))
    }

    @Test
    fun `full config stays within six short lines`() {
        val app = AppPolicy(
            mode = FreezeMode.CUSTOM,
            delayMs = 1_500L,
            thawCooldownMs = 30_000L,
            maskKeep = SceneBit.toMask(listOf(SceneBit.AUDIO, SceneBit.CAMERA)),
            maskDrop = SceneBit.toMask(listOf(SceneBit.NO_RESTRICT, SceneBit.FOREGROUND_SERVICE)),
            processes = mapOf(
                "com.full:push" to FreezeMode.NEVER,
                "com.full:work" to FreezeMode.IMMEDIATE,
            ),
            resources = ResourceSwitches(net = true, alarm = true, job = false),
            thawOn = ThawTriggers(screenOn = true, notification = true, charging = false),
        )
        val lines = Explain.appLines("com.full", app, followGlobal)
        assertTrue("line count ${lines.size}", lines.size <= 6)
        assertTrue(lines.size >= 5)
        for (line in lines) {
            assertTrue("blank line", line.isNotBlank())
            assertTrue("too long: $line", line.length <= 40)
        }
        val text = lines.joinToString(" | ")
        assertTrue(text, text.contains("会冻结"))
        assertTrue(text, text.contains("网络"))
        assertTrue(text, text.contains("亮屏"))
    }

    @Test
    fun `never mode ignores drop masks`() {
        val app = AppPolicy(
            mode = FreezeMode.NEVER,
            maskDrop = SceneBit.toMask(listOf(SceneBit.NO_RESTRICT)),
        )
        val text = Explain.appLines("com.never", app, followGlobal).joinToString(" | ")
        assertFalse(text, text.contains("照冻"))
    }

    @Test
    fun `master switch off is announced first`() {
        val lines = Explain.appLines(
            "com.fast",
            AppPolicy(mode = FreezeMode.IMMEDIATE),
            GlobalPolicy(enabled = false),
        )
        assertTrue(lines[0], lines[0].contains("总开关"))
    }

    // ------------------------------------------------------------ reason label

    @Test
    fun `reason label renders zero, single and combined masks`() {
        assertEquals("无原因位", Explain.reasonLabel(0))
        assertEquals(SceneBit.AUDIO.label, Explain.reasonLabel(SceneBit.AUDIO.value))
        assertEquals(
            "音频播放 + 白名单",
            Explain.reasonLabel(SceneBit.AUDIO.value or SceneBit.NO_RESTRICT.value),
        )
    }

    @Test
    fun `reason label keeps unknown bits visible`() {
        val text = Explain.reasonLabel(SceneBit.AUDIO.value or Int.MIN_VALUE)
        assertTrue(text, text.contains(SceneBit.AUDIO.label))
        assertTrue(text, text.contains("未知"))
    }

    // -------------------------------------------------------------- bit effect

    @Test
    fun `bit effect distinguishes keep, drop and unset`() {
        val none = Explain.bitEffect(SceneBit.AUDIO, BitRole.NONE)
        val keep = Explain.bitEffect(SceneBit.AUDIO, BitRole.KEEP)
        val drop = Explain.bitEffect(SceneBit.AUDIO, BitRole.DROP)
        assertNotEquals(keep, drop)
        assertNotEquals(none, keep)
        assertNotEquals(none, drop)
        assertTrue(keep, keep.contains("不会冻"))
        assertTrue(drop, drop.contains("不允许") || drop.contains("锁"))
        assertTrue(drop, drop.length <= 40)
    }

    @Test
    fun `drop on a safe bit reads as a plain override`() {
        val text = Explain.bitEffect(SceneBit.GPS, BitRole.DROP)
        assertTrue(text, text.contains("丢弃"))
        assertFalse(text, text.contains("不允许"))
    }

    // ---------------------------------------------------------- filter preview

    @Test
    fun `filter preview follows the engine for every mode`() {
        val policy = policyOf(
            "com.never" to AppPolicy(mode = FreezeMode.NEVER),
            "com.keep" to AppPolicy(
                mode = FreezeMode.CUSTOM,
                maskKeep = SceneBit.AUDIO.value,
            ),
            "com.drop" to AppPolicy(
                mode = FreezeMode.IMMEDIATE,
                maskDrop = SceneBit.NO_RESTRICT.value,
            ),
            "com.plain" to AppPolicy(mode = FreezeMode.CUSTOM),
        )

        val missing = Explain.filterPreview(policy, "com.missing", SceneBit.AUDIO.value, true)
        assertTrue(missing, missing.contains("跟随系统"))

        val never = Explain.filterPreview(policy, "com.never", SceneBit.AUDIO.value, true)
        assertTrue(never, never.contains("不会冻结"))

        val keep = Explain.filterPreview(policy, "com.keep", SceneBit.AUDIO.value, true)
        assertTrue(keep, keep.contains("不会冻结"))
        assertTrue(keep, keep.contains(SceneBit.AUDIO.label))

        val drop = Explain.filterPreview(policy, "com.drop", SceneBit.NO_RESTRICT.value, false)
        assertTrue(drop, drop.contains("会冻结"))
        assertFalse(drop, drop.contains("不会冻结"))
        assertTrue(drop, drop.contains(SceneBit.NO_RESTRICT.label))

        val plain = Explain.filterPreview(policy, "com.plain", SceneBit.GPS.value, true)
        assertTrue(plain, plain.contains("没有命中"))
        assertTrue(plain, plain.contains("true"))
    }

    @Test
    fun `keep wins over drop for the same reason in the preview`() {
        val policy = policyOf(
            "com.both" to AppPolicy(
                mode = FreezeMode.CUSTOM,
                maskKeep = SceneBit.AUDIO.value,
                maskDrop = SceneBit.AUDIO.value,
            ),
        )
        val text = Explain.filterPreview(policy, "com.both", SceneBit.AUDIO.value, true)
        assertTrue(text, text.contains("不会冻结"))
        assertTrue(text, text.contains("keep"))
    }

    @Test
    fun `filter preview flips only the override when polarity is inverted`() {
        val base = policyOf(
            "com.keep" to AppPolicy(
                mode = FreezeMode.CUSTOM,
                maskKeep = SceneBit.AUDIO.value,
            ),
        )
        val normal = Explain.filterPreview(base, "com.keep", SceneBit.AUDIO.value, true)
        assertTrue(normal, normal.contains("不会冻结"))

        val invertedPolicy = base.withGlobal(GlobalPolicy(filterPolarityInverted = true))
        val inverted = Explain.filterPreview(
            invertedPolicy,
            "com.keep",
            SceneBit.AUDIO.value,
            true,
        )
        assertTrue(inverted, inverted.contains("会冻结"))
        assertFalse(inverted, inverted.contains("不会冻结"))

        // No hit is not an override: the system answer survives the flip.
        val noHit = Explain.filterPreview(invertedPolicy, "com.keep", SceneBit.GPS.value, false)
        assertTrue(noHit, noHit.contains("没有命中"))
        assertTrue(noHit, noHit.contains("false"))

        // NEVER is not an override either.
        val neverPolicy = policyOf("com.never" to AppPolicy(mode = FreezeMode.NEVER))
            .withGlobal(GlobalPolicy(filterPolarityInverted = true))
        val never = Explain.filterPreview(neverPolicy, "com.never", SceneBit.AUDIO.value, true)
        assertTrue(never, never.contains("不会冻结"))
    }

    @Test
    fun `filter preview returns the original answer when the master switch is off`() {
        val policy = policyOf("com.never" to AppPolicy(mode = FreezeMode.NEVER))
            .withGlobal(GlobalPolicy(enabled = false))
        val text = Explain.filterPreview(policy, "com.never", SceneBit.AUDIO.value, true)
        assertTrue(text, text.contains("跟随系统"))
        assertTrue(text, text.contains("true"))
        assertFalse(text, text.contains("不会冻结"))
    }

    // -------------------------------------------------------- resource / thaw

    @Test
    fun `resource rows differ from the follow value`() {
        for (key in ResourceSwitches.ORDER.map { it.first }) {
            val follow = Explain.resourceLine(key, null)
            val on = Explain.resourceLine(key, true)
            val off = Explain.resourceLine(key, false)
            assertTrue("$key follow: $follow", follow.contains("跟随系统"))
            assertNotEquals("$key follow/on", follow, on)
            assertNotEquals("$key follow/off", follow, off)
            assertNotEquals("$key on/off", on, off)
            assertTrue("$key too long: $on", on.length <= 40)
            assertTrue("$key too long: $off", off.length <= 40)
        }
    }

    @Test
    fun `thaw rows differ from the follow value`() {
        for (key in ThawTriggers.ORDER.map { it.first }) {
            val follow = Explain.thawLine(key, null)
            val on = Explain.thawLine(key, true)
            val off = Explain.thawLine(key, false)
            assertTrue("$key follow: $follow", follow.contains("跟随系统"))
            assertNotEquals("$key follow/on", follow, on)
            assertNotEquals("$key follow/off", follow, off)
            assertNotEquals("$key on/off", on, off)
            assertTrue("$key too long: $on", on.length <= 40)
            assertTrue("$key too long: $off", off.length <= 40)
        }
    }

    @Test
    fun `explain never mentions parameter names in app lines`() {
        val app = AppPolicy(
            mode = FreezeMode.CUSTOM,
            maskKeep = SceneBit.toMask(listOf(SceneBit.AUDIO)),
            maskDrop = SceneBit.toMask(listOf(SceneBit.NO_RESTRICT)),
        )
        val text = Explain.appLines("com.custom", app, followGlobal).joinToString(" | ")
        assertFalse(text, text.contains("maskKeep"))
        assertFalse(text, text.contains("maskDrop"))
        assertFalse(text, text.contains("!"))
    }
}
