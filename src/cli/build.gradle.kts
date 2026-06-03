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
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.22.0")
    
    // CSV export
    implementation("org.apache.commons:commons-csv:1.14.1")

    // AWS SDK for S3 (Feature 065 - S3 User Mapping Import)
    implementation(platform("software.amazon.awssdk:bom:2.46.2"))
    implementation("software.amazon.awssdk:s3")
    
    // Kotlin
    implementation("org.jetbrains.kotlin:kotlin-reflect:2.4.0")
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8:2.4.0")
    
    // Logging
    runtimeOnly("ch.qos.logback:logback-classic:1.5.34")
    runtimeOnly("org.yaml:snakeyaml:2.6")
    
    // KSP
    ksp("io.micronaut:micronaut-http-validation")

    // Test dependencies - Feature 056
    testImplementation("org.junit.jupiter:junit-jupiter-api:6.1.0")
    testImplementation("org.junit.jupiter:junit-jupiter-engine:6.1.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:6.1.0")
    testImplementation("io.mockk:mockk:1.14.11")
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
    }

    // Fix for zip64 issue - archive has more than 65535 entries
    shadowJar {
        isZip64 = true
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
