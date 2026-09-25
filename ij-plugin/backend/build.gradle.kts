// mcp-steroid.backend: serves SteroidBridgeApi. Conventions come from ij-plugin/build.gradle.kts.
plugins {
    id("org.jetbrains.intellij.platform.module")
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("rpc")
}
dependencies {
    intellijPlatform {
        bundledModule("intellij.platform.kernel.backend")
        bundledModule("intellij.platform.rpc.backend")
        bundledModule("intellij.platform.backend")
    }
    implementation(project(":ij-plugin:shared"))
    compileOnly(rootProject.project(":ij-plugin").extensions.getByType<SourceSetContainer>()["main"].output)
    compileOnly(project(":mcp-core"))
    compileOnly(project(":mcp-steroid-server"))
}
