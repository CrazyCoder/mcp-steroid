/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig.server

import com.jonnyzzz.mcpSteroid.IdeInfo
import com.jonnyzzz.mcpSteroid.PluginInfo
import com.jonnyzzz.mcpSteroid.devrig.DevrigBeacon
import com.jonnyzzz.mcpSteroid.devrig.HomePaths
import com.jonnyzzz.mcpSteroid.devrig.monitor.DiscoveredIde
import com.jonnyzzz.mcpSteroid.devrig.monitor.IdeMonitorState
import com.jonnyzzz.mcpSteroid.devrig.monitor.IdeProjectState
import com.jonnyzzz.mcpSteroid.devrig.startTestHttpServer
import com.jonnyzzz.mcpSteroid.devrig.testDevrigEndpoint
import com.jonnyzzz.mcpSteroid.mcp.ContentItem
import com.jonnyzzz.mcpSteroid.mcp.McpJson
import com.jonnyzzz.mcpSteroid.mcp.ToolCallResult
import com.jonnyzzz.mcpSteroid.server.ExecuteCodeToolHandler
import com.jonnyzzz.mcpSteroid.server.ExecuteFeedbackToolHandler
import com.jonnyzzz.mcpSteroid.server.McpSteroidTools
import com.jonnyzzz.mcpSteroid.server.NoOpProgressReporter
import com.jonnyzzz.mcpSteroid.server.RefactorToolHandler
import com.jonnyzzz.mcpSteroid.server.UiToolHandler
import com.jonnyzzz.mcpSteroid.server.VisionInputToolHandler
import com.jonnyzzz.mcpSteroid.server.VisionScreenshotToolHandler
import com.jonnyzzz.mcpSteroid.server.backendNameForMarker
import com.jonnyzzz.mcpSteroid.testHelper.CloseableStackHost
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.http.ContentType
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir

/**
 * Every parameter a devrig tool advertises reaches the IDE. devrig serves the same schemas as the plugin, so a
 * parameter its handler leaves out is accepted from the agent and then silently dropped.
 */
class DevrigToolForwardingTest {
    private lateinit var server: EmbeddedServer<*, *>
    private lateinit var httpClient: HttpClient
    private var port = 0
    private val bodies = mutableListOf<JsonObject>()

    @BeforeEach
    fun setUp() {
        val started = startTestHttpServer {
            routing {
                post("/api/jonnyzzz/mcp-steroid/v1/tools/call/stream") {
                    bodies += McpJson.parseToJsonElement(call.receiveText()).jsonObject
                    val result = McpJson.encodeToJsonElement(
                        ToolCallResult.serializer(),
                        ToolCallResult(content = listOf(ContentItem.Text("ok")), isError = false),
                    )
                    val line = buildJsonObject { put("type", "result"); put("result", result) }
                    call.respondText(line.toString() + "\n", ContentType.parse("application/x-ndjson"))
                }
            }
        }
        server = started.server
        port = started.port
        httpClient = HttpClient(CIO) { expectSuccess = false }
    }

    @AfterEach
    fun tearDown() {
        if (::httpClient.isInitialized) httpClient.close()
        if (::server.isInitialized) server.stop(0L, 0L)
    }

    @Test
    fun `every advertised parameter of a forwarded tool reaches the IDE`(@TempDir tempDir: Path) = runBlocking {
        val routing = DevrigProjectRoutingService {
            listOf(
                IdeMonitorState(
                    ide = DiscoveredIde(
                        backendName = backendNameForMarker(7L, "IU-261.1"),
                        processId = 7,
                        rpcBaseUrl = testDevrigEndpoint("http://127.0.0.1:$port/mcp").rpcBaseUrl,
                        bridgeHeaders = emptyMap(),
                        ide = IdeInfo("IntelliJ IDEA", "2026.1", "IU-261.1"),
                        plugin = PluginInfo("io.github.crazycoder.mcp-steroid", "MCP Steroid", "0.0.0-test"),
                    ),
                    projects = listOf(IdeProjectState("original-project", Files.createDirectories(tempDir.resolve("p")).toString())),
                )
            )
        }
        val exposed = routing.routes().single().exposedProjectName
        val tools = devrigTools(routing, DevrigToolBridgeClient(httpClient), tempDir)

        // Every tool devrig serves goes to the IDE except the ones below, so a new tool is covered.
        val forwarded = tools.devrigToolSpecs().filter { it.name !in LOCAL_TOOLS }
        assertTrue(forwarded.size >= 6, "forwarded tools: ${forwarded.map { it.name }}")
        // Two passes with different values, so a handler that sends a fixed value fails one of them.
        for (pass in 0..1) for (spec in forwarded) {
            val properties = spec.inputSchema["properties"]!!.jsonObject
            val arguments = buildJsonObject {
                for ((name, schema) in properties) {
                    put(name, if (name == "project_name") JsonPrimitive(exposed) else sample(name, schema.jsonObject, pass))
                }
            }
            bodies.clear()
            val result = callToolViaSpec(spec, arguments, NoOpProgressReporter)
            assertEquals(false, result.isError, "${spec.name}: $result")
            val sent = bodies.single()["arguments"]!!.jsonObject
            // Leaving out a false flag sends its default, false.
            val missing = properties.keys.filterNot { it in sent || arguments[it] == JsonPrimitive(false) }
            assertTrue(missing.isEmpty(), "${spec.name} drops $missing; sent ${sent.keys}")
            for (name in properties.keys - "project_name") {
                if (name in sent) assertEquals(arguments[name].content(), sent[name].content(), "${spec.name}.$name, pass $pass")
            }
        }
    }

    @Test
    fun `a client without a session sends none`(@TempDir tempDir: Path) = runBlocking {
        DevrigToolBridgeClient(httpClient, session = null).callProjectTool(route(tempDir), "steroid_list_windows") {}
        assertTrue("session" !in bodies.single(), "no session key: ${bodies.single()}")
    }

    @Test
    fun `the calls of one devrig name one session`(@TempDir tempDir: Path) = runBlocking {
        val route = route(tempDir)
        val bridge = DevrigToolBridgeClient(httpClient)
        repeat(2) { bridge.callProjectTool(route, "steroid_list_windows") {} }
        DevrigToolBridgeClient(httpClient).callProjectTool(route, "steroid_list_windows") {}
        val sessions = bodies.map { it["session"]?.jsonPrimitive?.content }
        assertTrue(sessions.all { !it.isNullOrBlank() }, "every call names its session: $sessions")
        assertEquals(sessions[0], sessions[1], "one devrig, one session")
        assertTrue(sessions[0] != sessions[2], "another devrig, another session")
    }

    private fun route(tempDir: Path) = ProjectRoute(
        route = DiscoveredIde(
            backendName = backendNameForMarker(7L, "IU-261.1"),
            processId = 7,
            rpcBaseUrl = testDevrigEndpoint("http://127.0.0.1:$port/mcp").rpcBaseUrl,
            bridgeHeaders = emptyMap(),
            ide = IdeInfo("IntelliJ IDEA", "2026.1", "IU-261.1"),
            plugin = PluginInfo("io.github.crazycoder.mcp-steroid", "MCP Steroid", "0.0.0-test"),
        ),
        projectInfo = IdeProjectState("original-project", tempDir.toString()),
        exposedProjectName = "original-project-abcdefgh",
        projectPath = tempDir.toString(),
    )

    private fun devrigTools(routing: DevrigProjectRoutingService, bridge: DevrigToolBridgeClient, tempDir: Path) =
        object : McpSteroidTools() {
            private val beacon = DevrigBeacon(HomePaths(tempDir.resolve("beacon-home")), CloseableStackHost())

            override fun <T> handler(type: Class<T>): T = type.cast(
                when (type) {
                    ExecuteCodeToolHandler::class.java -> DevrigExecuteCodeToolHandler(bridge, routing, beacon)
                    ExecuteFeedbackToolHandler::class.java -> DevrigExecuteFeedbackToolHandler(bridge, routing, beacon)
                    VisionScreenshotToolHandler::class.java -> DevrigVisionScreenshotToolHandler(bridge, routing)
                    VisionInputToolHandler::class.java -> DevrigVisionInputToolHandler(bridge, routing)
                    UiToolHandler::class.java -> DevrigUiToolHandler(bridge, routing)
                    RefactorToolHandler::class.java -> DevrigRefactorToolHandler(bridge, routing)
                    else -> error("not forwarded in this test: ${type.name}")
                }
            )
        }

    /**
     * A valid value for each parameter, different in each [pass]: an enum's first or last value, a type's
     * sample, or a value its parser accepts.
     */
    private fun sample(name: String, schema: JsonObject, pass: Int): JsonElement = SAMPLES[name]
        ?: schema["enum"]?.jsonArray?.let { if (pass == 0) it.first() else it.last() }
        ?: when (schema["type"]?.jsonPrimitive?.content) {
            "integer" -> JsonPrimitive(3 + pass)
            "number" -> JsonPrimitive(0.5 + pass / 4.0)
            "boolean" -> JsonPrimitive(pass == 0)
            else -> JsonPrimitive("sample-$name-$pass")
        }

    private fun JsonElement?.content(): String? = (this as? JsonPrimitive)?.content ?: this?.toString()

    private companion object {
        /**
         * The tools devrig answers itself, from its own routing and bundled guides, and steroid_open_project, which
         * picks or starts a backend before it forwards and has tests of its own in DevrigToolBridgeClientTest.
         */
        val LOCAL_TOOLS = setOf(
            "steroid_list_projects",
            "steroid_list_windows",
            "steroid_fetch_resource",
            "steroid_open_project",
        )
        val SAMPLES = mapOf(
            "sequence" to JsonPrimitive("press:ENTER"),
            "steps" to JsonPrimitive("[]"),
        )
    }
}
