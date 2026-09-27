IDE: Inspection + Quick Fix

Run a named inspection on a file and apply its quick fix. The tool is resolved from the current profile by short-name, so the same recipe works in every JetBrains IDE.

Prefer the `steroid_refactor` tool, which needs no script:
`{"op":"fix","file":"src/Util.kt","inspection":"SimplifiableCallChain"}` lists the problems and their
fixes, and `"apply": true` applies the fix on the target's line (`symbol` or `line`), or every fix in the
file with `"all": true`. Without `inspection`, a dry run lists what every enabled inspection reports from
WEAK WARNING up, with short names and severities (`"all": true` adds INFORMATION-level suggestions). Use
the script below to choose a fix other than the first, or to post-process the problems.

`runInspectionsDirectly(file, inspections = setOf(shortName))` runs the named inspection the way
Code | Inspect Code does: in an IDE background task that gives way to every write action. It resolves
the inspection from the current profile by its short name, so the recipe runs unchanged in any
JetBrains IDE, and it runs the inspection whether the profile enables it or not.

**Do not call `InspectionEngine.inspectEx` from a script.** Inside a `readAction { }` or
`smartReadAction { }` it inspects on worker threads that a pending write action cannot cancel. One
slow inspection, or a JavaScript/TypeScript inspection that waits for the TypeScript service while the
service waits for a write, then holds the read lock, and the IDE's UI freezes until it finishes.

Find the short-name first: `mcp-steroid://ide/inspection-summary` lists every enabled
inspection with its short-name (e.g. `UnusedDeclaration`, `RedundantCast`,
`SpellCheckingInspection`).

```kotlin
import com.intellij.codeInspection.CommonProblemDescriptor
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemDescriptorUtil
import com.intellij.codeInspection.QuickFix
import com.intellij.openapi.command.CommandProcessor

data class ProblemInfo(
    val problem: ProblemDescriptor,
    val description: String,
    val fix: QuickFix<CommonProblemDescriptor>?,
    val fixName: String?
)

// Configuration - modify these for your use case
val filePath = "/path/to/your/File.kt" // TODO: Set your file path
val inspectionShortName = "SpellCheckingInspection" // TODO: short-name from mcp-steroid://ide/inspection-summary
val dryRun = true


val virtualFile = findFile(filePath)
    ?: return println("File not found: $filePath")

// An unknown short name fails with the similar names the profile has.
val result = runInspectionsDirectly(virtualFile, inspections = setOf(inspectionShortName))
if (result.failedTools.isNotEmpty()) {
    println("Inspection issues: " + result.failedTools.joinToString { "${it.toolId}: ${it.error}" })
}
val problems: List<ProblemDescriptor> = result[inspectionShortName].orEmpty()

if (problems.isEmpty()) {
    return println("No '$inspectionShortName' problems found in $filePath")
}

val problemInfo = readAction {
    val firstProblem = problems.first()
    val description = ProblemDescriptorUtil.renderDescriptionMessage(firstProblem, firstProblem.psiElement)
    val fix = firstProblem.fixes?.firstOrNull()
    ProblemInfo(firstProblem, description, fix, fix?.name)
}

println("Found ${problems.size} problem(s).")
println("First problem: ${problemInfo.description}")

val fix = problemInfo.fix
val fixName = problemInfo.fixName
if (fix == null || fixName == null) {
    return println("No quick fix available for the first problem.")
}

if (dryRun) {
    println("Quick fix available: $fixName")
    return println("Set dryRun=false to apply changes.")
}

// Apply it the way the editor does: on the EDT, inside one undoable command. A fix that
// reports startInWriteAction() = false (a ModCommand fix, as most Kotlin fixes are) takes
// its own write action and fails inside one.
withContext(Dispatchers.EDT) {
    CommandProcessor.getInstance().executeCommand(project, {
        if (fix.startInWriteAction()) {
            ApplicationManager.getApplication().runWriteAction { fix.applyFix(project, problemInfo.problem) }
        } else {
            fix.applyFix(project, problemInfo.problem)
        }
    }, fixName, null)
}

println("Applied quick fix: $fixName")
```

###_IF_IDE[RD]_###
# Rider: ReSharper analyses

ReSharper-backed C# analyses run out-of-process and are NOT `LocalInspectionTool` instances, so
`runInspectionsDirectly` does not see them; only IDE-frontend inspections (spellchecker, web, ...)
run this way in Rider.
###_END_IF_###

# Inspect a file in ANOTHER open project

`runInspectionsDirectly` and `steroid_refactor` inspect the project that the `project_name` tool
argument names (the unique routing key from `steroid_list_projects`, NOT the raw folder name). To
inspect a file of another open project, call them with that project's `project_name`.

# See also

- [Inspection Summary](mcp-steroid://ide/inspection-summary) - List every enabled inspection with its short-name
- [Find Duplicate Code](mcp-steroid://ide/find-duplicates) - Run `DuplicatedCode` and walk every clone cluster typed (no reflection)
- [Code Action](mcp-steroid://lsp/code-action) - Quick fixes and refactorings
- [Find References](mcp-steroid://lsp/find-references) - Find all usages of a symbol
- [Document Symbols](mcp-steroid://lsp/document-symbols) - List symbols in a document
- [IntelliJ API Power User Guide](mcp-steroid://prompt/skill) - Core API patterns
