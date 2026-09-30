/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.jonnyzzz.mcpSteroid.mcp.McpJson
import com.jonnyzzz.mcpSteroid.mcp.McpServerCore
import com.jonnyzzz.mcpSteroid.mcp.ToolCallParams
import com.jonnyzzz.mcpSteroid.mcp.ToolCallResult
import com.jonnyzzz.mcpSteroid.server.split.bridgeSession
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

@Service(Service.Level.APP)
class NpxBridgeService {
    private val log = thisLogger()
    private val seqCounter = AtomicLong(0)

    val instanceId: String = "npx-${UUID.randomUUID()}"
    val token: String = UUID.randomUUID().toString().replace("-", "")
    val schemaVersion: String = "1"

    fun isAuthorized(authorizationHeader: String?): Boolean {
        if (authorizationHeader.isNullOrBlank()) return false
        if (!authorizationHeader.startsWith("Bearer ")) return false
        return authorizationHeader.removePrefix("Bearer ").trim() == token
    }

    private fun nextSeq(): Long = seqCounter.incrementAndGet()

    private fun nowIso(): String = java.time.Instant.now().toString()

    /**
     * The `/windows` WIRE response (devrig<->IDE) — built from the raw [IdeWindowsCollector] snapshot
     * (pristine [WindowInfo]/[ProgressTaskInfo] + this IDE's own pid), NOT from the MCP
     * [ListWindowsResponse] (which is backend-attributed and never crosses the wire).
     */
    suspend fun buildWindows(mcpUrl: String): NpxBridgeWindowsResponse {
        val seq = nextSeq()
        val snapshot = service<IdeWindowsCollector>().collect()
        return NpxBridgeWindowsResponse(
            windows = snapshot.windows,
            backgroundTasks = snapshot.backgroundTasks,
            pid = ProcessHandle.current().pid(),
            mcpUrl = mcpUrl,
            instanceId = instanceId,
            seq = seq,
            schemaVersion = schemaVersion,
            updatedAt = nowIso()
        )
    }

    suspend fun streamToolCall(
        serverCore: McpServerCore,
        request: NpxBridgeToolCallRequest,
        emit: suspend (JsonObject) -> Unit
    ) {
        // The calls of one devrig session share an IDE session, so notices are told to it once; a devrig that sends no
        // session key gets a session per call, removed when the call ends.
        val devrigSession = request.session?.takeIf { it.isNotBlank() }
        val session = devrigSession?.let { bridgeSession(serverCore, "devrig:$it") } ?: serverCore.sessionManager.createSession()
        val progressToken = "npx-${UUID.randomUUID()}"

        val params = buildToolCallParams(request, progressToken)

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val emitMutex = Mutex()
        suspend fun emitEvent(event: JsonObject) {
            emitMutex.withLock {
                emit(event)
            }
        }
        // Progress goes through this call's own channel, not the session's notifications: a shared session carries
        // the progress of every call of its devrig session.
        val progressMessages = Channel<String>(Channel.UNLIMITED)
        val progressCounter = AtomicLong(0)
        val progress = object : McpProgressReporter {
            override fun report(message: String) {
                progressMessages.trySend(message)
            }
        }
        var sessionRemoved = devrigSession != null
        fun removeSession() {
            if (!sessionRemoved) {
                serverCore.sessionManager.removeSession(session.id)
                sessionRemoved = true
            }
        }
        lateinit var progressJob: Job
        suspend fun closeSessionAndDrainProgress() {
            progressMessages.close()
            progressJob.join()
            removeSession()
        }
        val heartbeatJob = scope.launch {
            while (isActive) {
                delay(NPX_STREAM_KEEPALIVE_INTERVAL_SECONDS.seconds)
                emitEvent(
                    buildJsonObject {
                        put("type", "heartbeat")
                        put("instanceId", instanceId)
                        put("seq", seqCounter.incrementAndGet())
                        put("updatedAt", nowIso())
                    }
                )
            }
        }
        progressJob = scope.launch {
            for (message in progressMessages) {
                emitEvent(
                    buildJsonObject {
                        put("type", "progress")
                        put("instanceId", instanceId)
                        put("seq", seqCounter.incrementAndGet())
                        put("progress", progressCounter.incrementAndGet().toDouble())
                        put("message", message)
                        put("updatedAt", nowIso())
                    }
                )
            }
        }

        try {
            emitEvent(
                buildJsonObject {
                    put("type", "progress")
                    put("instanceId", instanceId)
                    put("seq", seqCounter.incrementAndGet())
                    put("message", "Tool call started: ${request.name}")
                    put("progress", 0.0)
                    put("updatedAt", nowIso())
                }
            )

            val result = serverCore.toolRegistry.callTool(params, session, progress)
            heartbeatJob.cancel()
            closeSessionAndDrainProgress()
            emitEvent(
                buildJsonObject {
                    put("type", "result")
                    put("instanceId", instanceId)
                    put("seq", seqCounter.incrementAndGet())
                    put("updatedAt", nowIso())
                    put("result", McpJson.encodeToJsonElement(ToolCallResult.serializer(), result))
                }
            )
        } catch (e: Exception) {
            log.warn("Failed to stream bridge tool call '${request.name}'", e)
            heartbeatJob.cancel()
            closeSessionAndDrainProgress()
            emitEvent(
                buildJsonObject {
                    put("type", "error")
                    put("instanceId", instanceId)
                    put("seq", seqCounter.incrementAndGet())
                    put("updatedAt", nowIso())
                    put("message", e.message ?: "Tool call failed")
                }
            )
        } finally {
            heartbeatJob.cancel()
            progressJob.cancel()
            scope.cancel()
            removeSession()
        }
    }

    /**
     * Builds a bridge-owned tool call. [ToolCallParams.trustedArguments] is transient, so neither an
     * old devrig request nor spoofed JSON arguments can control execution-storage provenance.
     */
    fun buildToolCallParams(request: NpxBridgeToolCallRequest, progressToken: String): ToolCallParams {
        val argsWithMeta = injectProgressMeta(request.arguments, progressToken)
        val rawParams = buildJsonObject {
            put("name", request.name)
            put("arguments", argsWithMeta)
        }
        val backendName = backendNameForMarker(
            ProcessHandle.current().pid(),
            ApplicationInfo.getInstance().build.asString(),
        )
        return ToolCallParams(
            name = request.name,
            arguments = argsWithMeta,
            rawArguments = rawParams,
            trustedArguments = buildJsonObject {
                put(EXECUTION_BACKEND_KIND_ARGUMENT, "d")
                put(EXECUTION_BACKEND_NAME_ARGUMENT, backendName)
            },
        )
    }

    private fun injectProgressMeta(arguments: JsonObject?, progressToken: String): JsonObject {
        val args = arguments ?: buildJsonObject { }
        val existingMeta = args["_meta"]?.jsonObject
        return buildJsonObject {
            for ((key, value) in args.entries) {
                if (key == "_meta") continue
                put(key, value)
            }
            putJsonObject("_meta") {
                if (existingMeta != null) {
                    for ((key, value) in existingMeta.entries) {
                        put(key, value)
                    }
                }
                put("progressToken", progressToken)
            }
        }
    }

    companion object {
        fun getInstance(): NpxBridgeService = service()
    }
}
