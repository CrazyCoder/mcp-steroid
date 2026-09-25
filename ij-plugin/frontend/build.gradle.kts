// mcp-steroid.frontend: the Split Mode frontend bridge. Conventions come from ij-plugin/build.gradle.kts.
plugins {
    id("org.jetbrains.intellij.platform.module")
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("rpc")
}
dependencies {
    intellijPlatform {
        bundledModule("intellij.platform.frontend")
    }
    implementation(project(":ij-plugin:shared"))
    compileOnly(rootProject.project(":ij-plugin").extensions.getByType<SourceSetContainer>()["main"].output)
    compileOnly(project(":mcp-core"))
    compileOnly(project(":mcp-steroid-server"))
}
