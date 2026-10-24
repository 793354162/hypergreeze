package dev.hypergreeze.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SceneBitTest {

    @Test
    fun `every bit value is a distinct power of two`() {
        val values = SceneBit.entries.map { it.value }
        assertEquals(values.size, values.toSet().size)
        for (value in values) {
            assertTrue("$value is not a single bit", value > 0 && (value and (value - 1)) == 0)
        }
    }

    @Test
    fun `mask round trip`() {
        val all = SceneBit.entries.toSet()
        val mask = SceneBit.toMask(all)
        assertEquals(all, SceneBit.fromMask(mask))
        assertEquals(0, SceneBit.toMask(emptyList()))
        assertTrue(SceneBit.fromMask(0).isEmpty())
    }

    @Test
    fun `locked drop bits never intersect the default drop set`() {
        assertTrue(SceneBit.LOCKED_DROP.intersect(SceneBit.DEFAULT_DROP).isEmpty())
        // DEFAULT_KEEP may overlap LOCKED_DROP by design: "keep audio" is exactly what we want.
        assertTrue(SceneBit.DEFAULT_KEEP.intersect(SceneBit.LOCKED_DROP).isNotEmpty())
    }

    @Test
    fun `dangerous bits are the ones that break visible use`() {
        val locked = SceneBit.LOCKED_DROP
        assertTrue(SceneBit.AUDIO in locked)
        assertTrue(SceneBit.CAMERA in locked)
        assertTrue(SceneBit.TOP in locked)
        assertTrue(SceneBit.IME in locked)
        assertTrue(SceneBit.VISIBLE in locked)
    }

    @Test
    fun `describe renders chinese labels or a fallback`() {
        assertEquals("无", SceneBit.describe(0))
        assertTrue(SceneBit.describe(SceneBit.AUDIO.value).contains(SceneBit.AUDIO.label))
        val combined = SceneBit.describe(SceneBit.AUDIO.value or SceneBit.CAMERA.value)
        assertTrue(combined.contains(SceneBit.AUDIO.label))
        assertTrue(combined.contains(SceneBit.CAMERA.label))
    }

    @Test
    fun `group enumeration covers every bit exactly once`() {
        val grouped = SceneBit.Group.entries.flatMap { group ->
            SceneBit.entries.filter { it.group == group }
        }
        assertEquals(SceneBit.entries.size, grouped.size)
    }

    @Test
    fun `withMask strips locked bits`() {
        val app = AppPolicy(mode = FreezeMode.CUSTOM).withMask(
            keep = setOf(SceneBit.AUDIO),
            drop = setOf(SceneBit.TOP, SceneBit.NO_RESTRICT),
        )
        assertEquals(SceneBit.toMask(listOf(SceneBit.AUDIO)), app.maskKeep)
        assertEquals(SceneBit.toMask(listOf(SceneBit.NO_RESTRICT)), app.maskDrop)
    }

    @Test
    fun `freeze mode wire names are stable`() {
        assertEquals(FreezeMode.FOLLOW, FreezeMode.fromWire("follow"))
        assertEquals(FreezeMode.NEVER, FreezeMode.fromWire("never"))
        assertEquals(FreezeMode.IMMEDIATE, FreezeMode.fromWire("immediate"))
        assertEquals(FreezeMode.CUSTOM, FreezeMode.fromWire("custom"))
        assertEquals(FreezeMode.FOLLOW, FreezeMode.fromWire("nonsense"))
        assertEquals(FreezeMode.FOLLOW, FreezeMode.fromWire(null))
        assertNotNull(FreezeMode.ordered)
        assertEquals(4, FreezeMode.ordered.size)
    }

    @Test
    fun `freeze state ordinals match the greeze enum`() {
        assertEquals(FreezeState.UNFROZEN, FreezeState.fromOrdinal(0))
        assertEquals(FreezeState.PARTIALLY_FROZEN, FreezeState.fromOrdinal(1))
        assertEquals(FreezeState.FULLY_FROZEN, FreezeState.fromOrdinal(2))
        assertEquals(FreezeState.UNKNOWN, FreezeState.fromOrdinal(9))
    }
}
