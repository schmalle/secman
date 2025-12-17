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
    implementation("io.micronaut:micronaut-http-client:4.10.10")
    implementation("io.micronaut:micronaut-http-server-netty:4.10.10")
    implementation("io.micronaut:micronaut-jackson-databind:4.10.10")
    implementation("io.micronaut.kotlin:micronaut-kotlin-runtime")
    implementation("io.micronaut.kotlin:micronaut-kotlin-extension-functions")
    implementation("io.micronaut:micronaut-retry:4.10.10")
    implementation("io.micronaut.cache:micronaut-cache-caffeine")
    
    // Database
    implementation("io.micronaut.data:micronaut-data-hibernate-jpa:4.14.2")
    implementation("io.micronaut.sql:micronaut-hibernate-jpa:7.0.0")
    implementation("io.micronaut.sql:micronaut-jdbc-hikari:7.0.0")
    runtimeOnly("org.mariadb.jdbc:mariadb-java-client:3.5.6")

	implementation("io.micronaut.flyway:micronaut-flyway:7.9.2")
	runtimeOnly("org.flywaydb:flyway-core:11.19.0")
	
    // Security
    implementation("io.micronaut.security:micronaut-security-jwt:4.16.0")
    implementation("io.micronaut.security:micronaut-security-oauth2:4.16.0")

    // WebAuthn/Passkey support
    implementation("com.webauthn4j:webauthn4j-core:0.30.1.RELEASE")
    implementation("com.webauthn4j:webauthn4j-metadata:0.30.1.RELEASE")

    // Validation
    implementation("io.micronaut.validation:micronaut-validation")
    implementation("jakarta.validation:jakarta.validation-api")

    // XSS Prevention - Feature 047
    implementation("com.googlecode.owasp-java-html-sanitizer:owasp-java-html-sanitizer:20240325.1")

    // Email
    implementation("io.micronaut.email:micronaut-email-javamail:2.11.0")
    implementation("org.eclipse.angus:angus-mail:2.0.5")

    // Amazon SES
    implementation("software.amazon.awssdk:ses:2.40.9")
    implementation("software.amazon.awssdk:auth:2.40.9")

    // Email templates (Thymeleaf) - Feature 035
    implementation("io.micronaut.views:micronaut-views-thymeleaf")
    implementation("org.thymeleaf:thymeleaf:3.1.3.RELEASE")
    
    // Serialization
    implementation("io.micronaut.serde:micronaut-serde-jackson:2.16.2")
    
    // Kotlin
    implementation("org.jetbrains.kotlin:kotlin-reflect:${kotlinVersion}")
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8:${kotlinVersion}")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor:1.10.2")

    // MCP (Model Context Protocol) Dependencies
    // Note: Using JSON-RPC and reactive streams for MCP implementation
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("io.micronaut.reactor:micronaut-reactor")
    implementation("io.micronaut.reactor:micronaut-reactor-http-client")
    
    // Logging
    runtimeOnly("ch.qos.logback:logback-classic:1.5.22")
    // Bridge Log4j to Logback (required for Apache POI)
    runtimeOnly("org.apache.logging.log4j:log4j-to-slf4j:2.25.2")
    // Logstash encoder for JSON logging (Feature 046)
    implementation("net.logstash.logback:logstash-logback-encoder:9.0")
    // Conditional logging support (Janino) - for SECMAN_LOGGING env var
    implementation("org.codehaus.janino:janino:3.1.12")
    
    // YAML configuration support
    runtimeOnly("org.yaml:snakeyaml:2.5")
    
    // Password encoding
    implementation("org.springframework.security:spring-security-crypto:7.0.2")
    implementation("commons-logging:commons-logging:1.3.5")
    
    // Document generation (Apache POI)
    implementation("org.apache.poi:poi-ooxml:5.5.1")
    implementation("org.apache.poi:poi-scratchpad:5.5.1")

    // CSV parsing (Apache Commons CSV) - Feature 016
    implementation("org.apache.commons:commons-csv:1.14.1")

    // IP address parsing (Apache Commons Net) - Feature 020
    implementation("commons-net:commons-net:3.12.0")

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


// Configure Kotlin compiler options
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    compilerOptions {
        // Compiler options for Kotlin 2.1.0
        // The -Xannotation-default-target flag is no longer needed in Kotlin 2.1.0
    }
}

