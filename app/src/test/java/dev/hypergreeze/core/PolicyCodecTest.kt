package dev.hypergreeze.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the policy document. `org.json` is provided by a real artifact on the unit
 * test classpath (see app/build.gradle.kts), so this exercises the same code path the module uses
 * inside system_server.
 */
class PolicyCodecTest {

    private fun sample(): Policy = Policy.EMPTY
        .withApp(
            "com.tencent.mm",
            AppPolicy(
                mode = FreezeMode.CUSTOM,
                delayMs = 500L,
                thawCooldownMs = 30_000L,
                maskKeep = SceneBit.toMask(listOf(SceneBit.AUDIO, SceneBit.CAMERA)),
                maskDrop = SceneBit.toMask(listOf(SceneBit.NO_RESTRICT)),
                processes = mapOf("com.tencent.mm:push" to FreezeMode.NEVER),
                resources = ResourceSwitches(net = true, job = false),
                thawOn = ThawTriggers(screenOn = true, externalWake = false),
                note = "测试",
            ),
        )
        .withApp("com.example.never", AppPolicy(mode = FreezeMode.NEVER))
        .withGlobal(GlobalPolicy(enabled = true, defaultDelayMs = 200L, patchLists = true))

    @Test
    fun `round trip preserves every field`() {
        val original = sample()
        val decoded = PolicyCodec.decode(PolicyCodec.encode(original))

        assertEquals(original.global.enabled, decoded.global.enabled)
        assertEquals(200L, decoded.global.defaultDelayMs)
        assertTrue(decoded.global.patchLists)
        assertEquals(2, decoded.apps.size)

        val wechat = decoded.apps.getValue("com.tencent.mm")
        assertEquals(FreezeMode.CUSTOM, wechat.mode)
        assertEquals(500L, wechat.delayMs)
        assertEquals(30_000L, wechat.thawCooldownMs)
        assertEquals(setOf(SceneBit.AUDIO, SceneBit.CAMERA), wechat.keepBits)
        assertEquals(setOf(SceneBit.NO_RESTRICT), wechat.dropBits)
        assertEquals(mapOf("com.tencent.mm:push" to FreezeMode.NEVER), wechat.processes)
        assertEquals(true, wechat.resources.net)
        assertEquals(false, wechat.resources.job)
        assertNull(wechat.resources.alarm)
        assertEquals(true, wechat.thawOn.screenOn)
        assertEquals(false, wechat.thawOn.externalWake)
        assertNull(wechat.thawOn.charging)
        assertEquals("测试", wechat.note)
    }

    @Test
    fun `malformed documents fall back to empty policy instead of partially applying`() {
        assertEquals(Policy.EMPTY, PolicyCodec.decode("{not json"))
        assertEquals(Policy.EMPTY, PolicyCodec.decode("[]"))
        assertEquals(Policy.EMPTY, PolicyCodec.decode(null))
        assertEquals(Policy.EMPTY, PolicyCodec.decode(""))
    }

    @Test
    fun `unknown keys are ignored and defaults stay sane`() {
        val decoded = PolicyCodec.decode(
            """{"v":2,"rev":7,"global":{"enabled":false,"future":"x"},"apps":{},"unknown":1}""",
        )
        assertFalse(decoded.global.enabled)
        assertEquals(7L, decoded.revision)
        assertTrue(decoded.apps.isEmpty())
    }

    @Test
    fun `locked drop bits are stripped even when hand edited`() {
        val locked = SceneBit.toMask(listOf(SceneBit.AUDIO, SceneBit.TOP, SceneBit.VISIBLE))
        val allowed = SceneBit.toMask(listOf(SceneBit.NO_RESTRICT))
        val mask = locked or allowed
        val raw = "{\"apps\":{\"p\":{\"mode\":\"custom\",\"drop\":" + mask + "}}}"
        val decoded = PolicyCodec.decode(raw)
        val drop = decoded.apps.getValue("p").maskDrop
        assertEquals(allowed, drop)
        assertEquals(0, drop and locked)
    }

    @Test
    fun `numeric extremes are coerced into range`() {
        val decoded = PolicyCodec.decode(
            """{"apps":{"p":{"mode":"immediate","delayMs":-99,"thawCooldownMs":9999999999}}}""",
        )
        val app = decoded.apps.getValue("p")
        assertEquals(0L, app.delayMs)
        assertEquals(3_600_000L, app.thawCooldownMs)
    }

    @Test
    fun `follow entries are not persisted`() {
        val policy = Policy.EMPTY.withApp("p", AppPolicy(mode = FreezeMode.FOLLOW))
        val decoded = PolicyCodec.decode(PolicyCodec.encode(policy))
        assertTrue(decoded.apps.isEmpty())
    }

    @Test
    fun `editing bumps revision and timestamp`() {
        val before = Policy.EMPTY
        val after = before.withApp("p", AppPolicy(mode = FreezeMode.NEVER))
        assertEquals(before.revision + 1, after.revision)
        assertTrue(after.updatedAt >= before.updatedAt)
        assertEquals(1, after.configuredCount)
        assertEquals(after.revision, after.withoutApp("p").revision - 1)
        assertTrue(after.withoutApp("p").apps.isEmpty())
    }

    @Test
    fun `global encode omits null tri-state switches`() {
        val encoded = PolicyCodec.encode(Policy.EMPTY)
        assertFalse(encoded.contains("applyFreezeBinder"))
        assertFalse(encoded.contains("defaultDelayMs"))
    }
}
