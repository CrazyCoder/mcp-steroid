/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.util.SystemInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.seconds

/** Polls the port pin file and moves the server when this IDE's pin changes. */
@Service(Service.Level.APP)
class PortPinWatcher(private val scope: CoroutineScope) {
    private val started = AtomicBoolean(false)

    fun start() {
        if (!started.compareAndSet(false, true)) return
        val file = PortPins.pinsFile(Path.of(System.getProperty("user.home")))
        scope.launch(Dispatchers.IO) {
            var lastModified = modified(file)
            while (isActive) {
                delay(2.seconds)
                val now = modified(file)
                if (now == lastModified) continue
                lastModified = now
                // One failed move must not end the watch for the rest of the session.
                try {
                    val server = SteroidsMcpServer.getInstance()
                    val pinned = PortPins.pinnedPort(file, PathManager.getPluginsPath(), SystemInfo.isWindows)
                    val target = PortPins.rebindTarget(server.port, pinned) ?: continue
                    if (server.rebind(target) > 0) {
                        ServerUrlWriter.getInstance().writeServerUrlToUserHome(server.mcpUrl)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    thisLogger().warn("Could not apply the port pin from $file", e)
                }
            }
        }
    }

    private fun modified(file: Path): Long =
        try { Files.getLastModifiedTime(file).toMillis() } catch (_: Exception) { -1L }

    companion object {
        fun getInstance(): PortPinWatcher = ApplicationManager.getApplication().service()
    }
}
