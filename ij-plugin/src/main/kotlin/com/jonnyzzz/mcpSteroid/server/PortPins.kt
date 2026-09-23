/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import com.intellij.openapi.diagnostic.thisLogger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The port pin file `~/.mcp-steroid/ports.json`, written by external tooling:
 * `{"version":1,"ports":{"<plugins dir>":<port>}}`. Keys are compared after [normalize],
 * so the writer and the IDE may spell the same directory differently. A pin
 * wins over `mcp.steroid.server.port`: the tooling that writes it pins each IDE
 * to the port its MCP client configuration expects.
 */
object PortPins {
    private val warnedMalformed = AtomicBoolean(false)

    fun pinsFile(userHome: Path): Path = userHome.resolve(".mcp-steroid").resolve("ports.json")

    fun normalize(path: String, windows: Boolean): String {
        val slashed = path.replace('\\', '/').trimEnd('/')
        return if (windows) slashed.lowercase() else slashed
    }

    fun parse(text: String, windows: Boolean): Map<String, Int>? = try {
        val ports = Json.parseToJsonElement(text).jsonObject["ports"]?.jsonObject ?: return emptyMap()
        ports.entries.mapNotNull { (key, value) ->
            val port = (value as? JsonPrimitive)?.intOrNull
            if (port != null && port in 1..65535) normalize(key, windows) to port else null
        }.toMap()
    } catch (_: Exception) {
        null
    }

    fun pinnedPort(file: Path, pluginsPath: String, windows: Boolean): Int? {
        if (!Files.isRegularFile(file)) return null
        val text = try { Files.readString(file) } catch (_: Exception) { return null }
        val pins = parse(text, windows)
        if (pins == null) {
            if (warnedMalformed.compareAndSet(false, true)) thisLogger().warn("Ignoring malformed port pin file $file")
            return null
        }
        return pins[normalize(pluginsPath, windows)]
    }

    fun resolve(registryPort: Int, pinned: Int?): Int = pinned ?: registryPort

    fun rebindTarget(currentPort: Int, pinned: Int?): Int? =
        if (pinned == null || pinned == currentPort) null else pinned
}
