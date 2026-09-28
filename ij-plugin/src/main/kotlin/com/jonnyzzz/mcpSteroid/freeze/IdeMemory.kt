/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.freeze

import com.intellij.diagnostic.PlatformMemoryUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.util.LowMemoryWatcher
import com.intellij.platform.util.io.storages.mmapped.MMappedFileStorage
import com.intellij.util.io.DirectByteBufferAllocator
import com.jonnyzzz.mcpSteroid.server.split.currentSplitRole
import java.lang.management.BufferPoolMXBean
import java.lang.management.ManagementFactory
import java.lang.management.MemoryType
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** The IDE process's memory, as the status bar's memory indicator shows it, plus the heap left after the last GC. */
data class MemorySnapshot(
    val heapUsed: Long,
    val heapCommitted: Long,
    val heapMax: Long,
    /** What the heap pools held after their last collection, or null before the first one. */
    val heapAfterGc: Long?,
    val directTotal: Long?,
    val directFileCache: Long?,
    val nonHeap: Long,
    val threads: Int,
    val mapped: Long?,
    /** The OS figures, when this OS reports them: RAM without file mappings, file mappings in RAM, and RAM plus swap. */
    val osRam: Long?,
    val osFileMappings: Long?,
    val osRamAndSwap: Long?,
    /** The share of wall time the GC took over [gcWindowMs]. */
    val gcShare: Double?,
    val gcWindowMs: Long?,
) {
    /** The heap after the last GC as a share of the maximum heap, or null when either is unknown. */
    val afterGcShare: Double? get() = heapAfterGc?.takeIf { heapMax > 0 }?.let { it.toDouble() / heapMax }
}

/**
 * One stretch of memory pressure: overloaded-GC signals that follow each other within [IdeMemory.WINDOW_MS]. [told]
 * is set once the signals meet [IdeMemory.pressing], which is when agents hear about it.
 */
data class MemoryEpisode(val id: Int, val signals: List<Long>, val lastSnapshot: MemorySnapshot?, val told: Boolean = false)

/**
 * Tells agents when the IDE runs short of memory, so slow calls are read as memory pressure rather than as a freeze or
 * a bug, and answers a get of the numbers the status bar's memory indicator shows.
 *
 * The platform signals low memory once the GC is overloaded ([LowMemoryWatcher.LowMemoryWatcherType.ONLY_AFTER_GC]).
 * A short burst of such signals is normal during indexing or a large search, so, as the platform's own low-memory
 * warning does, the signals are throttled and counted over a window; a notice needs [SIGNALS] of them, or one with the
 * heap nearly full after its GC.
 */
@Service(Service.Level.APP)
class IdeMemory : Disposable {
    private val started = AtomicBoolean()
    private val told = WeakHashMap<Any, Int>()

    @Volatile
    internal var episode: MemoryEpisode? = null
        private set

    private var gcSample: GcSample? = null

    /** Starts listening; signals before this are not known. */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        LowMemoryWatcher.register({ signal(System.currentTimeMillis(), snapshot(sampleGc = false)) }, LowMemoryWatcher.LowMemoryWatcherType.ONLY_AFTER_GC, this)
    }

    override fun dispose() = Unit

    /** Records one overloaded-GC signal at [atMs], with the memory as it was then. */
    fun signal(atMs: Long, snapshot: MemorySnapshot?) = synchronized(this) {
        episode = next(episode, atMs, snapshot)
    }

    /** The memory pressure [session] has not been told about, as a notice, and marks it told. */
    fun noticeFor(session: Any, nowMs: Long = System.currentTimeMillis()): String? {
        val current = episode?.takeIf { it.told && nowMs - it.signals.last() < WINDOW_MS } ?: return null
        synchronized(told) {
            if (told[session] == current.id) return null
            told[session] = current.id
        }
        return render(current, nowMs, FreezeMonitor.sideOf(currentSplitRole()))
    }

    /** The memory now, with the GC's share of the time since the previous call, as a get of memory reports it. */
    fun report(nowMs: Long = System.currentTimeMillis()): String {
        val recent = episode?.signals.orEmpty().count { nowMs - it < WINDOW_MS }
        return renderSnapshot(snapshot(nowMs), recent)
    }

    /**
     * The memory now. With [sampleGc], the GC share covers the time since the previous such snapshot, or since the IDE
     * started; a signal's snapshot leaves it out, so a report after a burst of signals still covers a useful stretch.
     */
    fun snapshot(nowMs: Long = System.currentTimeMillis(), sampleGc: Boolean = true): MemorySnapshot {
        val heap = ManagementFactory.getMemoryMXBean().heapMemoryUsage
        val pools = ManagementFactory.getMemoryPoolMXBeans()
        val afterGc = pools.filter { it.type == MemoryType.HEAP }.mapNotNull { it.collectionUsage?.used }.takeIf { it.isNotEmpty() }?.sum()
        val nonHeap = pools.filter { it.type == MemoryType.NON_HEAP }.sumOf { it.usage?.used ?: 0L }
        val buffers = ManagementFactory.getPlatformMXBeans(BufferPoolMXBean::class.java)
        // Internal platform statistics, which a later IDE build may move: each one is left out when it fails.
        val os = runCatching { PlatformMemoryUtil.getInstance().getCurrentProcessMemoryStats() }.getOrNull()
            ?.takeIf { it.ramMinusFileMappings > 0 } // Older Windows versions report 0.
        val gc = if (sampleGc) gcShare(nowMs) else null
        return MemorySnapshot(
            heapUsed = heap.used,
            heapCommitted = heap.committed,
            heapMax = heap.max,
            heapAfterGc = afterGc,
            directTotal = buffers.firstOrNull { it.name == "direct" }?.memoryUsed,
            directFileCache = runCatching { DirectByteBufferAllocator.ALLOCATOR.statistics.totalSizeOfBuffersAllocatedInBytes }.getOrNull(),
            nonHeap = nonHeap,
            threads = ManagementFactory.getThreadMXBean().threadCount,
            mapped = runCatching { MMappedFileStorage.totalBytesMapped() }.getOrNull() ?: buffers.firstOrNull { it.name == "mapped" }?.memoryUsed,
            osRam = os?.ramMinusFileMappings,
            osFileMappings = os?.fileMappingsRam,
            osRamAndSwap = os?.ramPlusSwapMinusFileMappings,
            gcShare = gc?.first,
            gcWindowMs = gc?.second,
        )
    }

    private data class GcSample(val atMs: Long, val gcMs: Long)

    private fun gcShare(nowMs: Long): Pair<Double, Long>? = synchronized(this) {
        val gcMs = ManagementFactory.getGarbageCollectorMXBeans().sumOf { it.collectionTime.coerceAtLeast(0) }
        val previous = gcSample ?: GcSample(nowMs - ManagementFactory.getRuntimeMXBean().uptime, 0)
        gcSample = GcSample(nowMs, gcMs)
        val window = nowMs - previous.atMs
        if (window <= 0) null else ((gcMs - previous.gcMs).toDouble() / window).coerceIn(0.0, 1.0) to window
    }

    companion object {
        /** Signals closer together than this count as one, as the platform's low-memory warning throttles them. */
        const val THROTTLE_MS = 20_000L
        /** The window the signals are counted over; an episode ends after this long without one. */
        const val WINDOW_MS = 15 * 60_000L
        /** The throttled signals in [WINDOW_MS] that make a notice. */
        const val SIGNALS = 5
        /** The share of the maximum heap still in use after a GC that makes a notice on one signal. */
        const val FULL_AFTER_GC = 0.85
        /** Below this share of the maximum heap in use after a GC, the pressure is an allocation burst, not a small heap. */
        const val ROOM_AFTER_GC = 0.5
        private const val MB = 1024L * 1024

        fun getInstanceOrNull(): IdeMemory? = ApplicationManager.getApplication()?.let { service<IdeMemory>().also(IdeMemory::start) }

        /** Whether [episode]'s signals are memory pressure worth telling: [SIGNALS] in the window, or a nearly full heap. */
        fun pressing(signals: List<Long>, snapshot: MemorySnapshot?): Boolean =
            signals.size >= SIGNALS || (snapshot?.afterGcShare ?: 0.0) >= FULL_AFTER_GC

        /** The episode after a signal at [atMs]: throttled, counted over [WINDOW_MS], and a new one after a quiet window. */
        fun next(previous: MemoryEpisode?, atMs: Long, snapshot: MemorySnapshot?): MemoryEpisode {
            val ongoing = previous?.takeIf { atMs - it.signals.last() < WINDOW_MS }
            if (ongoing != null && atMs - ongoing.signals.last() < THROTTLE_MS) {
                val updated = ongoing.copy(lastSnapshot = snapshot ?: ongoing.lastSnapshot)
                return updated.copy(told = updated.told || pressing(updated.signals, updated.lastSnapshot))
            }
            val signals = (ongoing?.signals.orEmpty() + atMs).filter { atMs - it < WINDOW_MS }
            val episode = MemoryEpisode(ongoing?.id ?: ((previous?.id ?: 0) + 1), signals, snapshot ?: ongoing?.lastSnapshot, ongoing?.told ?: false)
            return episode.copy(told = episode.told || pressing(signals, episode.lastSnapshot))
        }

        private fun mb(bytes: Long) = "${bytes / MB} MB"
        private fun percent(share: Double) = "${Math.round(share * 100)}%"

        /** The notice for [episode]. [side] names the process in Split Mode, as [FreezeMonitor.sideOf] gives it. */
        fun render(episode: MemoryEpisode, nowMs: Long, side: String? = null): String = buildString {
            val count = episode.signals.count { nowMs - it < WINDOW_MS }
            append("LOW MEMORY${side?.let { " in $it" }.orEmpty()}: the IDE's garbage collector was overloaded ")
            append(if (count == 1) "once" else "$count times").append(" in the last ${WINDOW_MS / 60_000} min")
            episode.lastSnapshot?.let { s ->
                val after = s.heapAfterGc
                if (after != null && s.heapMax > 0) append("; ${mb(after)} of the ${mb(s.heapMax)} heap stayed in use after a GC (${percent(after.toDouble() / s.heapMax)})")
            }
            append(". Slow calls now are likely memory pressure, not a freeze or a bug in the call. ")
            append("{\"action\":\"get\",\"memory\":true} in steroid_ui gives the details. ")
            val share = episode.lastSnapshot?.afterGcShare
            // Room left after a GC means a burst of allocation, which a bigger heap would not fix.
            if (share != null && share < ROOM_AFTER_GC) append("The heap has room after a GC, so this is a burst of allocation, such as indexing or a large search, not a heap that is too small.\n")
            else append("The user can close projects they do not need, or raise the maximum heap with the IDE's Change Memory Settings action.\n")
        }

        /** The memory as a get reports it, one topic per line, in the order of the memory indicator's tooltip. */
        fun renderSnapshot(s: MemorySnapshot, recentSignals: Int): String = buildString {
            append("heap: ${mb(s.heapUsed)} used, ${mb(s.heapCommitted)} committed, ${mb(s.heapMax)} max")
            s.heapAfterGc?.let { after -> append("; ${mb(after)} after the last GC").append(s.afterGcShare?.let { " (${percent(it)} of max)" }.orEmpty()) }
            if (s.directTotal != null || s.directFileCache != null) {
                append("\ndirect buffers: ")
                append(listOfNotNull(s.directFileCache?.let { "${mb(it)} file cache" }, s.directTotal?.let { "${mb(it)} total" }).joinToString(", "))
            }
            append("\nJVM: ${mb(s.nonHeap)} non-heap pools, ${s.threads} threads (about ${s.threads} MB of stacks)")
            s.mapped?.let { append("\nmemory-mapped files: ${mb(it)}") }
            if (s.osRam != null) {
                append("\nOS: ${mb(s.osRam)} RAM without file mappings")
                s.osFileMappings?.let { append(", ${mb(it)} file mappings in RAM") }
                s.osRamAndSwap?.let { append(", ${mb(it)} RAM and swap") }
            }
            append("\nGC: ")
            if (s.gcShare != null && s.gcWindowMs != null) append("${percent(s.gcShare)} of the last ${s.gcWindowMs / 1000} s; ")
            append(if (recentSignals == 0) "not overloaded in the last ${WINDOW_MS / 60_000} min"
                else "overloaded ${if (recentSignals == 1) "once" else "$recentSignals times"} in the last ${WINDOW_MS / 60_000} min")
        }
    }
}
