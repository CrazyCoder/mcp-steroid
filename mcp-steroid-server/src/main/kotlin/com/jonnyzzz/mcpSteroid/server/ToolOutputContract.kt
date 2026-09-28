/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import com.jonnyzzz.mcpSteroid.mcp.ContentItem
import com.jonnyzzz.mcpSteroid.mcp.ToolCallResult
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/**
 * The machine output contract of steroid_execute_code and steroid_ui, for scripts and other programs that read a tool's
 * result. A call that passes `"output":"json"` gets exactly one text content item: a JSON object, the envelope, and
 * nothing around it. The text a model reads carries notices, hints and framing that grow with every release; the
 * envelope keeps each of them in a field of its own.
 *
 * The envelope only grows. A released field keeps its name, type and meaning in every later version; new data comes as
 * new fields, or as new values of `notices[].kind`. A consumer ignores fields it does not know. [VERSION] changes only
 * if a released field ever has to change, which the contract is written to avoid. The frozen fixture
 * `output-contract-1.json` under the server's test resources fails the build when a released field changes.
 * `mcp-steroid://skill/output-contract` documents it for callers.
 *
 * Fields every envelope has: `contract` ([VERSION]), `tool`, `ok` (false when the call failed, as `isError` says), and
 * `notices`. Each tool adds its own; [wrap] gives any other result `text`.
 */
object ToolOutputContract {
    const val VERSION = 1
    const val PARAM = "output"
    const val JSON_MODE = "json"

    private val json = Json { prettyPrint = false }

    /** Whether the call's raw arguments ask for the envelope. */
    fun wantsJson(arguments: JsonObject?): Boolean = (arguments?.get(PARAM) as? JsonPrimitive)?.contentOrNull == JSON_MODE

    /** A notice as the envelope lists it: its kind, the Split Mode side it came from, and its text as a model reads it. */
    data class Notice(val kind: String, val side: String?, val text: String)

    // "IDE FREEZE in the backend (ended): ...", "LOW MEMORY: ...", "EDITOR BANNERS in the JetBrains Client: ..."
    private val NOTICE_HEAD = Regex("""^([A-Z][A-Z ]*[A-Z])(?: in (?:the )?(backend|JetBrains Client))?(?: \([a-z ]+\))?:""")

    /**
     * A notice's kind and side, read from its first line: `IDE_FREEZE`, `IDE_ERRORS`, `EDITOR_BANNERS`, `LOW_MEMORY`,
     * `EDITOR_STATE`, or another name in the same form. The side is `backend` or `frontend`, or null in a regular IDE.
     */
    fun noticeOf(text: String): Notice {
        val head = NOTICE_HEAD.find(text)
        val kind = head?.groupValues?.get(1)?.replace(' ', '_') ?: "NOTICE"
        val side = when (head?.groupValues?.get(2)) {
            "backend" -> "backend"
            "JetBrains Client" -> "frontend"
            else -> null
        }
        return Notice(kind, side, text.trimEnd())
    }

    /** The start of an envelope for [tool], which the tool fills with its own fields. */
    fun envelope(tool: String, ok: Boolean, fields: JsonObject = JsonObject(emptyMap()), notices: List<Notice> = emptyList()): JsonObject =
        JsonObject(buildMap {
            put("contract", JsonPrimitive(VERSION))
            put("tool", JsonPrimitive(tool))
            put("ok", JsonPrimitive(ok))
            putAll(fields)
            put("notices", noticesJson(notices))
        })

    /** The one-item result that carries [envelope]; `isError` follows its `ok`. */
    fun result(envelope: JsonObject): ToolCallResult {
        val ok = (envelope["ok"] as? JsonPrimitive)?.contentOrNull == "true"
        return ToolCallResult(listOf(ContentItem.Text(json.encodeToString(JsonObject.serializer(), envelope))), isError = !ok)
    }

    /** The envelope [result] carries, or null when it is not one. */
    fun envelopeOf(result: ToolCallResult): JsonObject? {
        val item = result.content.singleOrNull() as? ContentItem.Text ?: return null
        val obj = try {
            json.parseToJsonElement(item.text) as? JsonObject
        } catch (e: SerializationException) {
            null
        } ?: return null
        return obj.takeIf { (it["contract"] as? JsonPrimitive)?.intOrNull != null }
    }

    /**
     * [result] as an envelope: itself when it is one, else an envelope of [tool] whose `text` holds the result's text,
     * as an error the call ran into before a tool built its own. Images are left out; a tool that saves one names its
     * file in its fields.
     */
    fun wrap(tool: String, result: ToolCallResult): ToolCallResult {
        if (envelopeOf(result) != null) return result
        val text = result.content.filterIsInstance<ContentItem.Text>().joinToString("\n") { it.text }
        return result(envelope(tool, ok = !result.isError, fields = buildJsonObject { put("text", text) }))
    }

    /** [result]'s envelope with [notices] added after the ones it has; a result that is not an envelope is wrapped first. */
    fun withNotices(tool: String, result: ToolCallResult, notices: List<String>): ToolCallResult {
        if (notices.isEmpty()) return wrap(tool, result)
        val envelope = envelopeOf(wrap(tool, result))!!
        val existing = (envelope["notices"] as? JsonArray).orEmpty()
        val added = noticesJson(notices.map(::noticeOf))
        return result(JsonObject(envelope + ("notices" to JsonArray(existing + added))))
    }

    private fun noticesJson(notices: List<Notice>): JsonArray = JsonArray(notices.map { n ->
        buildJsonObject {
            put("kind", n.kind)
            n.side?.let { put("side", it) }
            put("text", n.text)
        }
    })

    /** Which way a call ended before its tool answered: [STILL_RUNNING] under a UI freeze, or [CANCELLED] inside the IDE. */
    enum class Interruption(val field: String) { STILL_RUNNING("still_running"), CANCELLED("cancelled") }

    /** The envelope of a call that ended [how], with [text] saying why and the [notices] it carries. */
    fun interrupted(tool: String, how: Interruption, text: String, notices: List<String>): JsonObject =
        envelope(tool, ok = false, fields = buildJsonObject { put(how.field, true); put("text", text) }, notices = notices.map(::noticeOf))

    /** An error of a steroid_execute_code run: an exception it printed or hit, with its stack trace, or why it failed. */
    data class ExecError(val kind: String, val message: String, val stackTrace: String? = null)

    /**
     * The envelope of a steroid_execute_code run. Line breaks in `stdout` are `\n` on every OS: printJson pretty-prints
     * with the platform's separator, which would make the same script's output differ between Windows and the rest.
     */
    fun executeCode(
        executionId: String,
        ok: Boolean,
        stdout: List<String>,
        messages: List<String>,
        errors: List<ExecError>,
        images: List<Pair<String, String>>,
    ): JsonObject {
        val out = stdout.joinToString("\n") { it.replace("\r\n", "\n") }
        return envelope("steroid_execute_code", ok, buildJsonObject {
            put("execution_id", executionId)
            put("stdout", out)
            parsedOutput(out)?.let { put("result", it) }
            put("messages", JsonArray(messages.map(::JsonPrimitive)))
            put("errors", JsonArray(errors.map { e ->
                buildJsonObject {
                    put("kind", e.kind)
                    put("message", e.message)
                    e.stackTrace?.let { put("stack_trace", it) }
                }
            }))
            put("images", JsonArray(images.map { (mime, file) -> buildJsonObject { put("mime_type", mime); put("file", file) } }))
        })
    }

    /** The envelope of one steroid_ui call: its report, and the verdict of a scenario or of a call with a bug check. */
    fun ui(executionId: String, ok: Boolean, verdict: UiVerdict.Verdict?, report: String, notices: List<Notice> = emptyList()): JsonObject =
        envelope("steroid_ui", ok, buildJsonObject {
            put("execution_id", executionId)
            verdict?.let { put("verdict", it.line); put("verdict_kind", it.kind.name) }
            put("report", report)
        }, notices)

    /** The envelope of a steroid_ui replay of several scenario files: one entry per file, and the summary as `report`. */
    fun uiBatch(reports: List<Pair<String, String>>, summary: String): JsonObject {
        val files = reports.map { (file, report) -> Triple(file, report, UiScenarioBatch.verdictOf(report)) }
        return envelope("steroid_ui", ok = files.none { UiScenarioBatch.isBad(it.third) }, fields = buildJsonObject {
            put("files", JsonArray(files.map { (file, report, verdict) ->
                buildJsonObject {
                    put("file", file)
                    put("verdict", verdict)
                    put("ok", !UiScenarioBatch.isBad(verdict))
                    put("report", report)
                }
            }))
            put("report", summary)
        })
    }

    /** The JSON a script printed as its whole output, or null when its output is not one JSON document. */
    fun parsedOutput(stdout: String): JsonElement? {
        val text = stdout.trim()
        if (text.isEmpty() || (text[0] != '{' && text[0] != '[')) return null
        return try {
            json.parseToJsonElement(text)
        } catch (e: SerializationException) {
            null
        }
    }
}
