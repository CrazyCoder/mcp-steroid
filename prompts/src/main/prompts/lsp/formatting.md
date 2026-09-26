LSP: textDocument/formatting - Format Document

This example demonstrates how to format an entire document

Prefer the `steroid_refactor` tool, which needs no script: `{"op":"reformat","file":"src/Util.kt"}`
reports the lines it would add and remove, and `"apply": true` reformats the file. Adapt the script below
for what the tool does not do, such as formatting a range with `CodeStyleManager.reformatRange`.

```kotlin
import com.intellij.psi.codeStyle.CodeStyleManager

// Configuration - modify these for your use case
val filePath = "/path/to/your/File.kt"  // TODO: Set your file path
val dryRun = true  // Set to false to actually format the file


// Get the original content
val originalContent = readAction {
    val virtualFile = findFile(filePath)
        ?: return@readAction null to "File not found: $filePath"

    val document = FileDocumentManager.getInstance().getDocument(virtualFile)
        ?: return@readAction null to "Cannot get document for: $filePath"

    document.text to null
}

val (original, error) = originalContent
if (error != null) {
    println(error)
    return
}

if (dryRun) {
    // For dry run, work with a copy so the original file is not modified
    val copy = readAction {
        val virtualFile = findFile(filePath)!!
        val psiFile = PsiManager.getInstance(project).findFile(virtualFile)
            ?: return@readAction null
        psiFile.copy()
    }

    if (copy == null) {
        println("Cannot parse file: $filePath")
        return
    }

    // Format the copy (reformat modifies PSI, so it needs write access)
    writeAction {
        CodeStyleManager.getInstance(project).reformat(copy)
    }

    val formattedPreview = readAction { copy.text }

    println("Format Preview")
    println("=============")
    println()
    println("File: $filePath")
    println()

    if (formattedPreview == original) {
        println("No formatting changes needed.")
    } else {
        // Show simple diff
        val originalLines = original!!.lines()
        val formattedLines = formattedPreview.lines()

        println("Changes:")
        println("-".repeat(40))

        var changes = 0
        val maxLines = maxOf(originalLines.size, formattedLines.size)
        for (i in 0 until maxLines) {
            val origLine = originalLines.getOrNull(i) ?: ""
            val newLine = formattedLines.getOrNull(i) ?: ""
            if (origLine != newLine) {
                changes++
                if (changes <= 20) {
                    println("Line ${i + 1}:")
                    println("  - $origLine")
                    println("  + $newLine")
                }
            }
        }
        if (changes > 20) {
            println("... and ${changes - 20} more changes")
        }
        println()
        println("Total lines changed: $changes")
    }
    println()
    println("(Dry run - no changes made. Set dryRun=false to format)")
} else {
    // Actually format the file
    val virtualFile = findFile(filePath)!!
    val psiFile = readAction { PsiManager.getInstance(project).findFile(virtualFile) }
        ?: return println("Cannot parse file: $filePath")
    val document = FileDocumentManager.getInstance().getDocument(virtualFile)
        ?: return println("Cannot get document for: $filePath")

    WriteCommandAction.runWriteCommandAction(project, "Format Document", null, {
        val codeStyleManager = CodeStyleManager.getInstance(project)
        codeStyleManager.reformat(psiFile)
        PsiDocumentManager.getInstance(project).commitDocument(document)
        FileDocumentManager.getInstance().saveDocument(document)
    })

    println("Format Complete")
    println("===============")
    println()
    println("File: $filePath")
    println("File has been formatted according to project code style.")
}
```

# See also

IDE power operations:
- [Optimize Imports](mcp-steroid://ide/optimize-imports) - Remove unused imports
- [Code Hygiene](mcp-steroid://ide/inspection-summary) - Run inspections

Overview resources:
- [IntelliJ API Power User Guide](mcp-steroid://prompt/skill) - Core API patterns
