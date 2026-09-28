IDE: Run Configuration

This example lists available run configurations and can optionally

To read the output of a run that already ran, no script is needed: `steroid_ui` with
`[{"action":"get","console":"App","lines":40}]` gives the last lines and the exit code of the latest run named App,
`""` for the latest run, with error lines marked `! `. Every tool result starts with a `RUN FAILED` notice for a run
that exited with an error since the previous call.

```kotlin
import com.intellij.execution.RunManager
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.ExecutorRegistry

// Configuration - modify these for your use case
val runConfigName = ""  // Leave empty to list only
val executorId = DefaultRunExecutor.EXECUTOR_ID
val dryRun = true


val manager = RunManager.getInstance(project)
val settings = manager.allSettings

println("Run Configurations (${settings.size}):")
settings.forEach { setting ->
    val typeName = setting.type.displayName
    println("  - ${setting.name} ($typeName)")
}

if (runConfigName.isBlank()) {
    println("Set runConfigName to execute a configuration.")
    return
}

val setting = settings.firstOrNull { it.name == runConfigName }
if (setting == null) {
    println("Run configuration not found: $runConfigName")
    return
}

val executor = ExecutorRegistry.getInstance().getExecutorById(executorId)
if (executor == null) {
    println("Executor not found: $executorId")
    return
}

if (dryRun) {
    println("Dry run: would execute '${setting.name}' with executor '$executorId'.")
    return
}

withContext(Dispatchers.EDT) {
    ProgramRunnerUtil.executeConfiguration(setting, executor)
}
println("Started run configuration: ${setting.name}")
```

# See also

- [Run Tests](mcp-steroid://test/run-tests) - Execute test run configuration
- [Wait for Completion](mcp-steroid://test/wait-for-completion) - Poll test status
- [Inspect Test Results](mcp-steroid://test/inspect-test-results) - Access test results
- [List Run Configurations](mcp-steroid://test/list-run-configurations) - Discover available tests
- [Debug Run Configuration](mcp-steroid://debugger/debug-run-configuration) - Start debugging
- [Debug Session Control](mcp-steroid://debugger/debug-session-control) - Pause/resume/stop
- [Test Examples Overview](mcp-steroid://test/overview) - Test execution workflows
