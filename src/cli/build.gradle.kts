plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.allopen")
    id("com.google.devtools.ksp")
    id("io.micronaut.application")
    id("com.gradleup.shadow")
}

version = "0.1.0"
group = "com.secman.cli"

dependencies {
    // Shared CrowdStrike Module
    implementation(project(":shared"))
    
    // Micronaut Core
    implementation("io.micronaut:micronaut-runtime")
    implementation("io.micronaut.kotlin:micronaut-kotlin-runtime")
    implementation("io.micronaut:micronaut-http-client")
    implementation("io.micronaut:micronaut-context")
    
    // Picocli for CLI
    implementation("info.picocli:picocli:4.7.5")
    implementation("io.micronaut.picocli:micronaut-picocli")
    
    // Jackson for YAML/JSON
    implementation("com.fasterxml.jackson.core:jackson-databind")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    
    // CSV export
    implementation("org.apache.commons:commons-csv:1.11.0")
    
    // Kotlin
    implementation("org.jetbrains.kotlin:kotlin-reflect:2.1.0")
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8:2.1.0")
    
    // Logging
    runtimeOnly("ch.qos.logback:logback-classic")
    runtimeOnly("org.yaml:snakeyaml")
    
    // KSP
    ksp("io.micronaut:micronaut-http-validation")
}

application {
    mainClass.set("com.secman.cli.SecmanCliKt")
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

kotlin {
    jvmToolchain(21)
}

tasks {
    compileKotlin {
        // jvmTarget is automatically set by jvmToolchain(21) above
        // No need for kotlinOptions block
    }
    
    // Disable all test tasks
    test {
        enabled = false
    }
    
    compileTestKotlin {
        enabled = false
    }
    
    processTestResources {
        enabled = false
    }
}

allOpen {
    annotation("io.micronaut.aop.Around")
    annotation("jakarta.inject.Singleton")
    annotation("picocli.CommandLine.Command")
}

graalvmNative.toolchainDetection.set(false)

micronaut {
    runtime("netty")
    processing {
        incremental(true)
        annotations("com.secman.cli.*")
    }
}
