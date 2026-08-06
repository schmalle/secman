plugins {
    id("org.jetbrains.kotlin.jvm") version "2.4.10" apply false
    id("org.jetbrains.kotlin.plugin.allopen") version "2.4.10" apply false
    id("org.jetbrains.kotlin.plugin.jpa") version "2.4.10" apply false
    // Pinned to 2.3.9. 2.3.10 crashed with "Unexpected missing parent declaration for
    // KSNode '@NotNull'" on WebAuthnService.kt (webauthn4j Java annotations). 2.3.11 fixes
    // that crash but its analysis worker runs inside the Gradle daemon and OOMs at the
    // -Xmx4g set in gradle.properties; it needs 6g. Verified 2026-08-06 — revisit the pin
    // together with the daemon heap budget.
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
    set("micronautVersion", "5.1.0")
    set("jvmTarget", "25")
    set("picocliVersion", "4.7.7")
}
