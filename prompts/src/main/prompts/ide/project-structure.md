IDE: Project Structure

Add a module with a content root, source roots and excluded folders, the way File | Project Structure | Modules does, without the dialog.

The project model is platform API: `ModuleManager` creates the module and
`ModuleRootModificationUtil.updateModel` edits its roots in one write action, in every JetBrains IDE. The
module type id picks the kind: `WEB_MODULE` for JavaScript and TypeScript, `JAVA_MODULE` for Java and Kotlin,
`PYTHON_MODULE` in PyCharm. A folder inside another module's content root can be its own module: the inner
content root wins for the files under it.

A project imported from a build tool (Maven, Gradle, Cargo) owns its modules: the next sync rewrites them.
Link the build file through the tool instead, and add hand-made modules only for folders no build tool covers.

```kotlin
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.vfs.LocalFileSystem
import java.nio.file.Path

val moduleName = "desktop"                      // TODO: the new module's name
val rootPath = "desktop"                        // TODO: its content root, relative to the project
val sources = listOf("src")                     // TODO: source roots, relative to the content root
val excludes = listOf("dist", "node_modules")   // TODO: excluded folders, relative to the content root
val moduleType = "WEB_MODULE"                   // TODO: WEB_MODULE, JAVA_MODULE, PYTHON_MODULE, ...
val dryRun = true

val base = Path.of(project.basePath ?: error("the project has no base directory"))
val root = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(base.resolve(rootPath))
    ?: error("not found: $rootPath")
val manager = ModuleManager.getInstance(project)
check(manager.findModuleByName(moduleName) == null) { "a module named $moduleName already exists" }
println("module $moduleName ($moduleType) at ${root.path}: sources $sources, excluded $excludes")
if (dryRun) {
    println("dry run: set dryRun = false to create it")
    return
}

val module = writeAction {
    val model = manager.getModifiableModel()
    val created = model.newModule(base.resolve(".idea/$moduleName.iml"), moduleType)
    model.commit()
    created
}
ModuleRootModificationUtil.updateModel(module) { roots ->
    roots.inheritSdk()
    val entry = roots.addContentEntry(root)
    sources.forEach { entry.addSourceFolder(root.url + "/" + it, false) }
    excludes.forEach { entry.addExcludeFolder(root.url + "/" + it) }
}
ModuleRootManager.getInstance(module).contentEntries.forEach { entry ->
    println("content ${entry.url}")
    entry.sourceFolders.forEach { println("  source ${it.url}") }
    entry.excludeFolderUrls.forEach { println("  excluded $it") }
}
```

The module lives in memory until the project saves: `project.save()` writes the `.iml` and
`.idea/modules.xml`. The save rewrites `modules.xml` from the model and can drop an entry the model does not
hold, such as one left by a build tool under another module name, so run `git diff .idea/modules.xml` after it.

Check the result with `ProjectFileIndex.getInstance(project)`: `getModuleForFile(vf)`, `isInSourceContent(vf)`
and `isExcluded(vf)` per path, in a read action.

Build tools link through their own services, imported directly (every installed plugin is on the script
classpath):

- Cargo, with the Rust plugin: set the toolchain first, or the workspace stays out of date:
  `project.rustSettings.modify { it.toolchain = RsToolchainBase.suggest(crateDir) }`, then
  `project.service<CargoProjectsService>().attachCargoProject(crateDir.resolve("Cargo.toml"))` and
  `refreshAllProjects()`. The plugin adds the crate's `src` as a source root of the module that holds it.
- Maven and Gradle: see `mcp-steroid://skill/execute-code-maven` and `mcp-steroid://skill/execute-code-gradle`.

# See also

- [Project Dependencies](mcp-steroid://ide/project-dependencies) - Summarize module dependencies
- [IDE Examples Overview](mcp-steroid://ide/overview) - All IDE examples
