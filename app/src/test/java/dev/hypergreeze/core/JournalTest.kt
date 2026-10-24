package dev.hypergreeze.core

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 纯 JVM 单测：`org.json` 由真实依赖提供（见 app/build.gradle.kts 的 testImplementation），所以
 * 这里跑的编解码和模块在 `system_server` 里跑的是同一份代码。
 *
 * 断言的重点不是「函数返回值对不对」，而是「账本能不能被信」：脏数据不许抛、不许变成一条
 * 看起来成功的记录、不许把可还原的数量算多。
 */
class JournalTest {

    // ------------------------------------------------------------------ 编解码

    @Test
    fun `round trip keeps every field including nulls and a chinese target`() {
        val entries = listOf(
            JournalEntry(
                seq = 1L,
                ts = 1_700_000_000_000L,
                op = EffectOp.FREEZE,
                target = "com.tencent.mm (u10123)",
                uid = 10123,
                pkg = "com.tencent.mm",
                before = "false",
                after = "true",
                ok = true,
                revision = 7L,
                note = "首屏冻结",
            ),
            // 全 null + 失败的一行：可空字段和 ok=false 都必须能原样回来
            JournalEntry(
                seq = 2L,
                ts = 1_700_000_001_000L,
                op = EffectOp.LIST_PATCH,
                target = "mNoRestrictAppSet",
                after = "size=3",
                ok = false,
                revision = 8L,
            ),
            JournalEntry(
                seq = 3L,
                ts = 1_700_000_002_000L,
                op = EffectOp.FREEZE_BINDER,
                target = "全局 binder 开关",
                before = "1",
                after = "0",
            ),
        )

        assertEquals(entries, JournalCodec.decode(JournalCodec.encode(entries)))
    }

    @Test
    fun `encode omits absent optional fields but keeps identity fields`() {
        val minimal = JournalCodec.encode(listOf(JournalEntry(op = EffectOp.NET_RESTRICT, target = "com.x")))
        assertFalse(minimal.contains("\"n\":"))
        assertFalse(minimal.contains("\"p\":"))
        assertFalse(minimal.contains("\"b\":"))
        assertFalse(minimal.contains("\"a\":"))
        assertTrue(minimal.contains("\"op\":\"net\""))
        assertTrue(minimal.contains("\"ok\":true"))

        // 有值时必须在，别把「省略」写成「永远不写」
        val full = JournalCodec.encode(
            listOf(
                JournalEntry(op = EffectOp.NET_RESTRICT, pkg = "com.x", before = "t", after = "f", note = "n1"),
            ),
        )
        assertTrue(full.contains("\"p\":\"com.x\""))
        assertTrue(full.contains("\"b\":\"t\""))
        assertTrue(full.contains("\"a\":\"f\""))
        assertTrue(full.contains("\"n\":\"n1\""))
    }

    @Test
    fun `decode never throws and returns empty on garbage`() {
        assertTrue(JournalCodec.decode(null).isEmpty())
        assertTrue(JournalCodec.decode("").isEmpty())
        assertTrue(JournalCodec.decode("   ").isEmpty())
        assertTrue(JournalCodec.decode("{not json").isEmpty())
        assertTrue(JournalCodec.decode("{}").isEmpty())
        assertTrue(JournalCodec.decode("\"just a string\"").isEmpty())
        assertTrue(JournalCodec.decode("[null,null]").isEmpty())
        assertTrue(JournalCodec.decode("[]").isEmpty())
        assertTrue(JournalCodec.decode("[1,2,3]").isEmpty())
        assertTrue(JournalCodec.decode("null").isEmpty())
    }

    @Test
    fun `decode skips unknown or missing ops and keeps the rest`() {
        val raw = """
            [
              {"s":1,"op":"freeze","t":"a"},
              {"s":2,"op":"teleport","t":"b"},
              {"s":3,"t":"no-op-field"},
              {"s":4,"op":"NET","t":"wrong-case"},
              {"s":5,"op":"net","t":"c"},
              {"s":6,"op":null,"t":"null-op"},
              "not an object"
            ]
        """.trimIndent()

        val decoded = JournalCodec.decode(raw)
        assertEquals(2, decoded.size)
        assertEquals(listOf(1L, 5L), decoded.map { it.seq })
        assertEquals(listOf("a", "c"), decoded.map { it.target })
    }

    @Test
    fun `decode falls back to defaults when a field has the wrong type`() {
        val raw = """[{"s":"oops","ts":null,"op":"net","u":"x","t":7,"b":[],"ok":1,"r":"zz","n":{}}]"""

        val decoded = JournalCodec.decode(raw)
        assertEquals(1, decoded.size)
        val entry = decoded.first()
        assertEquals(EffectOp.NET_RESTRICT, entry.op)
        assertEquals(0L, entry.seq)
        assertEquals(0L, entry.ts)
        assertEquals(0, entry.uid)
        assertEquals("", entry.target)
        assertNull(entry.before)
        assertNull(entry.note)
        assertTrue("垃圾 ok 值不能读成「调用失败」", entry.ok)
        assertEquals(0L, entry.revision)
    }

    @Test
    fun `decode does not apply the ring capacity`() {
        val array = JSONArray()
        for (i in 1..300) array.put(JSONObject().put("s", i.toLong()).put("op", "freeze"))
        assertEquals(300, JournalCodec.decode(array.toString()).size)
    }

    @Test
    fun `decode keeps only the last 200 rows when the document floods past 1000`() {
        val array = JSONArray()
        for (i in 1..1200) {
            array.put(
                JSONObject()
                    .put("s", i.toLong())
                    .put("ts", 1_700_000_000_000L + i)
                    .put("op", "freeze")
                    .put("t", "com.x")
                    .put("ok", true),
            )
        }

        val decoded = JournalCodec.decode(array.toString())
        assertEquals(200, decoded.size)
        assertEquals(1001L, decoded.first().seq)
        assertEquals(1200L, decoded.last().seq)
    }

    // ------------------------------------------------------------------ EffectOp

    @Test
    fun `fromWire is exact and rejects unknown or mistyped values`() {
        assertEquals(EffectOp.NET_RESTRICT, EffectOp.fromWire("net"))
        assertEquals(EffectOp.FREEZE_BINDER, EffectOp.fromWire("freezeBinder"))
        assertNull(EffectOp.fromWire("NET"))
        assertNull(EffectOp.fromWire("Freeze"))
        assertNull(EffectOp.fromWire("freeze "))
        assertNull(EffectOp.fromWire("teleport"))
        assertNull(EffectOp.fromWire(""))
        assertNull(EffectOp.fromWire(null))
    }

    // ------------------------------------------------------------------ 容量与顺序

    @Test
    fun `add stamps a monotonic seq and a timestamp`() {
        val ring = JournalRing(10)
        val first = ring.add(JournalEntry(op = EffectOp.FREEZE))
        val second = ring.add(JournalEntry(op = EffectOp.THAW))
        val third = ring.add(JournalEntry(op = EffectOp.NET_RESTRICT, ts = 42L))

        assertEquals(1L, first.seq)
        assertEquals(2L, second.seq)
        assertEquals(3L, third.seq)
        assertTrue(first.ts > 0L)
        assertTrue(second.ts > 0L)
        assertEquals("调用方给了 ts 就不覆盖", 42L, third.ts)
    }

    @Test
    fun `capacity drops the oldest rows`() {
        val ring = JournalRing(3)
        repeat(5) { ring.add(JournalEntry(op = EffectOp.FREEZE)) }

        assertEquals(3, ring.size)
        assertEquals(listOf(3L, 4L, 5L), ring.snapshot().map { it.seq })
    }

    @Test
    fun `newestFirst is the reverse of snapshot`() {
        val ring = JournalRing(10)
        listOf("a", "b", "c").forEach { ring.add(JournalEntry(target = it)) }

        assertEquals(listOf("a", "b", "c"), ring.snapshot().map { it.target })
        assertEquals(listOf("c", "b", "a"), ring.newestFirst().map { it.target })
    }

    @Test
    fun `clear drops rows but keeps the sequence monotonic`() {
        val ring = JournalRing(5)
        ring.add(JournalEntry(op = EffectOp.FREEZE))
        ring.add(JournalEntry(op = EffectOp.FREEZE))
        ring.clear()

        assertEquals(0, ring.size)
        assertTrue(ring.snapshot().isEmpty())
        assertEquals(3L, ring.add(JournalEntry(op = EffectOp.FREEZE)).seq)
    }

    // ------------------------------------------------------------------ 可还原统计

    @Test
    fun `undoableCount only counts reversible successful rows`() {
        val ring = JournalRing(20)
        ring.add(JournalEntry(op = EffectOp.JOB_RESTRICT, target = "job", ok = true))
        ring.add(JournalEntry(op = EffectOp.FREEZE, target = "freeze-failed", ok = false))
        ring.add(JournalEntry(op = EffectOp.FREEZE, target = "freeze-ok", ok = true))
        ring.add(JournalEntry(op = EffectOp.THAW, target = "thaw", ok = true))
        ring.add(JournalEntry(op = EffectOp.NET_RESTRICT, target = "net", ok = true))

        assertEquals(5, ring.size)
        assertEquals(2, ring.undoableCount)
    }

    @Test
    fun `undoableSummary aggregates by op in first seen order`() {
        val ring = JournalRing(20)
        ring.add(JournalEntry(op = EffectOp.FREEZE))
        ring.add(JournalEntry(op = EffectOp.NET_RESTRICT))
        ring.add(JournalEntry(op = EffectOp.FREEZE))
        ring.add(JournalEntry(op = EffectOp.JOB_RESTRICT))
        ring.add(JournalEntry(op = EffectOp.WAKELOCK_LIMIT, ok = false))

        assertEquals(listOf("冻结 ×2", "网络限制 ×1"), ring.undoableSummary())
    }

    // ------------------------------------------------------------------ 恢复现场

    @Test
    fun `restoring a persisted ledger keeps the original seq numbers`() {
        val live = JournalRing(5)
        repeat(3) { live.add(JournalEntry(op = EffectOp.FREEZE)) }

        val restored = JournalRing(5)
        for (entry in JournalCodec.decode(JournalCodec.encode(live.snapshot()))) restored.add(entry)

        assertEquals(listOf(1L, 2L, 3L), restored.snapshot().map { it.seq })
        assertEquals("恢复后新行要接着往下数", 4L, restored.add(JournalEntry(op = EffectOp.THAW)).seq)
        assertEquals(4, restored.size)
    }

    // ------------------------------------------------------------------ 人读摘要

    @Test
    fun `oneLine reads like a ui row`() {
        val entry = JournalEntry(
            ts = 1_700_000_000_000L,
            op = EffectOp.NET_RESTRICT,
            target = "com.x",
            uid = 10123,
            before = "true",
            after = "false",
            ok = true,
        )

        val line = entry.oneLine
        assertTrue(line, line.matches(Regex("""\d{2}:\d{2}:\d{2} 网络限制 com\.x \(u10123\) true→false ok""")))

        val failed = JournalEntry(op = EffectOp.FREEZE, target = "p", ok = false)
        assertTrue(failed.oneLine.endsWith(" fail"))

        val unstamped = JournalEntry()
        assertTrue(unstamped.oneLine.startsWith("--:--:-- 冻结"))

        val noted = JournalEntry(op = EffectOp.LIST_PATCH, target = "set", note = "全量写入")
        assertTrue(noted.oneLine.endsWith("— 全量写入"))

        // target 里已经带了同一个 uid 时不再重复补，两种写法混用不能读成 "(u10123) (u10123)"
        val dedup = JournalEntry(op = EffectOp.NET_RESTRICT, target = "com.x (u10123)", uid = 10123)
        assertTrue(dedup.oneLine.contains("com.x (u10123)"))
        assertFalse(dedup.oneLine.contains("(u10123) (u10123)"))
    }
}
