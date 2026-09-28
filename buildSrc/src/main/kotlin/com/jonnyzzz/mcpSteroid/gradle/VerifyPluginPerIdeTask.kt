/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Nested
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.UntrackedTask
import org.gradle.jvm.toolchain.JavaLauncher
import org.gradle.process.ExecOperations
import org.gradle.workers.WorkAction
import org.gradle.workers.WorkParameters
import org.gradle.workers.WorkerExecutor
import java.io.File
import javax.inject.Inject

/**
 * Runs the IntelliJ Plugin Verifier once per IDE, each in its own JVM, all at once.
 *
 * One verifier JVM given several IDEs checks them together, and they slow each other down inside it:
 * IU-262 takes 23 s alone and about 75 s next to 261 and 263. Separate JVMs finish in the time of the
 * slowest IDE. The failure rule matches the IntelliJ Platform Gradle Plugin's own verifyPlugin: the build
 * fails when a section whose heading starts with one of [failureHeadings] follows an IDE's result line.
 */
@UntrackedTask(because = "the verdict depends on the IDEs and the verifier, and the report is for reading")
abstract class VerifyPluginPerIdeTask : DefaultTask() {
    @get:Classpath
    abstract val verifierClasspath: ConfigurableFileCollection

    @get:InputFile
    abstract val archiveFile: RegularFileProperty

    /** Unpacked IDE directories, one verifier JVM each. */
    @get:Internal
    abstract val ides: ConfigurableFileCollection

    @get:Internal
    abstract val runtimeDirectory: DirectoryProperty

    /** The verifier writes each IDE's report into its own subdirectory here. */
    @get:Internal
    abstract val reportsDirectory: DirectoryProperty

    /** Each IDE's verifier gets its own home under this directory, so the processes share no cache. */
    @get:Internal
    abstract val homesDirectory: DirectoryProperty

    /** Verifier output and exit code, `<ide directory name>.log` and `.exit` per IDE. */
    @get:Internal
    abstract val logsDirectory: DirectoryProperty

    /** Arguments after the reports and runtime directories, before the plugin archive. */
    @get:Input
    abstract val options: ListProperty<String>

    @get:Input
    abstract val failureHeadings: ListProperty<String>

    @get:Nested
    abstract val javaLauncher: Property<JavaLauncher>

    @get:Inject
    abstract val workerExecutor: WorkerExecutor

    @TaskAction
    fun verify() {
        val ideDirs = ides.files.sortedBy { it.name }
        if (ideDirs.isEmpty()) throw GradleException("No IDE is configured for plugin verification")
        val logs = logsDirectory.get().asFile.apply { mkdirs() }

        val queue = workerExecutor.noIsolation()
        ideDirs.forEach { ide ->
            queue.submit(RunVerifier::class.java) {
                classpath.from(verifierClasspath)
                javaExecutable.set(javaLauncher.get().executablePath.asFile.absolutePath)
                homeDirectory.set(homesDirectory.get().dir(ide.name).asFile.absolutePath)
                arguments.set(
                    listOf(
                        "check-plugin",
                        "-verification-reports-dir", reportsDirectory.get().asFile.absolutePath,
                        "-runtime-dir", runtimeDirectory.get().asFile.absolutePath,
                    ) + options.get() + archiveFile.get().asFile.absolutePath + ide.absolutePath,
                )
                logFile.set(File(logs, "${ide.name}.log"))
                exitCodeFile.set(File(logs, "${ide.name}.exit"))
            }
        }
        queue.await()

        val failures = ideDirs.mapNotNull { ide ->
            val log = File(logs, "${ide.name}.log")
            val output = log.readText()
            val exitCode = File(logs, "${ide.name}.exit").readText().trim().toInt()
            output.lineSequence().filter { resultLine.containsMatchIn(it) }.forEach { logger.lifecycle(it) }
            val sections = failedSections(output, failureHeadings.get())
            when {
                exitCode != 0 -> "${ide.name}: the verifier exited with $exitCode, see $log"
                output.contains(INVALID_PLUGIN) -> "${ide.name}: $INVALID_PLUGIN, see $log"
                sections.isNotEmpty() -> "${ide.name}: ${sections.joinToString()}, see $log"
                else -> null
            }
        }
        logger.lifecycle("Verifier reports: ${reportsDirectory.get().asFile}")
        if (failures.isNotEmpty()) throw GradleException("Plugin verification failed:\n" + failures.joinToString("\n"))
    }

    interface VerifierParameters : WorkParameters {
        val classpath: ConfigurableFileCollection
        val javaExecutable: Property<String>
        val homeDirectory: Property<String>
        val arguments: ListProperty<String>
        val logFile: RegularFileProperty
        val exitCodeFile: RegularFileProperty
    }

    /** One verifier JVM. The exit code goes to a file, because a work action returns nothing to its task. */
    abstract class RunVerifier : WorkAction<VerifierParameters> {
        @get:Inject
        abstract val execOperations: ExecOperations

        override fun execute() {
            val p = parameters
            val result = p.logFile.get().asFile.outputStream().use { out ->
                execOperations.javaexec {
                    executable = p.javaExecutable.get()
                    classpath = p.classpath
                    mainClass.set("com.jetbrains.pluginverifier.PluginVerifierMain")
                    jvmArgs("-Dplugin.verifier.home.dir=${p.homeDirectory.get()}")
                    args(p.arguments.get())
                    standardOutput = out
                    errorOutput = out
                    isIgnoreExitValue = true
                }
            }
            p.exitCodeFile.get().asFile.writeText(result.exitValue.toString())
        }
    }

    companion object {
        private const val INVALID_PLUGIN = "The following files specified for the verification are not valid plugins:"
        private val resultLine = Regex("^Plugin .*? against (\\S+):")

        /** The headings from [headings] that open a section after an IDE's result line in [output]. */
        fun failedSections(output: String, headings: List<String>): List<String> {
            val lines = output.lines()
            val start = lines.indexOfFirst { resultLine.containsMatchIn(it) }
            if (start < 0) return emptyList()
            return lines.drop(start + 1).mapNotNull { line -> headings.firstOrNull { line.startsWith(it) } }.distinct()
        }
    }
}
