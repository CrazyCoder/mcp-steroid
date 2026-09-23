/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.updates

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

const val RELEASE_JSON_URL = "https://github.com/CrazyCoder/mcp-steroid/releases/latest/download/release.json"
const val RELEASES_PAGE_URL = "https://github.com/CrazyCoder/mcp-steroid/releases/latest"

/** The `version` of a release.json document, without a leading `v`; null when the text is not one. */
fun parseReleaseVersion(json: String): String? = try {
    Json.parseToJsonElement(json).jsonObject["version"]?.jsonPrimitive?.content
        ?.removePrefix("v")?.takeIf { it.isNotBlank() }
} catch (_: Exception) {
    null
}
