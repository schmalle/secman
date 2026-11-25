import com.github.jengelman.gradle.plugins.shadow.transformers.PropertiesFileTransformer

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
    implementation("io.micronaut.serde:micronaut-serde-jackson")

    // Picocli for CLI
    implementation("info.picocli:picocli:4.7.7")
    implementation("io.micronaut.picocli:micronaut-picocli")
    
    // Jackson for YAML/JSON
    implementation("com.fasterxml.jackson.core:jackson-databind")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.23.0")
    
    // CSV export
    implementation("org.apache.commons:commons-csv:1.14.1")

    // AWS SDK for S3 (Feature 065 - S3 User Mapping Import)
    implementation(platform("software.amazon.awssdk:bom:2.59.9"))
    implementation("software.amazon.awssdk:s3")
    
    // Kotlin
    implementation("org.jetbrains.kotlin:kotlin-reflect:2.4.21")
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8:2.4.21")
    
    // Logging
    runtimeOnly("ch.qos.logback:logback-classic:1.6.8")
    runtimeOnly("org.yaml:snakeyaml:2.7")
    
    // KSP
    ksp("io.micronaut:micronaut-http-validation")

    // Test dependencies - Feature 056
    testImplementation("org.junit.jupiter:junit-jupiter-api:6.1.3")
    testImplementation("org.junit.jupiter:junit-jupiter-engine:6.1.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:6.1.3")
    testImplementation("io.mockk:mockk:1.14.12")
    testImplementation("org.assertj:assertj-core:3.27.7")
}

application {
    mainClass.set("com.secman.cli.SecmanCliKt")
}

java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

kotlin {
    jvmToolchain(25)
}

tasks {
    // Enable JUnit 5 platform for tests - Feature 056
    test {
        useJUnitPlatform()
        // MockK appends to the bootstrap classpath. Disable test JVM class sharing
        // and have Byte Buddy use its supported class-injection path.
        jvmArgs("-Xshare:off")
        systemProperty("net.bytebuddy.safe", "true")
    }

    // Fix for zip64 issue - archive has more than 65535 entries
    shadowJar {
        isZip64 = true
        failOnDuplicateEntries = true
        // These compile-time descriptors share a path across dependencies.
        // The executable uses Micronaut's generated metadata and JVM service descriptors.
        exclude("META-INF/spring-configuration-metadata.json", "META-INF/jackson-module-kotlin.kotlin_module")

        // Preserve all provider registrations, license text, and per-module Netty versions.
        val combinedText = listOf(
            "LICENSE", "LICENSE.txt", "NOTICE", "NOTICE.txt", "NOTICE.md", "DEPENDENCIES",
            "FastDoubleParser-LICENSE", "FastDoubleParser-ThirdParty-LICENSE", "Schubfach-LICENSE",
            "config-properties.adoc"
        ).map { "META-INF/$it" }
        filesMatching(combinedText + listOf("META-INF/services/**", "META-INF/io.netty.versions.properties")) {
            duplicatesStrategy = DuplicatesStrategy.INCLUDE
        }
        mergeServiceFiles()
        combinedText.forEach { append(it) }
        transform<PropertiesFileTransformer> {
            paths.add("META-INF/io.netty.versions.properties")
        }
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
