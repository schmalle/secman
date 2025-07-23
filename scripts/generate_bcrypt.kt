#!/usr/bin/env kotlin

@file:DependsOn("org.springframework.security:spring-security-crypto:6.1.5")

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder

fun main() {
    val encoder = BCryptPasswordEncoder()
    val password = "admin"
    val hashedPassword = encoder.encode(password)
    println(hashedPassword)
}