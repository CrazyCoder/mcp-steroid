/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.freeze

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IdeMemoryTest {
    private val mb = 1024L * 1024
    private val t0 = 1_000_000_000L

    private fun snapshot(afterGcMb: Long?, maxMb: Long = 1000) = MemorySnapshot(
        heapUsed = 700 * mb, heapCommitted = 900 * mb, heapMax = maxMb * mb, heapAfterGc = afterGcMb?.times(mb),
        directTotal = 20 * mb, directFileCache = 15 * mb, nonHeap = 300 * mb, threads = 120, mapped = 50 * mb,
        osRam = 1500 * mb, osFileMappings = 200 * mb, osRamAndSwap = 1600 * mb, gcShare = 0.25, gcWindowMs = 60_000,
    )

    /** The episode after signals at these offsets from [t0], each with the heap [afterGcMb] after its GC. */
    private fun signals(vararg offsetsMs: Long, afterGcMb: Long = 300): MemoryEpisode? =
        offsetsMs.fold(null as MemoryEpisode?) { e, at -> IdeMemory.next(e, t0 + at, snapshot(afterGcMb)) }

    @Test
    fun `a burst of signals within the throttle counts once and is not pressure`() {
        val e = signals(0, 1_000, 5_000, 19_000)!!
        assertEquals(1, e.signals.size)
        assertFalse(e.told)
    }

    @Test
    fun `five throttled signals within the window are pressure`() {
        val four = signals(0, 30_000, 60_000, 90_000)!!
        assertFalse(four.told)
        val five = IdeMemory.next(four, t0 + 120_000, snapshot(300))
        assertEquals(5, five.signals.size)
        assertTrue(five.told)
    }

    @Test
    fun `one signal with the heap nearly full after its GC is pressure`() {
        assertTrue(signals(0, afterGcMb = 900)!!.told)
        assertFalse(signals(0, afterGcMb = 800)!!.told)
    }

    @Test
    fun `a quiet window ends the episode and the next signal starts a new one`() {
        val first = signals(0, afterGcMb = 900)!!
        val second = IdeMemory.next(first, t0 + IdeMemory.WINDOW_MS + 1, snapshot(300))
        assertEquals(first.id + 1, second.id)
        assertEquals(1, second.signals.size)
        assertFalse(second.told)
    }

    @Test
    fun `signals older than the window drop out of the count`() {
        val minute = 60_000L
        // Five signals, but the first is more than 15 minutes older than the fifth.
        val e = signals(0, 3 * minute, 6 * minute, 9 * minute, 16 * minute)!!
        assertEquals(listOf(3, 6, 9, 16).map { t0 + it * minute }, e.signals)
        assertFalse(e.told)
    }

    @Test
    fun `a session hears about an episode once, and a new episode again`() {
        val memory = IdeMemory()
        val session = Any()
        memory.signal(t0, snapshot(900))
        val notice = memory.noticeFor(session, t0 + 1_000)!!
        assertTrue(notice, notice.startsWith("LOW MEMORY: the IDE's garbage collector was overloaded once in the last 15 min"))
        assertTrue(notice, notice.contains("900 MB of the 1000 MB heap stayed in use after a GC (90%)"))
        assertTrue(notice, notice.contains("{\"action\":\"get\",\"memory\":true}"))
        assertTrue(notice, notice.contains("raise the maximum heap"))
        assertNull(memory.noticeFor(session, t0 + 2_000))
        assertTrue(memory.noticeFor(Any(), t0 + 2_000) != null)

        memory.signal(t0 + IdeMemory.WINDOW_MS + 10_000, snapshot(950))
        assertTrue(memory.noticeFor(session, t0 + IdeMemory.WINDOW_MS + 11_000) != null)
    }

    @Test
    fun `pressure with room left after a GC is told as an allocation burst, not a small heap`() {
        val e = signals(0, 30_000, 60_000, 90_000, 120_000, afterGcMb = 100)!!
        val notice = IdeMemory.render(e, t0 + 121_000)
        assertTrue(notice, notice.contains("overloaded 5 times"))
        assertTrue(notice, notice.contains("a burst of allocation"))
        assertFalse(notice, notice.contains("raise the maximum heap"))
    }

    @Test
    fun `pressure that is not yet worth telling, or long over, gives no notice`() {
        val memory = IdeMemory()
        memory.signal(t0, snapshot(300))
        assertNull(memory.noticeFor(Any(), t0 + 1_000))
        memory.signal(t0 + 30_000, snapshot(900))
        assertNull(memory.noticeFor(Any(), t0 + 30_000 + IdeMemory.WINDOW_MS))
    }

    @Test
    fun `the report lists each part of the memory in the indicator's order`() {
        val text = IdeMemory.renderSnapshot(snapshot(300), recentSignals = 3)
        assertEquals(
            """
            heap: 700 MB used, 900 MB committed, 1000 MB max; 300 MB after the last GC (30% of max)
            direct buffers: 15 MB file cache, 20 MB total
            JVM: 300 MB non-heap pools, 120 threads (about 120 MB of stacks)
            memory-mapped files: 50 MB
            OS: 1500 MB RAM without file mappings, 200 MB file mappings in RAM, 1600 MB RAM and swap
            GC: 25% of the last 60 s; overloaded 3 times in the last 15 min
            """.trimIndent(),
            text,
        )
    }

    @Test
    fun `the report leaves out what this IDE build or OS does not report`() {
        val bare = snapshot(null).copy(directTotal = null, directFileCache = null, mapped = null, osRam = null, gcShare = null, gcWindowMs = null)
        assertEquals(
            """
            heap: 700 MB used, 900 MB committed, 1000 MB max
            JVM: 300 MB non-heap pools, 120 threads (about 120 MB of stacks)
            GC: not overloaded in the last 15 min
            """.trimIndent(),
            IdeMemory.renderSnapshot(bare, recentSignals = 0),
        )
    }
}
