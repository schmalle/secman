pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
    plugins {
        id("org.jetbrains.kotlin.jvm") version "2.3.0"
        id("org.jetbrains.kotlin.plugin.allopen") version "2.3.0"
        id("org.jetbrains.kotlin.plugin.jpa") version "2.2.2	0"
        id("com.google.devtools.ksp") version "2.3.5"
        id("io.micronaut.application") version "4.6.1"
        id("io.micronaut.library") version "4.6.1"
        id("io.micronaut.aot") version "4.6.1"
        id("com.gradleup.shadow") version "8.3.9"
    }
}

rootProject.name = "secman"

include("shared", "cli", "backendng")

// Module paths
project(":shared").projectDir = file("src/shared")
project(":cli").projectDir = file("src/cli")
project(":backendng").projectDir = file("src/backendng")
