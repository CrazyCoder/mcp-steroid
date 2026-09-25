/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import com.intellij.ide.impl.OpenProjectTask
import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.project.ex.ProjectManagerEx
import com.jonnyzzz.mcpSteroid.execution.dialogWindowsLookup
import com.jonnyzzz.mcpSteroid.mcp.ToolCallResult
import com.jonnyzzz.mcpSteroid.mcp.builder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

class OpenProjectToolHandlerIJ : OpenProjectToolHandler {
    private val logger = thisLogger()


    override suspend fun handleOpenProject(
        openProjectParams: OpenProjectParams,
        callProgress: McpProgressReporter,
    ): ToolCallResult {
        val projectPath = Path.of(openProjectParams.projectPath).toAbsolutePath()

        // backend_name is a devrig-only routing hint. A direct in-IDE connection serves exactly one
        // backend, so there is nothing to route to — log it and ignore (defense-in-depth for forward
        // compatibility; the direct surface never advertises the parameter).
        openProjectParams.backendName?.let {
            logger.info("steroid_open_project received backend_name='$it' on a direct IDE connection; ignoring (routing applies only via devrig).")
        }

        // Check if project is already open
        val existingProject = run { // #214: no read action — must not park behind a pending write (wedges every tool)
            ProjectManager.getInstance().openProjects.find { project ->
                project.basePath?.let { Path.of(it).toAbsolutePath().normalize() == projectPath.normalize() } == true
            }
        }

        if (existingProject != null) {
            return ToolCallResult.builder()
                .addTextContent("Project is already open: ${existingProject.name}")
                .addTextContent("Project path: ${existingProject.basePath}")
                .addTextContent("Use the project-listing tool or command to see all open projects.")
                .build()
        }

        val builder = ToolCallResult.builder()
        try {
            // Trust the project if requested
            if (openProjectParams.trustProject) {
                builder.addTextContent("Trusting project path: $projectPath")
                TrustedProjects.setProjectTrusted(projectPath, isTrusted = true)
                check(TrustedProjects.isProjectTrusted(projectPath)) {
                    "TrustedProjects did not mark path as trusted: $projectPath"
                }
                builder.addTextContent("Project path trusted successfully")
            }

            builder.addTextContent("Initiating project open: $projectPath")

            // Always a new frame: no "This Window / New Window" question, and no attach to another project.
            // Reusing a frame makes the platform consult the last focused frame's project, which can be a
            // closed, disposed one when the IDE window is not focused. An attach processor that reads that
            // project's services, such as the Multi-Project Workspace plugin's WorkspaceAttachProcessor, then
            // throws ProcessCanceledException and nothing opens (IDEA-394203).
            // build().withForceOpenInNewFrame() rather than the inline OpenProjectTask { } builder: the builder
            // inlines accessors that 2026.3 removed, so a plugin compiled against 261 fails with NoSuchMethodError.
            @Suppress("DEPRECATION")
            val task = OpenProjectTask.build().withForceOpenInNewFrame(true)
            // The open runs in an application scope, so it outlives this call: a dialog it shows (Trust Project,
            // in a JetBrains Client or in this IDE) waits for an answer the agent can give only after we return.
            val dialogsBefore = dialogWindowsLookup().showingModalDialogWindows()
            val opening = service<ProjectOpenScope>().scope.async {
                ProjectManagerEx.getInstanceEx().openProjectAsync(projectPath, task)
            }
            opening.invokeOnCompletion { failure ->
                when {
                    failure == null -> logger.info("Project open finished for $projectPath")
                    failure !is CancellationException -> logger.warn("Project open failed for $projectPath", failure)
                }
            }
            val start = awaitOpenStart(opening, OPEN_WAIT, OPEN_POLL) {
                (dialogWindowsLookup().showingModalDialogWindows() - dialogsBefore).isNotEmpty()
            }
            when (start) {
                is OpenStart.Opened -> builder.addTextContent("Project opened: ${start.project.name}")
                OpenStart.Declined -> return builder
                    .addTextContent(
                        "The IDE did not open the project: a dialog declined it, such as Don't Open in the " +
                            "trust dialog, or the open was cancelled."
                    )
                    .markAsError()
                    .build()
                OpenStart.DialogShowing -> builder.addTextContent(
                    "The project is still opening: a modal dialog is waiting for an answer. " +
                        "Read it with steroid_take_screenshot and answer it with steroid_input."
                )
                OpenStart.StillOpening -> builder.addTextContent(
                    "The project is still opening after $OPEN_WAIT. In Split Mode it can be waiting on a dialog " +
                        "in the JetBrains Client, such as Trust Project: check steroid_list_windows on the client."
                )
            }

            builder.addTextContent(OPEN_PROJECT_VERIFICATION_WORKFLOW)
            if (!openProjectParams.trustProject) {
                builder.addTextContent(
                    """
                        NOTE: trust_project was false. A 'Trust Project' dialog may appear.
                              Set trust_project=true to skip the trust dialog.
                    """.trimIndent()
                )
            }
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: Exception) {
            val message = "Failed to initiate project open: ${e.message}"
            logger.warn(message, e)
            builder.addTextContent("ERROR: $message").markAsError()
        }

        return builder.build()
    }
}

private val OPEN_WAIT = 20.seconds
private val OPEN_POLL = 250.milliseconds

@Service(Service.Level.APP)
internal class ProjectOpenScope(val scope: CoroutineScope)

internal sealed interface OpenStart<out T> {
    data class Opened<T>(val project: T) : OpenStart<T>
    data object Declined : OpenStart<Nothing>
    data object DialogShowing : OpenStart<Nothing>
    data object StillOpening : OpenStart<Nothing>
}

/**
 * Waits until [opening] finishes, [dialogShowing] reports a modal dialog, or [bound] passes, and says which
 * came first. [opening] keeps running afterwards. A failed open rethrows its exception; a null result means
 * the open was declined or cancelled.
 */
internal suspend fun <T : Any> awaitOpenStart(
    opening: Deferred<T?>,
    bound: Duration,
    poll: Duration,
    dialogShowing: suspend () -> Boolean,
): OpenStart<T> {
    val deadline = TimeSource.Monotonic.markNow() + bound
    while (true) {
        withTimeoutOrNull(poll) { opening.join() }
        if (opening.isCompleted) return opening.await()?.let { OpenStart.Opened(it) } ?: OpenStart.Declined
        if (dialogShowing()) return OpenStart.DialogShowing
        if (deadline.hasPassedNow()) return OpenStart.StillOpening
    }
}
