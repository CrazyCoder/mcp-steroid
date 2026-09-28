/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.ui

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.awt.Dimension
import java.awt.Rectangle

/**
 * The layout problems of a host Settings page in Split Mode. The page's controls live on the backend, in a window the
 * backend places where the JetBrains Client shows the page, so their screen areas hold on the Client's screen too.
 * The backend reports each problem as a line a `get` of the layout returns; the Client reads them and turns their fixes
 * into steps it can run: a window step grows the Client's window, any other step runs on the backend.
 */
object UiHostLayout {
    /** A backend layout problem: its line, its fix as a step, the screen area of what it cuts, and its window's size. */
    data class HostProblem(val line: String, val fix: String?, val area: Rectangle?, val window: Dimension)

    /** How each problem starts in the report of a `get` of the layout. */
    const val PREFIX = "layout-problem "

    /** [problem] of a window of [window]'s size, as a line of the report. */
    fun encode(problem: UiLayout.Problem, window: Dimension): String = PREFIX + buildJsonObject {
        put("line", problem.line)
        put("fix", problem.fix?.let(::JsonPrimitive) ?: JsonNull)
        put("area", problem.area?.let { JsonArray(listOf(it.x, it.y, it.width, it.height).map(::JsonPrimitive)) } ?: JsonNull)
        put("window", JsonArray(listOf(window.width, window.height).map(::JsonPrimitive)))
    }

    /** The problems in the report of a `get` of the layout; its other lines are left out. */
    fun decode(report: String): List<HostProblem> = report.lineSequence().filter { it.startsWith(PREFIX) }.map { text ->
        val o = Json.parseToJsonElement(text.removePrefix(PREFIX)).jsonObject
        val area = (o["area"] as? JsonArray)?.map { it.jsonPrimitive.int }
        val window = o.getValue("window").jsonArray.map { it.jsonPrimitive.int }
        HostProblem(
            line = o.getValue("line").jsonPrimitive.content,
            fix = (o["fix"] as? JsonPrimitive)?.takeIf { it.isString }?.content,
            area = area?.let { (x, y, w, h) -> Rectangle(x, y, w, h) },
            window = Dimension(window[0], window[1]),
        )
    }.toList()

    /**
     * [fix], a step the backend names, as the JetBrains Client runs it. A window step with a size grows the Client's
     * window, of [client]'s size, by as much as it grows the backend's, of [backend]'s size: the page grows with the
     * Client's window. A window step without a size is the Client's too. Any other step runs on the backend.
     */
    fun clientFix(fix: String?, backend: Dimension, client: Dimension): String? {
        val step = fix?.let { Json.parseToJsonElement(it).jsonObject } ?: return null
        if ((step["action"] as? JsonPrimitive)?.content != "window") return JsonObject(step + ("side" to JsonPrimitive("backend"))).toString()
        return JsonObject(step.mapValues { (key, value) ->
            val size = (value as? JsonPrimitive)?.intOrNull
            when {
                size == null -> value
                key == "width" -> JsonPrimitive(client.width + size - backend.width)
                key == "height" -> JsonPrimitive(client.height + size - backend.height)
                else -> value
            }
        }).toString()
    }
}
