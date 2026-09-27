Module Inspection Sweep

Run the enabled inspections across one module after changing several files.

Prefer the `steroid_refactor` tool, which needs no script:
`{"op":"inspect","file":"<the module's directory>"}` runs Code | Inspect Code over the directory,
global inspections included, in an IDE background task that gives way to the user's edits. It lists
`path:line: [short name] SEVERITY description`, `"inspection"` limits it to short names, and past
5 minutes it stops and says the list is incomplete. Without `"file"` it inspects the whole project.

The script below does the same file by file with `runInspectionsDirectly`, for a structured result
with the verification status vocabulary. Each call is its own background run, so the loop cannot
freeze the IDE. Do not replace it with `InspectionEngine.inspectEx` inside a read action: that can.

```kotlin[AI,IC,IU]
import com.intellij.codeInspection.ProblemDescriptorUtil
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager

val moduleName = "my-module" // TODO: module to inspect
val maxFiles = 200
val sourceExtensions = setOf("java", "kt", "kts")

fun collectSourceFiles(root: VirtualFile, output: MutableList<VirtualFile>) {
    if (output.size >= maxFiles) return
    if (!root.isDirectory) {
        if (root.extension in sourceExtensions) output += root
        return
    }
    for (child in root.children) {
        collectSourceFiles(child, output)
        if (output.size >= maxFiles) return
    }
}

val module = readAction {
    ModuleManager.getInstance(project).findModuleByName(moduleName)
}
if (module == null) {
    printJson(
        mapOf(
            "status" to "did_not_run",
            "check_ran" to false,
            "target" to moduleName,
            "message" to "Module was not found.",
        )
    )
    return
}

val files = readAction {
    val psiManager = PsiManager.getInstance(project)
    val result = mutableListOf<VirtualFile>()
    ModuleRootManager.getInstance(module).sourceRoots.forEach { root ->
        collectSourceFiles(root, result)
    }
    result
        .distinctBy { it.path }
        .filter { psiManager.findFile(it) != null }
}

if (files.isEmpty()) {
    printJson(
        mapOf(
            "status" to "did_not_run",
            "check_ran" to false,
            "target" to moduleName,
            "message" to "Module scope contains no inspectable source files.",
        )
    )
    return
}

val diagnostics = mutableListOf<Map<String, Any?>>()
val failedTools = mutableListOf<Any>()

for (file in files) {
    val result = runInspectionsDirectly(file)
    failedTools.addAll(result.failedTools)
    val fileDiagnostics = readAction {
        result.entries.flatMap { (toolId, descriptors) ->
            descriptors.map { descriptor ->
                val element = descriptor.psiElement
                val psiFile = element?.containingFile
                val vf = psiFile?.virtualFile ?: file
                val line = element?.textRange?.startOffset?.let { offset ->
                    FileDocumentManager.getInstance()
                        .getDocument(vf)
                        ?.getLineNumber(offset)
                        ?.plus(1)
                }
                mapOf(
                    "toolId" to toolId,
                    "path" to vf.path,
                    "line" to line,
                    "message" to ProblemDescriptorUtil.renderDescriptionMessage(descriptor, element),
                    "elementText" to (element?.text ?: ""),
                )
            }
        }
    }
    diagnostics += fileDiagnostics
}

val status = when {
    failedTools.isNotEmpty() -> "check_failed"
    diagnostics.isNotEmpty() -> "findings"
    else -> "clean"
}

printJson(
    mapOf(
        "status" to status,
        "check_ran" to true,
        "target" to moduleName,
        "filesVisited" to files.size,
        "filesTruncated" to (files.size >= maxFiles),
        "diagnostics" to diagnostics.take(100),
        "diagnosticsTruncated" to (diagnostics.size > 100),
        "failedTools" to failedTools,
    )
)
```

Pitfalls:

- This is a module-scope loop that inspects one file at a time, with the
  `runInspectionsDirectly` failure contract. Global inspections that need a
  cross-file reference graph, such as unused declarations, see one file per call
  here; `steroid_refactor` `inspect` on the module's directory runs them over
  the whole directory.
- A module with no source files is `did_not_run`, not `clean`.
- Routine post-edit checks should narrow the scope to changed files first. A
  module sweep can be expensive because every visited file runs the enabled
  file-scoped inspection set.
- `filesTruncated=true` means the sweep hit `maxFiles`; treat the result as a
  capped sample, not full module coverage.
- A non-empty `failedTools` list is `check_failed`, even when diagnostics are
  otherwise empty.

# See also

- [Verify After Edit](mcp-steroid://ide/verify-after-edit) - Canonical verification status vocabulary.
- [Inspection Summary](mcp-steroid://ide/inspection-summary) - List enabled inspections.
- [Inspect and Fix](mcp-steroid://ide/inspect-and-fix) - Run one named inspection and apply a quick fix.
