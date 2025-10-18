plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.allopen")
    id("com.google.devtools.ksp")
    id("io.micronaut.library")
}

version = "0.1.0"
group = "com.secman.crowdstrike"

dependencies {
    // Micronaut Core (HTTP client, no web server)
    implementation("io.micronaut:micronaut-http-client")
    implementation("io.micronaut:micronaut-jackson-databind")
    implementation("io.micronaut.serde:micronaut-serde-jackson")
    implementation("io.micronaut.kotlin:micronaut-kotlin-runtime")
    implementation("io.micronaut.kotlin:micronaut-kotlin-extension-functions")
    implementation("io.micronaut:micronaut-retry")
    
    // Kotlin
    implementation("org.jetbrains.kotlin:kotlin-reflect:\${kotlinVersion}")
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8:\${kotlinVersion}")
    
    // Jackson for JSON
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    
    // Validation
    implementation("io.micronaut.validation:micronaut-validation")
    implementation("jakarta.validation:jakarta.validation-api")
    
    // Logging
    runtimeOnly("ch.qos.logback:logback-classic")
    
    // KSP
    ksp("io.micronaut:micronaut-http-validation")
    ksp("io.micronaut.validation:micronaut-validation-processor")
    ksp("io.micronaut.serde:micronaut-serde-processor")
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
        kotlinOptions {
            jvmTarget = "21"
        }
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
}

micronaut {
    processing {
        incremental(true)
        annotations("com.secman.crowdstrike.*")
    }
}
