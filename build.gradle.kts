plugins {
    id("org.jetbrains.kotlin.jvm") version "2.4.10" apply false
    id("org.jetbrains.kotlin.plugin.allopen") version "2.4.10" apply false
    id("org.jetbrains.kotlin.plugin.jpa") version "2.4.10" apply false
    // Pinned to 2.3.9: KSP 2.3.10 crashes with "Unexpected missing parent declaration for
    // KSNode '@NotNull'" on WebAuthnService.kt (webauthn4j Java annotations).
    id("com.google.devtools.ksp") version "2.3.9" apply false
    id("io.micronaut.application") version "5.0.2" apply false
    id("io.micronaut.library") version "5.0.2" apply false
    id("io.micronaut.aot") version "5.0.2" apply false
    id("com.gradleup.shadow") version "9.6.1" apply false
}

subprojects {
    group = "com.secman"
    version = "0.1.0"

    repositories {
        mavenCentral()
    }
}

// Common dependency versions
ext {
    set("kotlinVersion", "2.4.10")
    set("micronautVersion", "5.0.6")
    set("jvmTarget", "25")
    set("picocliVersion", "4.7.7")
}
