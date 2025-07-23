plugins {
    id("org.jetbrains.kotlin.jvm") version "2.0.21"
    id("org.jetbrains.kotlin.plugin.allopen") version "2.0.21"
    id("org.jetbrains.kotlin.plugin.jpa") version "2.0.21"
    id("com.google.devtools.ksp") version "2.0.21-1.0.28"
    id("com.github.johnrengelman.shadow") version "8.1.1"
    id("io.micronaut.application") version "4.4.3"
    // Disabled test resources to avoid Docker dependency
    // id("io.micronaut.test-resources") version "4.4.3"
    id("io.micronaut.aot") version "4.4.3"
}

version = "0.1"
group = "com.secman"

val kotlinVersion = project.properties.get("kotlinVersion")
repositories {
    mavenCentral()
}

dependencies {
    // Micronaut Core
    implementation("io.micronaut:micronaut-http-client")
    implementation("io.micronaut:micronaut-http-server-netty")
    implementation("io.micronaut:micronaut-jackson-databind")
    implementation("io.micronaut.kotlin:micronaut-kotlin-runtime")
    implementation("io.micronaut.kotlin:micronaut-kotlin-extension-functions")
    
    // Database
    implementation("io.micronaut.data:micronaut-data-hibernate-jpa")
    implementation("io.micronaut.sql:micronaut-hibernate-jpa")
    implementation("io.micronaut.sql:micronaut-jdbc-hikari")
    runtimeOnly("org.mariadb.jdbc:mariadb-java-client:3.5.3")
    
    // Security
    implementation("io.micronaut.security:micronaut-security-jwt")
    implementation("io.micronaut.security:micronaut-security-oauth2")
    
    // Validation
    implementation("io.micronaut.validation:micronaut-validation")
    implementation("jakarta.validation:jakarta.validation-api")
    
    // Email
    implementation("io.micronaut.email:micronaut-email-javamail")
    
    // Serialization
    implementation("io.micronaut.serde:micronaut-serde-jackson")
    
    // Kotlin
    implementation("org.jetbrains.kotlin:kotlin-reflect:${kotlinVersion}")
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8:${kotlinVersion}")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor")
    
    // Logging
    runtimeOnly("ch.qos.logback:logback-classic")
    
    // YAML configuration support
    runtimeOnly("org.yaml:snakeyaml")
    
    // Password encoding
    implementation("org.springframework.security:spring-security-crypto:6.3.5")
    implementation("commons-logging:commons-logging:1.3.4")
    
    // Document generation (Apache POI)
    implementation("org.apache.poi:poi-ooxml:5.3.0")
    implementation("org.apache.poi:poi-scratchpad:5.3.0")
    
    // HTML processing for email
    implementation("org.jsoup:jsoup:1.18.3")
    
    // Testing
    testImplementation("io.micronaut:micronaut-http-client")
    testImplementation("io.micronaut.test:micronaut-test-junit5")
    testImplementation("org.junit.jupiter:junit-jupiter-api")
    testImplementation("org.junit.jupiter:junit-jupiter-engine")
    testImplementation("org.mockito:mockito-core")
    testImplementation("io.mockk:mockk:1.13.14")
    testImplementation("com.h2database:h2:2.2.224")
    
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
    sourceCompatibility = JavaVersion.toVersion("17")
    targetCompatibility = JavaVersion.toVersion("17")
}

kotlin {
    jvmToolchain(17)
}

allOpen {
    annotation("io.micronaut.aop.Around")
}

graalvmNative.toolchainDetection.set(false)
micronaut {
    runtime("netty")
    testRuntime("junit5")
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
    jdkVersion.set("17")
}