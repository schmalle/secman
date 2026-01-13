plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.allopen")
    id("org.jetbrains.kotlin.plugin.jpa")
    id("com.google.devtools.ksp")
    id("io.micronaut.application")
    id("com.gradleup.shadow")
}

version = "0.1.0"
group = "com.secman.cli"

dependencies {
    // Shared CrowdStrike Module
    implementation(project(":shared"))

    // Backend Module (for NotificationService, repositories)
    implementation(project(":backendng"))

    // Micronaut Core
    implementation("io.micronaut:micronaut-runtime")
    implementation("io.micronaut.kotlin:micronaut-kotlin-runtime")
    implementation("io.micronaut:micronaut-http-client")
    implementation("io.micronaut:micronaut-context")

    // Micronaut Data (required for Pageable and repository interfaces)
    implementation("io.micronaut.data:micronaut-data-hibernate-jpa")

    // Database (required for EntityManager and JPA support)
    implementation("io.micronaut.sql:micronaut-hibernate-jpa")
    implementation("io.micronaut.sql:micronaut-jdbc-hikari")
    runtimeOnly("org.mariadb.jdbc:mariadb-java-client:3.5.3")

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
    implementation("org.jetbrains.kotlin:kotlin-reflect:2.3.0")
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8:2.3.0")
    
    // Logging
    runtimeOnly("ch.qos.logback:logback-classic")
    runtimeOnly("org.yaml:snakeyaml")
    
    // KSP
    ksp("io.micronaut:micronaut-http-validation")

    // Test dependencies - Feature 056
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.11.3")
    testImplementation("org.junit.jupiter:junit-jupiter-engine:5.11.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.3")
    testImplementation("io.mockk:mockk:1.13.13")
    testImplementation("org.assertj:assertj-core:3.26.3")
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
    compileKotlin {
        // jvmTarget is automatically set by jvmToolchain(21) above
        // No need for kotlinOptions block
    }

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
    annotation("jakarta.transaction.Transactional")
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
