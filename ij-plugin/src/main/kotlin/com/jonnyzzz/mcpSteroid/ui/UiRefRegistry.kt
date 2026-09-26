/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.ui

import com.intellij.openapi.components.Service
import java.awt.Component
import java.lang.ref.WeakReference
import java.util.WeakHashMap

sealed interface UiRefResolution {
    data class Live(val component: Component) : UiRefResolution
    data class Stale(val ref: String) : UiRefResolution
    data class Unknown(val ref: String) : UiRefResolution
}

/**
 * Short refs (`e12`) for components listed in snapshots. Components are held weakly, so a ref never keeps
 * a closed window alive. A ref whose component is gone or no longer showing is stale. The oldest refs are
 * forgotten past [capacity]. Thread-safe.
 */
class UiRefRegistry(
    private val capacity: Int = 5000,
    private val showing: (Component) -> Boolean = Component::isShowing,
) {
    private var next = 1
    private val byComponent = WeakHashMap<Component, String>()
    private val byRef = object : LinkedHashMap<String, WeakReference<Component>>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, WeakReference<Component>>?): Boolean =
            size > capacity
    }

    @Synchronized
    fun refFor(c: Component): String {
        byComponent[c]?.let { ref -> if (byRef.containsKey(ref)) return ref }
        val ref = "e${next++}"
        byComponent[c] = ref
        byRef[ref] = WeakReference(c)
        return ref
    }

    @Synchronized
    fun resolve(ref: String): UiRefResolution {
        val holder = byRef[ref] ?: return UiRefResolution.Unknown(ref)
        val c = holder.get() ?: return UiRefResolution.Stale(ref)
        return if (showing(c)) UiRefResolution.Live(c) else UiRefResolution.Stale(ref)
    }
}

/** The IDE-wide registry, so refs from one call work in the next one and in scripts. */
@Service(Service.Level.APP)
class UiRefs {
    val registry = UiRefRegistry()
}
