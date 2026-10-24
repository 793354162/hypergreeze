package dev.hypergreeze.module

import dev.hypergreeze.core.AppPolicy
import dev.hypergreeze.core.FreezeMode
import dev.hypergreeze.core.GlobalPolicy
import dev.hypergreeze.core.Policy
import dev.hypergreeze.core.SceneBit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The decision table. These are the assertions that matter on a device: if `filterVerdict` is
 * wrong, masks do the opposite of what the user asked for.
 */
class PolicyEngineTest {

    private val follow = PolicyEngine(Policy.EMPTY)

    private val policy = Policy.EMPTY
        .withApp(
            "com.never",
            AppPolicy(mode = FreezeMode.NEVER),
        )
        .withApp(
            "com.immediate",
            AppPolicy(mode = FreezeMode.IMMEDIATE, delayMs = 800L, thawCooldownMs = 5_000L),
        )
        .withApp(
            "com.masked",
            AppPolicy(
                mode = FreezeMode.CUSTOM,
                maskKeep = SceneBit.toMask(listOf(SceneBit.AUDIO, SceneBit.CAMERA)),
                maskDrop = SceneBit.toMask(listOf(SceneBit.NO_RESTRICT, SceneBit.FOREGROUND_SERVICE)),
            ),
        )
        .withApp(
            "com.proc",
            AppPolicy(
                mode = FreezeMode.IMMEDIATE,
                processes = mapOf("com.proc:push" to FreezeMode.NEVER),
            ),
        )

    private val engine = PolicyEngine(policy)

    @Test
    fun `follow apps are invisible to the module`() {
        assertEquals(FreezeMode.FOLLOW, engine.mode("com.unknown"))
        assertTrue(engine.filterVerdict("com.unknown", SceneBit.AUDIO.value, true))
        assertFalse(engine.filterVerdict("com.unknown", SceneBit.AUDIO.value, false))
        assertFalse(engine.forceFreezeOnBackground("com.unknown"))
        assertFalse(engine.neverFreeze("com.unknown"))
        assertNull(engine.resource("net", "com.unknown"))
        assertNull(engine.thawTrigger("screenOn", "com.unknown"))
    }

    @Test
    fun `never mode refuses every freeze path`() {
        assertTrue(engine.neverFreeze("com.never"))
        assertFalse(engine.filterVerdict("com.never", SceneBit.AUDIO.value, true))
        assertFalse(engine.filterVerdict("com.never", 0, true))
        assertTrue(engine.whitelistVerdict("com.never", false))
        assertFalse(engine.forceFreezeOnBackground("com.never"))
    }

    @Test
    fun `immediate mode forces freeze only when the global switch is on`() {
        assertTrue(engine.forceFreezeOnBackground("com.immediate"))
        val off = PolicyEngine(policy.withGlobal(GlobalPolicy(enabled = true, forceFreezeOnBackground = false)))
        assertFalse(off.forceFreezeOnBackground("com.immediate"))
        val disabled = PolicyEngine(policy.withGlobal(GlobalPolicy(enabled = false)))
        assertFalse(disabled.forceFreezeOnBackground("com.immediate"))
        // Master switch off = pass-through, never a forced false.
        assertTrue(disabled.filterVerdict("com.never", 0, true))
        assertFalse(disabled.filterVerdict("com.never", 0, false))
    }

    @Test
    fun `keep bits force the exemption and drop bits drop it`() {
        assertFalse(engine.filterVerdict("com.masked", SceneBit.AUDIO.value, true))
        assertTrue(engine.filterVerdict("com.masked", SceneBit.NO_RESTRICT.value, false))
        // Neither configured -> untouched.
        assertTrue(engine.filterVerdict("com.masked", SceneBit.GPS.value, true))
        assertFalse(engine.filterVerdict("com.masked", SceneBit.GPS.value, false))
    }

    @Test
    fun `keep wins when the same reason is in both masks`() {
        val conflicting = PolicyEngine(
            Policy.EMPTY.withApp(
                "p",
                AppPolicy(
                    mode = FreezeMode.CUSTOM,
                    maskKeep = SceneBit.AUDIO.value,
                    maskDrop = SceneBit.AUDIO.value,
                ),
            ),
        )
        assertFalse(conflicting.filterVerdict("p", SceneBit.AUDIO.value, true))
    }

    @Test
    fun `polarity switch only inverts the module override`() {
        val inverted = PolicyEngine(
            policy.withGlobal(GlobalPolicy(filterPolarityInverted = true)),
        )
        // keep bit -> normally false -> inverted true
        assertTrue(inverted.filterVerdict("com.masked", SceneBit.AUDIO.value, true))
        // drop bit -> normally true -> inverted false
        assertFalse(inverted.filterVerdict("com.masked", SceneBit.NO_RESTRICT.value, false))
        // no override -> original, never inverted
        assertTrue(inverted.filterVerdict("com.masked", SceneBit.GPS.value, true))
        assertFalse(inverted.filterVerdict("com.masked", SceneBit.GPS.value, false))
        // NEVER is not an "override", so it stays false
        assertFalse(inverted.filterVerdict("com.never", 0, true))
    }

    @Test
    fun `delay falls back to the global default`() {
        val withDefault = PolicyEngine(policy.withGlobal(GlobalPolicy(defaultDelayMs = 250L)))
        assertEquals(800L, withDefault.delayMs("com.immediate"))
        assertEquals(250L, withDefault.delayMs("com.masked"))
        assertEquals(5_000L, withDefault.cooldownMs("com.immediate"))
        assertEquals(0L, withDefault.cooldownMs("com.masked"))
    }

    @Test
    fun `process override wins over the app mode`() {
        assertTrue(engine.neverFreeze("com.proc", "com.proc:push"))
        assertFalse(engine.neverFreeze("com.proc", "com.proc:main"))
        assertFalse(engine.neverFreeze("com.proc"))
        assertEquals(FreezeMode.NEVER, engine.mode("com.proc", "com.proc:push"))
        assertEquals(FreezeMode.IMMEDIATE, engine.mode("com.proc", "com.proc:main"))
    }

    @Test
    fun `wakeup verdict honours the external wake trigger`() {
        val noWake = PolicyEngine(
            policy.withApp(
                "p",
                AppPolicy(mode = FreezeMode.NEVER, thawOn = dev.hypergreeze.core.ThawTriggers(externalWake = false)),
            ),
        )
        assertFalse(noWake.wakeUpVerdict("p", true))
        // unset -> original
        assertTrue(engine.wakeUpVerdict("com.masked", true))
        assertFalse(engine.wakeUpVerdict("com.masked", false))
        // follow -> original
        assertTrue(engine.wakeUpVerdict("com.unknown", true))
    }

    @Test
    fun `whitelist verdict drops the exemption for immediate apps`() {
        val dropper = PolicyEngine(
            policy.withApp(
                "p",
                AppPolicy(
                    mode = FreezeMode.IMMEDIATE,
                    maskDrop = SceneBit.NO_RESTRICT.value,
                ),
            ),
        )
        assertFalse(dropper.whitelistVerdict("p", true))
        // without the drop bit the system answer stands
        assertTrue(dropper.whitelistVerdict("com.immediate", true))
        assertFalse(dropper.whitelistVerdict("com.immediate", false))
    }

    @Test
    fun `resource and thaw switches are null by default`() {
        val withNet = PolicyEngine(
            policy.withApp(
                "p",
                AppPolicy(
                    mode = FreezeMode.CUSTOM,
                    resources = dev.hypergreeze.core.ResourceSwitches(net = true),
                ),
            ),
        )
        assertTrue(withNet.resource("net", "p") == true)
        assertNull(engine.resource("net", "com.masked"))
        assertNull(engine.thawTrigger("charging", "com.masked"))
    }

    @Test
    fun `summary is stable`() {
        val text = engine.summary()
        assertTrue(text.contains("enabled=true"))
        assertTrue(text.contains("never=1"))
        assertTrue(text.contains("immediate=2"))
    }
}
