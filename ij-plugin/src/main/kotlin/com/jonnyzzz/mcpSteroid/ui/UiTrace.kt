/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.ui

import com.intellij.util.ui.ImageUtil
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.awt.Window
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import javax.imageio.ImageIO

/**
 * A steroid_ui trace in the execution folder: a picture of the topmost window before and after each step, the
 * snapshot after it, one JSON line per step in `trace.jsonl`, and `trace.md`, which links them in order.
 */
class UiTrace(private val dir: Path) {
    private val markdown = StringBuilder("# steroid_ui trace\n")

    init {
        Files.createDirectories(dir)
    }

    val folder: Path get() = dir

    /** Paints [window] into `NN-<suffix>.png` and returns the file name. Call on the EDT. */
    fun picture(window: Window, index: Int, suffix: String): String {
        val name = "%02d-%s.png".format(index, suffix)
        paint(window, dir.resolve(name))
        return name
    }

    companion object {
        /** Paints [window] as it shows, without the rest of the screen, into the PNG [file]. Call on the EDT. */
        fun paint(window: Window, file: Path) {
            val image = ImageUtil.createImage(window.width.coerceAtLeast(1), window.height.coerceAtLeast(1), BufferedImage.TYPE_INT_ARGB)
            val g = image.createGraphics()
            try {
                window.printAll(g)
            } finally {
                g.dispose()
            }
            Files.createDirectories(file.parent)
            Files.newOutputStream(file).use { ImageIO.write(image, "png", it) }
        }
    }

    fun record(index: Int, label: String, line: String, failed: Boolean, snapshot: String, before: String?, after: String?, startedMs: Long, durationMs: Long) {
        val snapshotFile = "%02d-snapshot.txt".format(index)
        Files.writeString(dir.resolve(snapshotFile), snapshot)
        val json = buildJsonObject {
            put("index", index)
            put("step", label)
            put("result", line)
            put("failed", failed)
            put("startedMs", startedMs)
            put("durationMs", durationMs)
            before?.let { put("before", it) }
            after?.let { put("after", it) }
            put("snapshot", snapshotFile)
        }
        Files.writeString(dir.resolve("trace.jsonl"), "$json\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND)
        markdown.append("\n## ").append(label).append(if (failed) " (failed)" else "").append("\n\n")
            .append(line).append("\n\n")
        before?.let { markdown.append("Before: ![before](").append(it).append(")\n\n") }
        after?.let { markdown.append("After: ![after](").append(it).append(")\n\n") }
        markdown.append("[Snapshot](").append(snapshotFile).append(")\n")
        Files.writeString(dir.resolve("trace.md"), markdown)
    }
}
