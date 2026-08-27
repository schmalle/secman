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
    implementation("io.micronaut.cache:micronaut-cache-caffeine")

    // Database
    implementation("io.micronaut.data:micronaut-data-hibernate-jpa:5.1.2")
    implementation("io.micronaut.sql:micronaut-hibernate-jpa:7.1.0")
    implementation("io.micronaut.sql:micronaut-jdbc-hikari:7.1.0")
    runtimeOnly("org.mariadb.jdbc:mariadb-java-client:3.5.10")

	implementation("io.micronaut.flyway:micronaut-flyway:8.1.1")
	runtimeOnly("org.flywaydb:flyway-core:13.3.0")
	runtimeOnly("org.flywaydb:flyway-mysql:13.3.0")

    // Security
    implementation("io.micronaut.security:micronaut-security-jwt:5.3.2")
    implementation("io.micronaut.security:micronaut-security-oauth2:5.3.2")

    // WebAuthn/Passkey support
    implementation("com.webauthn4j:webauthn4j-core:0.31.9.RELEASE")
    implementation("com.webauthn4j:webauthn4j-metadata:0.31.9.RELEASE")

    // Validation
    implementation("io.micronaut.validation:micronaut-validation")
    implementation("jakarta.validation:jakarta.validation-api")

    // XSS Prevention - Feature 047
    implementation("com.googlecode.owasp-java-html-sanitizer:owasp-java-html-sanitizer:20260313.1")

    // Email
    implementation("io.micronaut.email:micronaut-email-javamail:3.1.0")
    implementation("org.eclipse.angus:angus-mail:2.0.5")

    // Email templates (Thymeleaf) - Feature 035
    implementation("io.micronaut.views:micronaut-views-thymeleaf")
    implementation("org.thymeleaf:thymeleaf:3.1.5.RELEASE")

    // Serialization
    implementation("io.micronaut.serde:micronaut-serde-jackson:3.1.1")
    
    // Kotlin
    implementation("org.jetbrains.kotlin:kotlin-reflect:${kotlinVersion}")
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8:${kotlinVersion}")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor:1.11.0")

    // MCP (Model Context Protocol) Dependencies
    // Note: Using JSON-RPC and reactive streams for MCP implementation
    // Explicit version: the Micronaut platform BOM manages the Jackson 3 coordinate
    // (tools.jackson.module), not this Jackson 2 one. It previously resolved only via a
    // transitive jackson-bom that micronaut-micrometer-bom 6.0.1 dropped.
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.22.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("io.micronaut.reactor:micronaut-reactor")
    implementation("io.micronaut.reactor:micronaut-reactor-http-client")
    
    // Logging
    runtimeOnly("ch.qos.logback:logback-classic:1.6.3")
    // Bridge Log4j to Logback (required for Apache POI)
    runtimeOnly("org.apache.logging.log4j:log4j-to-slf4j:2.26.1")
    // Logstash encoder for JSON logging (Feature 046)
    implementation("net.logstash.logback:logstash-logback-encoder:9.0")
    // Conditional logging support (Janino) - for SECMAN_LOGGING env var
    implementation("org.codehaus.janino:janino:3.1.12")
    
    // YAML configuration support
    runtimeOnly("org.yaml:snakeyaml:2.6")
    
    // Password encoding
    implementation("org.springframework.security:spring-security-crypto:7.1.0")
    implementation("org.springframework:spring-core:7.0.8")
    implementation("commons-logging:commons-logging:1.4.0")
    
    // Document generation (Apache POI)
    implementation("org.apache.poi:poi-ooxml:5.5.1")
    implementation("org.apache.poi:poi-scratchpad:5.5.1")

    // CSV parsing (Apache Commons CSV) - Feature 016
    implementation("org.apache.commons:commons-csv:1.14.1")

    // IP address parsing (Apache Commons Net) - Feature 020
    implementation("commons-net:commons-net:3.13.0")

    // HTML processing for email
    implementation("org.jsoup:jsoup:1.23.1")
    
    // KSP
    ksp("io.micronaut:micronaut-http-validation")
    ksp("io.micronaut.data:micronaut-data-processor")
    ksp("io.micronaut.serde:micronaut-serde-processor")

    // Test dependencies - Feature 056
    kspTest("io.micronaut:micronaut-inject-java")
    testImplementation("org.junit.jupiter:junit-jupiter-api:6.1.2")
    testImplementation("org.junit.jupiter:junit-jupiter-engine:6.1.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:6.1.2")
    testImplementation("io.micronaut.test:micronaut-test-junit5:5.1.1")
    testImplementation("io.mockk:mockk:1.14.11")
    // Tests assert on log output via Logback's ListAppender; main code stays
    // slf4j-only (logback-classic is runtimeOnly above).
    testImplementation("ch.qos.logback:logback-classic:1.6.3")
    testImplementation("org.assertj:assertj-core:3.27.7")
}

application {
    mainClass.set("com.secman.ApplicationKt")
    // Bound the dev heap to production's ballpark. `gradle run` forks a JVM with NO args
    // (org.gradle.jvmargs applies to the Gradle daemon, not the fork), so it inherited the
    // ergonomic default of ~1/4 of physical RAM — a multi-GB heap on a dev machine. That is
    // why the 2026-07-30 import OOM was invisible locally while killing a 1 GB container:
    // unbounded-query regressions simply cannot reproduce on an unbounded heap.
    //
    // Override for a one-off (e.g. profiling a large import) with:
    //   ./gradlew :backendng:run -PsecmanDevHeap=4g
    applicationDefaultJvmArgs = listOf(
        "-Xmx${providers.gradleProperty("secmanDevHeap").getOrElse("1g")}",
        "-Xms256m",
        "-XX:+HeapDumpOnOutOfMemoryError",
        "-XX:HeapDumpPath=build/secman-backend-oom.hprof",
        "-XX:+ExitOnOutOfMemoryError",
        // ProfilePictureService uses ImageIO/Graphics2D. Raster work needs no display, but pin
        // headless so a stray DISPLAY can never push the JVM onto a windowing toolkit.
        "-Djava.awt.headless=true"
    )
}

java {
    sourceCompatibility = JavaVersion.toVersion("25")
    targetCompatibility = JavaVersion.toVersion("25")
}

kotlin {
    jvmToolchain(25)
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
        freeCompilerArgs.add("-Xannotation-default-target=param-property")
    }
}

// Configure test task to use JUnit 5 platform - Feature 056
tasks.test {
    useJUnitPlatform()
    if (System.getenv("MICRONAUT_ENVIRONMENTS").isNullOrBlank()) {
        environment("MICRONAUT_ENVIRONMENTS", "test")
    }
}

tasks.withType<Jar> {
    isZip64 = true
}

/**
 * Regenerates the example company Word template committed under
 * `src/main/resources/templates/`.
 *
 * The artefact is an opaque binary, so it is never hand-edited: change
 * `ExampleRequirementExportTemplateBuilder`, run this task, and commit the result. Run it from the
 * repository root with:
 *
 *     ./gradlew :backendng:generateExampleRequirementTemplate
 */
tasks.register<JavaExec>("generateExampleRequirementTemplate") {
    group = "build"
    description = "Regenerate the example company requirement-export Word template from its builder."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.secman.tools.GenerateExampleRequirementTemplateKt")
    args(
        layout.projectDirectory
            .file("src/main/resources/templates/secman-company-requirements-template.docx")
            .asFile.absolutePath
    )
}
