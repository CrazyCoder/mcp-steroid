/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.mcp

import com.jonnyzzz.mcpSteroid.thisLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.jetbrains.annotations.TestOnly
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds


/**
 * Represents an MCP session with its state and notification channel.
 * Supports bidirectional requests for features like sampling.
 */
class McpSession(
    val id: String = UUID.randomUUID().toString()
) {
    private val log = thisLogger()

    @Volatile
    var initialized: Boolean = false
        private set

    @Volatile
    var clientInfo: ClientInfo? = null
        private set

    @Volatile
    var clientCapabilities: ClientCapabilities? = null
        private set

    /**
     * The protocol revision negotiated during `initialize` — the client's requested
     * version when we support it, otherwise our latest. Defaults to [MCP_PROTOCOL_VERSION]
     * until negotiation completes. The HTTP transport reports this in the
     * `MCP-Protocol-Version` response header so it matches the initialize result.
     */
    @Volatile
    var negotiatedProtocolVersion: String = MCP_PROTOCOL_VERSION
        private set

    /**
     * Guards the once-per-session `mcp_session_initialized` beacon so a duplicate
     * `initialize` request does not double-fire the analytics event.
     */
    @Volatile
    var initBeaconFired: Boolean = false

    private val notificationChannel = Channel<JsonRpcNotification>(Channel.BUFFERED)

    // For server-to-client requests (like sampling)
    private val requestIdCounter = AtomicLong(0)
    private val pendingRequests = ConcurrentHashMap<String, CompletableDeferred<JsonElement>>()
    private val outgoingRequestsChannel = Channel<JsonRpcRequest>(Channel.BUFFERED)

    /**
     * Mark session as initialized after successful initialize/initialized exchange.
     */
    fun markInitialized(info: ClientInfo, capabilities: ClientCapabilities) {
        clientInfo = info
        clientCapabilities = capabilities
        initialized = true
    }

    /**
     * Record the protocol version negotiated during `initialize`.
     */
    fun markProtocolVersion(version: String) {
        negotiatedProtocolVersion = version
    }

    /**
     * Check if client supports sampling capability.
     */
    fun supportsSampling(): Boolean {
        return clientCapabilities?.sampling != null
    }

    /**
     * Check if client supports roots capability.
     */
    fun supportsRoots(): Boolean {
        return clientCapabilities?.roots != null
    }

    /**
     * Check if client supports roots list changed notifications.
     */
    fun supportsRootsListChanged(): Boolean {
        return clientCapabilities?.roots?.listChanged == true
    }

    /**
     * Send a notification to this session's SSE stream.
     *
     * Uses a non-suspending [Channel.trySend] so callers (often `progress.report`,
     * inside hot tool-execution paths) never block. If the buffered channel is full
     * or already closed, the notification is dropped and a warning is logged so the
     * loss is observable in production rather than swallowed silently.
     */
    fun sendNotification(notification: JsonRpcNotification) {
        val result = notificationChannel.trySend(notification)
        if (result.isFailure) {
            val reason = if (result.isClosed) "channel closed" else "buffer full"
            log.warn("[MCP Session $id] dropped notification ${notification.method}: $reason")
        }
    }

    /**
     * Get flow of notifications for SSE streaming.
     */
    fun notifications(): Flow<JsonRpcNotification> = notificationChannel.consumeAsFlow()

    /**
     * Get flow of outgoing requests (server-to-client).
     * Used for SSE transport or batched responses.
     */
    fun outgoingRequests(): Flow<JsonRpcRequest> = outgoingRequestsChannel.consumeAsFlow()

    /**
     * Send a request to the client and await response.
     * This is used for sampling/createMessage and other bidirectional operations.
     *
     * @param method The JSON-RPC method to call
     * @param params The parameters for the method
     * @param timeout Maximum time to wait for response
     * @return The result from the client, or null if timeout/error
     */
    suspend fun sendRequest(
        method: String,
        params: JsonObject,
        timeout: Duration = 60.seconds
    ): JsonElement? {
        val requestId = "server-${requestIdCounter.incrementAndGet()}"
        val deferred = CompletableDeferred<JsonElement>()

        pendingRequests[requestId] = deferred

        val request = JsonRpcRequest(
            id = McpJson.parseToJsonElement("\"$requestId\""),
            method = method,
            params = params
        )

        log.info("[MCP Session ${id}] Sending server-to-client request: $method (id: $requestId)")
        val sent = outgoingRequestsChannel.trySend(request)
        if (sent.isFailure) {
            // Don't leave the deferred parked waiting for a response that will
            // never come — the request never reached the wire.
            pendingRequests.remove(requestId)
            val reason = if (sent.isClosed) "channel closed" else "buffer full"
            log.warn("[MCP Session $id] dropped outgoing request $method: $reason")
            return null
        }

        return try {
            withTimeoutOrNull(timeout) {
                deferred.await()
            }
        } finally {
            pendingRequests.remove(requestId)
        }
    }

    /**
     * Handle a response to a server-initiated request.
     * Called when client sends a response matching one of our pending requests.
     *
     * @param id The request ID from the response
     * @param result The result from the client
     * @return true if this was a pending server-to-client request
     */
    fun handleResponse(id: String, result: JsonElement): Boolean {
        val deferred = pendingRequests[id]
        if (deferred != null) {
            log.info("[MCP Session ${this.id}] Received response for server request: $id")
            deferred.complete(result)
            return true
        }
        return false
    }

    /**
     * Handle an error response to a server-initiated request.
     */
    fun handleErrorResponse(id: String, error: JsonRpcError): Boolean {
        val deferred = pendingRequests[id]
        if (deferred != null) {
            log.warn("[MCP Session ${this.id}] Received error for server request: $id - ${error.message}")
            deferred.completeExceptionally(McpRequestException(error))
            return true
        }
        return false
    }

    /**
     * Check if there are pending outgoing requests.
     */
    fun hasPendingRequests(): Boolean = pendingRequests.isNotEmpty()

    /**
     * Get pending request IDs (for debugging/testing).
     */
    fun getPendingRequestIds(): Set<String> = pendingRequests.keys.toSet()

    /**
     * Drain all buffered notifications without closing the channel.
     * Returns notifications that were buffered at the time of the call.
     */
    fun drainNotifications(): List<JsonRpcNotification> {
        val result = mutableListOf<JsonRpcNotification>()
        while (true) {
            val received = notificationChannel.tryReceive()
            if (received.isSuccess) {
                result.add(received.getOrThrow())
            } else {
                break
            }
        }
        return result
    }

    /** The client's requests this session is running, by request id, for `notifications/cancelled`. */
    private val runningRequests = ConcurrentHashMap<String, Job>()

    /**
     * Runs [block] as the client's request [requestId]. Returns `null` when the client cancels that request with
     * `notifications/cancelled` ([cancelRequest]); a cancellation of the caller itself propagates.
     */
    suspend fun <T : Any> runCancellable(requestId: String, block: suspend () -> T): T? = coroutineScope {
        val call = async { block() }
        runningRequests[requestId] = call
        try {
            call.await()
        } catch (e: CancellationException) {
            ensureActive()
            null
        } finally {
            runningRequests.remove(requestId, call)
        }
    }

    /** Cancels the running request [requestId], as the client asks; false when no such request runs. */
    fun cancelRequest(requestId: String, reason: String?): Boolean {
        val call = runningRequests[requestId] ?: return false
        log.info("Client cancelled request $requestId" + (reason?.let { ": $it" } ?: ""))
        call.cancel(CancellationException("the client cancelled the request" + (reason?.let { ": $it" } ?: "")))
        return true
    }

    /**
     * Close the session.
     */
    fun close() {
        notificationChannel.close()
        outgoingRequestsChannel.close()
        // Cancel any pending requests
        pendingRequests.values.forEach { it.cancel() }
        pendingRequests.clear()
        runningRequests.values.forEach { it.cancel(CancellationException("the session closed")) }
    }
}

/**
 * Exception thrown when a server-to-client request fails.
 */
class McpRequestException(val error: JsonRpcError) : Exception("MCP request failed: ${error.message}")

/**
 * Manages active MCP sessions.
 */
class McpSessionManager {
    private val log = thisLogger()
    private val sessions = ConcurrentHashMap<String, McpSession>()

    /** Unknown ids a client sent, such as ones from before an IDE restart, to the session created for each. */
    private val aliases = ConcurrentHashMap<String, String>()

    init {
        log.info("[MCP SessionManager] Initialized (new instance - all previous sessions are invalidated)")
    }

    /**
     * Create a new session. [unknownId] is the id the client sent that no session has; the client's later
     * requests with it reach this session, since a client may keep sending that id.
     */
    fun createSession(unknownId: String? = null): McpSession {
        val session = McpSession()
        sessions[session.id] = session
        if (unknownId != null) aliases[unknownId] = session.id
        log.info("[MCP SessionManager] Created session: ${session.id} (total active: ${sessions.size})")
        return session
    }

    /**
     * Get an existing session by its ID, or by the unknown ID it was created for.
     */
    fun getSession(id: String): McpSession? {
        val session = sessions[id] ?: aliases[id]?.let { sessions[it] }
        if (session == null) {
            log.debug("[MCP SessionManager] Session not found: $id (active sessions: ${sessions.keys.joinToString(", ").ifEmpty { "none" }})")
        }
        return session
    }

    /**
     * Remove and close a session.
     */
    fun removeSession(id: String) {
        val removed = sessions.remove(id) ?: aliases[id]?.let { sessions.remove(it) }
        if (removed != null) {
            aliases.values.removeIf { it == removed.id }
            removed.close()
            log.info("[MCP SessionManager] Removed session: $id (remaining: ${sessions.size})")
        } else {
            log.warn("[MCP SessionManager] Attempted to remove non-existent session: $id")
        }
    }

    /**
     * Get all active sessions.
     */
    fun getAllSessions(): Collection<McpSession> = sessions.values

    /**
     * Get count of active sessions.
     */
    fun getSessionCount(): Int = sessions.size

    @TestOnly
    fun forgetAllSessionsForTest(): Int {
        val count = sessions.size
        sessions.values.forEach { it.close() }
        sessions.clear()
        aliases.clear()
        log.info("[MCP SessionManager] Closed and forgot all sessions (previous count: $count)")
        return count
    }
}
