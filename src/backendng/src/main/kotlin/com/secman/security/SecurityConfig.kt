package com.secman.security

import io.micronaut.context.annotation.Factory

@Factory
class SecurityConfig {
    // JWT configuration is now handled entirely through application.yml
    // This removes potential bean creation conflicts
}