IDE: Move File

This example moves a file to another directory,

Prefer the `steroid_refactor` tool, which needs no script:
`{"op":"move","file":"src/Util.kt","to":"src/util"}` lists the references to the file, and
`"apply": true` moves it and updates them. Adapt the script below to move several files in one processor.

```kotlin
import com.intellij.refactoring.move.moveFilesOrDirectories.MoveFilesOrDirectoriesProcessor

data class MoveData(
    val psiFile: PsiFile,
    val targetDir: PsiDirectory,
    val sourcePath: String,
    val targetPath: String,
    val fileName: String
)

// Configuration - modify these for your use case
val filePath = "/path/to/your/File.java" // TODO: Set your file path
val targetDirPath = "/path/to/target/dir" // TODO: Set your target directory
val dryRun = true


val moveData = readAction {
    val sourceFile = findFile(filePath) ?: return@readAction null
    val targetVf = findFile(targetDirPath) ?: return@readAction null
    val psi = PsiManager.getInstance(project).findFile(sourceFile)
    val dir = PsiManager.getInstance(project).findDirectory(targetVf)
    if (psi == null || dir == null) return@readAction null
    MoveData(
        psi,
        dir,
        psi.virtualFile.path,
        dir.virtualFile.path,
        psi.name
    )
}

if (moveData == null) {
    println("Source file or target directory not found.")
    return
}

if (dryRun) {
    println("Move prepared: ${moveData.sourcePath}")
    println("Target dir: ${moveData.targetPath}")
    println("Set dryRun=false to apply changes.")
    return
}

val processor = MoveFilesOrDirectoriesProcessor(
    project,
    arrayOf(moveData.psiFile),
    moveData.targetDir,
    true,
    false,
    false,
    null,
    null
)

writeIntentReadAction { processor.run() }
println("Moved file: ${moveData.fileName}")
```

# See also

- [Code Action](mcp-steroid://lsp/code-action) - Quick fixes and refactorings
- [Find References](mcp-steroid://lsp/find-references) - Find all usages of a symbol
- [IntelliJ API Power User Guide](mcp-steroid://prompt/skill) - Core API patterns
