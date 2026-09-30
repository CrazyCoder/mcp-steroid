/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pins the published-installer contract: the URLs both halves of the product fetch, and the one-liner
 * every user-facing surface (website install CTA, README, the IDE settings page) shows VERBATIM.
 */
class InstallerHostTest {

    @Test
    fun `the installer URLs are the latest release's assets`() {
        assertEquals(
            "https://github.com/CrazyCoder/mcp-steroid/releases/latest/download/install.sh",
            devrigInstallerUrl(isWin = false),
        )
        assertEquals(
            "https://github.com/CrazyCoder/mcp-steroid/releases/latest/download/install.ps1",
            devrigInstallerUrl(isWin = true),
        )
    }

    /**
     * The exact strings the README publishes and the installer templates carry in their headers. A drift
     * here means the IDE settings page shows a command the docs never promoted — change the README and the
     * templates together with this pin, or not at all.
     */
    @Test
    fun `the install one-liner matches the README, verbatim, per OS`() {
        assertEquals(
            "curl -fsSL https://github.com/CrazyCoder/mcp-steroid/releases/latest/download/install.sh | sh",
            devrigInstallOneLiner(isWin = false),
        )
        assertEquals(
            "irm https://github.com/CrazyCoder/mcp-steroid/releases/latest/download/install.ps1 | iex",
            devrigInstallOneLiner(isWin = true),
        )
    }

    /**
     * The mechanical half of the verbatim pin: the literals above say what the strings ARE, this reads
     * the published sources and proves they still SAY it — so a README-only or template-only edit
     * (say, `| sh` → `| bash`) fails here instead of drifting silently past the settings page. Same
     * lint-test pattern as `BuildScriptIncrementalInputsTest` (walk up to the repo root, read the
     * source file); the files are checked into this repo, so a missing one is a real breakage, not a
     * condition to skip on.
     */
    @Test
    fun `the README and installer templates carry the same one-liners`() {
        val posix = devrigInstallOneLiner(isWin = false)
        val windows = devrigInstallOneLiner(isWin = true)
        val published = mapOf(
            "README.md" to listOf(posix, windows),
            "installer-gen/src/main/resources/templates/install.sh.tmpl" to listOf(posix),
            "installer-gen/src/main/resources/templates/install.ps1.tmpl" to listOf(windows),
        )
        for ((relativePath, oneLiners) in published) {
            val file = repoRoot().resolve(relativePath)
            assertTrue(Files.isRegularFile(file), "published install source is missing: $file")
            val text = Files.readString(file)
            for (oneLiner in oneLiners) {
                assertTrue(
                    text.contains(oneLiner),
                    "$relativePath no longer carries the one-liner `$oneLiner` verbatim — " +
                        "change devrigInstallOneLiner and the published sources together, or not at all",
                )
            }
        }
    }

    /**
     * `:installer-gen` bakes the devrig zip of a release into the scripts these URLs serve, and does not depend
     * on this module, so it names the repository in a constant of its own. Both must name the same one.
     */
    @Test
    fun `the installer generator reads the releases of the same repository`() {
        val generator = repoRoot().resolve("installer-gen/src/main/kotlin/com/jonnyzzz/mcpSteroid/installer/InstallerGenerator.kt")
        val declared = Regex("""const val RELEASES_REPOSITORY = "([^"]+)"""").find(Files.readString(generator))?.groupValues?.get(1)
        assertEquals(RELEASES_REPOSITORY, declared, "$generator must declare RELEASES_REPOSITORY = \"$RELEASES_REPOSITORY\"")
    }

    private fun repoRoot(): Path {
        var dir = Path.of("").toAbsolutePath()
        while (!Files.isRegularFile(dir.resolve("settings.gradle.kts"))) {
            dir = dir.parent ?: error("repo root (settings.gradle.kts) not found above ${Path.of("").toAbsolutePath()}")
        }
        return dir
    }
}
