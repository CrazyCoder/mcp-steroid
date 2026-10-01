/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.demo

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.Animator
import com.intellij.util.ui.AsyncProcessIcon
import com.intellij.util.ui.JBUI
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*
import kotlin.random.Random

/**
 * The visual overlay panel for Demo Mode.
 * Shows execution progress with warm orange theme and animated status line.
 * 
 * Note: Uses java.awt.Color intentionally for consistent demo appearance
 * across light/dark themes - the warm orange theme should look identical
 * regardless of IDE theme.
 */
@Suppress("UseJBColor") // Intentional: consistent demo appearance across themes
class DemoOverlayPanel(
    private val disposable: Disposable,
    private val onCloseRequest: () -> Unit
) : JPanel() {

    // Warm color palette (inspired by devrig.dev)
    // Using java.awt.Color intentionally for consistent demo appearance
    private val primaryOrange = Color(249, 115, 22)      // #F97316
    private val secondaryOrange = Color(251, 146, 60)    // #FB923C
    private val accentOrange = Color(253, 186, 116)      // #FDBA74
    private val warmBrownDark = Color(35, 28, 24)        // Gradient start
    private val warmBrownLight = Color(55, 42, 35)       // Gradient end
    private val consoleBg = Color(25, 20, 18)            // Console background
    private val consoleText = Color(230, 220, 210)       // Warm white

    // Fonts
    private val jetBrainsMonoBold = Font("JetBrains Mono", Font.BOLD, 16)
    private val jetBrainsMonoRegular = Font("JetBrains Mono", Font.PLAIN, 12)
    private val jetBrainsMonoBoldConsole = Font("JetBrains Mono", Font.BOLD, 12)
    private val jetBrainsMonoItalic = Font("JetBrains Mono", Font.ITALIC, 11)

    // 20 synonyms for animated status line
    private val runningWords = listOf(
        "Running", "Processing", "Executing", "Working", "Computing",
        "Analyzing", "Thinking", "Evaluating", "Compiling", "Building",
        "Loading", "Preparing", "Scanning", "Inspecting", "Resolving",
        "Transforming", "Generating", "Synthesizing", "Optimizing", "Crunching"
    )

    private var currentWordIndex = 0
    private var dotCount = 1
    private var alpha = 0f

    private val logArea: JTextArea
    private val statusLabel: JLabel
    private val spinner: AsyncProcessIcon

    private var fadeAnimator: Animator? = null
    private val dotTimer: Timer
    private val wordTimer: Timer

    companion object {
        const val PANEL_WIDTH = 580
        const val PANEL_HEIGHT = 420
        const val CORNER_RADIUS = 16
    }

    init {
        layout = BorderLayout(12, 12)
        border = JBUI.Borders.empty(20)
        isOpaque = false
        // Never takes keyboard focus: input the agent dispatches must reach the IDE component under the overlay.
        isFocusable = false
        preferredSize = Dimension(PANEL_WIDTH, PANEL_HEIGHT)

        // Status label for animated line
        statusLabel = JLabel().apply {
            font = jetBrainsMonoBoldConsole
            foreground = Color.WHITE
            text = "${runningWords[0]}."
        }

        // Spinner
        spinner = AsyncProcessIcon("demo-mode-spinner")
        Disposer.register(disposable, spinner)

        // Log area
        logArea = JTextArea().apply {
            isEditable = false
            lineWrap = false
            isOpaque = false
            foreground = consoleText
            border = JBUI.Borders.empty(12, 12, 4, 12)
            font = jetBrainsMonoRegular
            text = ""
        }

        // Build UI
        add(createHeaderPanel(), BorderLayout.NORTH)
        add(createConsolePanel(), BorderLayout.CENTER)
        add(createFooterPanel(), BorderLayout.SOUTH)

        // Dot animation timer (250ms - 37% faster than 400ms)
        dotTimer = Timer(250) {
            dotCount = (dotCount % 3) + 1
            statusLabel.text = "${runningWords[currentWordIndex]}${".".repeat(dotCount)}"
        }

        // Word change timer (random 7-15 seconds)
        wordTimer = Timer(Random.nextInt(7000, 15001)) {
            currentWordIndex = (currentWordIndex + 1) % runningWords.size
            (it.source as Timer).delay = Random.nextInt(7000, 15001)
        }

        // Cleanup timers on dispose
        Disposer.register(disposable) {
            dotTimer.stop()
            wordTimer.stop()
            fadeAnimator?.dispose()
        }
    }

    private fun createHeaderPanel(): JPanel {
        return JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.emptyBottom(8)

            val leftPanel = JPanel(FlowLayout(FlowLayout.LEFT, 10, 0)).apply {
                isOpaque = false
                add(JLabel(AllIcons.Toolwindows.ToolWindowRun))
                add(JLabel("MCP Steroid is working").apply {
                    font = jetBrainsMonoBold
                    foreground = primaryOrange
                })
                add(spinner)
            }
            add(leftPanel, BorderLayout.WEST)

            val closeButton = JLabel("✕").apply {
                font = Font("JetBrains Mono", Font.BOLD, 18)
                foreground = secondaryOrange
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                border = JBUI.Borders.empty(0, 10)
                addMouseListener(object : MouseAdapter() {
                    override fun mouseClicked(e: MouseEvent) {
                        onCloseRequest()
                    }
                    override fun mouseEntered(e: MouseEvent) {
                        foreground = primaryOrange
                    }
                    override fun mouseExited(e: MouseEvent) {
                        foreground = secondaryOrange
                    }
                })
            }
            add(closeButton, BorderLayout.EAST)
        }
    }

    private fun createConsolePanel(): JPanel {
        val consolePanel = object : JPanel(BorderLayout()) {
            init { isOpaque = false }

            override fun paintComponent(g: Graphics) {
                val g2d = g as Graphics2D
                g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2d.color = consoleBg
                g2d.fillRoundRect(0, 0, width, height, 8, 8)
            }
        }

        val statusPanel = JPanel(FlowLayout(FlowLayout.LEFT, 12, 8)).apply {
            isOpaque = false
            add(statusLabel)
        }

        val logScrollPane = JBScrollPane(logArea).apply {
            border = JBUI.Borders.empty()
            isOpaque = false
            viewport.isOpaque = false
        }

        val consoleContent = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(logScrollPane, BorderLayout.CENTER)
            add(statusPanel, BorderLayout.SOUTH)
        }

        consolePanel.add(consoleContent, BorderLayout.CENTER)
        consolePanel.border = BorderFactory.createLineBorder(Color(80, 60, 40), 1)

        return consolePanel
    }

    private fun createFooterPanel(): JPanel {
        return JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            isOpaque = false
            border = JBUI.Borders.emptyTop(8)
            add(JLabel("Click ✕ to dismiss").apply {
                font = jetBrainsMonoItalic
                foreground = accentOrange
            })
        }
    }

    override fun paintComponent(g: Graphics) {
        val g2d = g as Graphics2D
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)

        // Apply fade alpha
        g2d.composite = AlphaComposite.SrcOver.derive(alpha)

        // Gradient background
        val gradient = GradientPaint(
            0f, 0f, warmBrownDark,
            width.toFloat(), height.toFloat(), warmBrownLight
        )
        g2d.paint = gradient
        g2d.fillRoundRect(0, 0, width, height, CORNER_RADIUS, CORNER_RADIUS)

        // Orange border
        g2d.color = primaryOrange
        g2d.stroke = BasicStroke(2f)
        g2d.drawRoundRect(1, 1, width - 3, height - 3, CORNER_RADIUS, CORNER_RADIUS)
    }

    /**
     * Update the log content.
     */
    @Suppress("unused") // Public API for batch updates
    fun updateLog(lines: List<String>) {
        logArea.text = lines.joinToString("\n")
        logArea.caretPosition = logArea.document.length
    }

    /**
     * Append a line to the log.
     */
    fun appendLogLine(line: String) {
        val currentText = logArea.text
        val newText = if (currentText.isEmpty()) line else "$currentText\n$line"
        val lines = newText.lines().takeLast(DemoModeSettings.maxLines)
        logArea.text = lines.joinToString("\n")
        logArea.caretPosition = logArea.document.length
    }

    /**
     * Start animations and fade in.
     */
    fun fadeIn(durationMs: Int = 300) {
        fadeAnimator?.dispose()
        spinner.resume()
        dotTimer.start()
        wordTimer.start()

        fadeAnimator = object : Animator("DemoFadeIn", 10, durationMs, false, true, disposable) {
            override fun paintNow(frame: Int, totalFrames: Int, cycle: Int) {
                alpha = frame.toFloat() / totalFrames
                repaint()
            }
        }
        fadeAnimator?.resume()
    }

    /**
     * Fade out and call onComplete when done.
     */
    fun fadeOut(durationMs: Int = 300, onComplete: () -> Unit) {
        fadeAnimator?.dispose()
        spinner.suspend()
        dotTimer.stop()
        wordTimer.stop()

        fadeAnimator = object : Animator("DemoFadeOut", 10, durationMs, false, false, disposable) {
            override fun paintNow(frame: Int, totalFrames: Int, cycle: Int) {
                alpha = 1f - (frame.toFloat() / totalFrames)
                repaint()
            }

            override fun paintCycleEnd() {
                onComplete()
            }
        }
        fadeAnimator?.resume()
    }
}
