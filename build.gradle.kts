plugins {
    id("org.jetbrains.kotlin.jvm") version "2.3.20" apply false
    id("org.jetbrains.kotlin.plugin.allopen") version "2.3.20" apply false
    id("org.jetbrains.kotlin.plugin.jpa") version "2.3.20" apply false
    id("com.google.devtools.ksp") version "2.3.6" apply false
    id("io.micronaut.application") version "4.6.2" apply false
    id("io.micronaut.library") version "4.6.2" apply false
    id("io.micronaut.aot") version "4.6.2" apply false
    id("com.gradleup.shadow") version "9.3.2" apply false
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
    set("kotlinVersion", "2.3.20")
    set("micronautVersion", "4.10.10")
    set("jvmTarget", "21")
    set("picocliVersion", "4.7.7")
}
