plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.allopen")
    id("org.jetbrains.kotlin.plugin.jpa")
    id("com.google.devtools.ksp")
    id("com.gradleup.shadow")
    id("io.micronaut.application")
    id("io.micronaut.aot")
}

version = "0.1"
group = "com.secman"

val kotlinVersion = project.properties.get("kotlinVersion")
repositories {
    mavenCentral()
	google()
}

dependencies {
    // Shared CrowdStrike Module
    implementation(project(":shared"))
    
    // Micronaut Core
    implementation("io.micronaut:micronaut-http-client")
    implementation("io.micronaut:micronaut-http-server-netty")
    implementation("io.micronaut:micronaut-jackson-databind")
    implementation("io.micronaut.kotlin:micronaut-kotlin-runtime")
    implementation("io.micronaut.kotlin:micronaut-kotlin-extension-functions")
    implementation("io.micronaut:micronaut-retry")
    
    // Database
    implementation("io.micronaut.data:micronaut-data-hibernate-jpa")
    implementation("io.micronaut.sql:micronaut-hibernate-jpa")
    implementation("io.micronaut.sql:micronaut-jdbc-hikari")
    runtimeOnly("org.mariadb.jdbc:mariadb-java-client:3.5.3")
    
    // Security
    implementation("io.micronaut.security:micronaut-security-jwt")
    implementation("io.micronaut.security:micronaut-security-oauth2")

    // Redis (for rate limiting in Feature 009)
    implementation("io.micronaut.redis:micronaut-redis-lettuce")
    
    // Validation
    implementation("io.micronaut.validation:micronaut-validation")
    implementation("jakarta.validation:jakarta.validation-api")
    
    // Email
    implementation("io.micronaut.email:micronaut-email-javamail")
    implementation("org.eclipse.angus:angus-mail:2.0.5")

    // Email templates (Thymeleaf) - Feature 035
    implementation("io.micronaut.views:micronaut-views-thymeleaf")
    implementation("org.thymeleaf:thymeleaf:3.1.3.RELEASE")
    
    // Serialization
    implementation("io.micronaut.serde:micronaut-serde-jackson")
    
    // Kotlin
    implementation("org.jetbrains.kotlin:kotlin-reflect:${kotlinVersion}")
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8:${kotlinVersion}")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor")

    // MCP (Model Context Protocol) Dependencies
    // Note: Using JSON-RPC and reactive streams for MCP implementation
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.0")
    implementation("io.micronaut.reactor:micronaut-reactor")
    implementation("io.micronaut.reactor:micronaut-reactor-http-client")
    
    // Logging
    runtimeOnly("ch.qos.logback:logback-classic")
    // Bridge Log4j to Logback (required for Apache POI)
    runtimeOnly("org.apache.logging.log4j:log4j-to-slf4j:2.25.2")
    
    // YAML configuration support
    runtimeOnly("org.yaml:snakeyaml")
    
    // Password encoding
    implementation("org.springframework.security:spring-security-crypto:6.5.5")
    implementation("commons-logging:commons-logging:1.3.4")
    
    // Document generation (Apache POI)
    implementation("org.apache.poi:poi-ooxml:5.4.1")
    implementation("org.apache.poi:poi-scratchpad:5.4.1")

    // CSV parsing (Apache Commons CSV) - Feature 016
    implementation("org.apache.commons:commons-csv:1.11.0")

    // IP address parsing (Apache Commons Net) - Feature 020
    implementation("commons-net:commons-net:3.11.1")

    // HTML processing for email
    implementation("org.jsoup:jsoup:1.21.2")
    
    // KSP
    ksp("io.micronaut:micronaut-http-validation")
    ksp("io.micronaut.data:micronaut-data-processor")
    ksp("io.micronaut.serde:micronaut-serde-processor")
    ksp("io.micronaut.security:micronaut-security-annotations")
}

application {
    mainClass.set("com.secman.ApplicationKt")
}

java {
    sourceCompatibility = JavaVersion.toVersion("21")
    targetCompatibility = JavaVersion.toVersion("21")
}

kotlin {
    jvmToolchain(21)
}

allOpen {
    annotation("io.micronaut.aop.Around")
    annotation("jakarta.inject.Singleton")
    annotation("jakarta.transaction.Transactional")
}

graalvmNative.toolchainDetection.set(false)
micronaut {
    runtime("netty")
    processing {
        incremental(true)
        annotations("com.secman.*")
    }
    aot {
        optimizeServiceLoading.set(false)
        convertYamlToJava.set(false)
        precomputeOperations.set(true)
        cacheEnvironment.set(true)
        optimizeClassLoading.set(true)
        deduceEnvironment.set(true)
        optimizeNetty.set(true)
    }
}

tasks.named<io.micronaut.gradle.docker.NativeImageDockerfile>("dockerfileNative") {
    jdkVersion.set("21")
}

// Configure Kotlin compiler options
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    compilerOptions {
        // Compiler options for Kotlin 2.1.0
        // The -Xannotation-default-target flag is no longer needed in Kotlin 2.1.0
    }
}

// Disable all test tasks
tasks {
    test {
        enabled = false
    }

    named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileTestKotlin") {
        enabled = false
    }

    processTestResources {
        enabled = false
    }
}