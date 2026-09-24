/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.prompts

import com.jonnyzzz.mcpSteroid.testHelper.ProjectHomeDirectory
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.util.stream.Collectors

/**
 * A `ProblemDescriptor.descriptionTemplate` holds placeholders such as `<code>#ref</code>`, so a
 * recipe that prints it shows the placeholder instead of the problem. Code blocks must render it
 * with `ProblemDescriptorUtil.renderDescriptionMessage(descriptor, element)` inside a read action.
 */
class ProblemDescriptionRenderingContractTest {

    @Test
    fun `code blocks render problem descriptions instead of printing the template`() {
        val promptsRoot = ProjectHomeDirectory.requireProjectHomeDirectory().resolve("prompts/src/main/prompts")
        val files = Files.walk(promptsRoot).use { stream ->
            stream.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".md") }
                .collect(Collectors.toList())
        }
        assertTrue(files.isNotEmpty(), "no prompt articles under $promptsRoot")

        val violations = mutableListOf<String>()
        for (file in files) {
            var inCode = false
            Files.readAllLines(file).forEachIndexed { index, line ->
                if (line.trimStart().startsWith("```")) inCode = !inCode
                else if (inCode && line.contains(".descriptionTemplate")) {
                    violations.add("${promptsRoot.relativize(file)}:${index + 1}: ${line.trim()}")
                }
            }
        }

        assertTrue(
            violations.isEmpty(),
            "Code blocks print the raw descriptionTemplate; render it with " +
                "ProblemDescriptorUtil.renderDescriptionMessage(descriptor, descriptor.psiElement):\n" +
                violations.joinToString("\n"),
        )
    }
}
