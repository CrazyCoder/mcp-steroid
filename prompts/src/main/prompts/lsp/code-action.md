LSP: textDocument/codeAction - Code Actions / Quick Fixes

This example demonstrates how to get available code actions (quick fixes,

```kotlin
import com.intellij.codeInsight.intention.IntentionManager
import com.intellij.codeInspection.*
import com.intellij.codeInspection.ex.LocalInspectionToolWrapper
import com.intellij.profile.codeInspection.InspectionProjectProfileManager
import com.intellij.psi.util.PsiTreeUtil

// Configuration - modify these for your use case
val filePath = "/path/to/your/File.kt"  // TODO: Set your file path
val line = 10      // TODO: 1-based line number
val column = 15    // TODO: 1-based column number


// Find the virtual file
val virtualFile = findFile(filePath)
    ?: return println("File not found: $filePath")

val (psiFile, document) = readAction {
    val psiFile = PsiManager.getInstance(project).findFile(virtualFile)
    val document = FileDocumentManager.getInstance().getDocument(virtualFile)
    psiFile to document
}
if (psiFile == null) {
    return println("Cannot parse file: $filePath")
}
if (document == null) {
    return println("Cannot get document for: $filePath")
}

// Convert line/column to offset
val offset = document.getLineStartOffset(line - 1) + (column - 1)

val result = readAction {
    val element = psiFile.findElementAt(offset)
        ?: return@readAction "No element at position ($line:$column)"

    buildString {
        appendLine("Code Actions at $filePath:$line:$column")
        appendLine("=".repeat(50))
        appendLine()

        // 1. Check for syntax errors
        val errors = PsiTreeUtil.findChildrenOfType(psiFile, PsiErrorElement::class.java)
        if (errors.isNotEmpty()) {
            appendLine("Syntax Errors (${errors.size}):")
            errors.take(5).forEach { error ->
                val errorLine = document.getLineNumber(error.textOffset) + 1
                appendLine("  - Line $errorLine: ${error.errorDescription}")
            }
            appendLine()
        }

        // 2. List available intentions (general)
        appendLine("Available Intentions:")
        appendLine("-".repeat(30))

        val intentionManager = IntentionManager.getInstance()
        val allIntentions = intentionManager.intentionActions

        // Best-effort availability check without editor
        var availableCount = 0
        allIntentions.forEach { intention ->
            try {
                if (intention.isAvailable(project, null, psiFile)) {
                    availableCount++
                    if (availableCount <= 15) {
                        appendLine("  [Intention] ${intention.text}")
                    }
                }
            } catch (e: Exception) {
                // Some intentions may throw during availability check
            }
        }
        if (availableCount > 15) {
            appendLine("  ... and ${availableCount - 15} more intentions")
        }
        if (availableCount == 0) {
            appendLine("  (no intentions available at this position)")
        }
        appendLine()

        // 3. List inspection-based problems in the file
        appendLine("Inspection Problems in File:")
        appendLine("-".repeat(30))

        val inspectionManager = InspectionManager.getInstance(project)
        val profile = InspectionProjectProfileManager.getInstance(project).currentProfile

        val tools = profile.getAllEnabledInspectionTools(project)
        var problemCount = 0

        for (toolWrapper in tools.take(20)) {
            val wrapper = toolWrapper.tool
            if (wrapper is LocalInspectionToolWrapper) {
                try {
                    val localTool = wrapper.tool
                    val problems = localTool.checkFile(psiFile, inspectionManager, false)
                    if (problems != null) {
                        for (problem in problems) {
                            problemCount++
                            if (problemCount <= 10) {
                                val probOffset = problem.psiElement?.textOffset ?: 0
                                val probLine = document.getLineNumber(probOffset) + 1
                                val message = ProblemDescriptorUtil.renderDescriptionMessage(problem, problem.psiElement)
                                appendLine("  Line $probLine: ${message.take(60)}")

                                // List available fixes
                                val fixes = problem.fixes
                                if (fixes != null) {
                                    for (fix in fixes) {
                                        appendLine("    -> Fix: ${fix.name}")
                                    }
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    // Inspection may fail
                }
            }
        }
        if (problemCount > 10) {
            appendLine("  ... and ${problemCount - 10} more problems")
        }
        if (problemCount == 0) {
            appendLine("  (no inspection problems found)")
        }
    }
}

println(result)
```

# See also

IDE power operations:
- [Inspect and Fix](mcp-steroid://ide/inspect-and-fix) - Run inspection and apply fix
- [Inspection Summary](mcp-steroid://ide/inspection-summary) - List enabled inspections

Overview resources:
- [IntelliJ API Power User Guide](mcp-steroid://prompt/skill) - Core API patterns
