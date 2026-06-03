plugins {
    id("org.jetbrains.kotlin.jvm") version "2.4.0" apply false
    id("org.jetbrains.kotlin.plugin.allopen") version "2.4.0" apply false
    id("org.jetbrains.kotlin.plugin.jpa") version "2.4.0" apply false
    id("com.google.devtools.ksp") version "2.3.9" apply false
    id("io.micronaut.application") version "5.0.0" apply false
    id("io.micronaut.library") version "5.0.0" apply false
    id("io.micronaut.aot") version "5.0.0" apply false
    id("com.gradleup.shadow") version "9.4.2" apply false
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
    set("kotlinVersion", "2.4.0")
    set("micronautVersion", "5.0.2")
    set("jvmTarget", "25")
    set("picocliVersion", "4.7.7")
}
