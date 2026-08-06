package com.secman.controller

import io.micronaut.security.annotation.Secured
import io.micronaut.security.rules.SecurityRule
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Authorization contract for [TranslationConfigController].
 *
 * `@Secured` is enforced declaratively by Micronaut, so this pins the annotations themselves —
 * the test fails if anyone removes or weakens one, which is exactly the regression that mattered:
 * the controller previously carried only a class-level `IS_AUTHENTICATED` and no method-level role
 * check, so any authenticated user could rewrite `baseUrl` on the active LLM config. Since
 * TranslationService sends the stored key as `Authorization: Bearer` to whatever host that names,
 * that leaked both the API key and the requirement text being translated, and `/test` doubled as an
 * outbound-fetch primitive.
 *
 * Reads deliberately stay `IS_AUTHENTICATED`: non-admin pages (Export.tsx, ImportExport.tsx) call
 * `GET /active` to decide whether to offer translated export, and every read masks the credential
 * through `TranslationConfig.toSafeResponse()`.
 */
class TranslationConfigControllerAuthorizationTest {

    private val controller = TranslationConfigController::class.java

    private val mutatingMethods = listOf(
        "createConfig",
        "updateConfig",
        "deleteConfig",
        "testConfig",
        "activateConfig",
        "deactivateConfig"
    )

    private val readMethods = listOf(
        "getAllConfigs",
        "getActiveConfig",
        "getConfig",
        "getAvailableModels",
        "getSupportedLanguages"
    )

    @Test
    fun `class-level security is authenticated`() {
        val secured = controller.getAnnotation(Secured::class.java)
        assertNotNull(secured, "TranslationConfigController must carry a class-level @Secured")
        assertEquals(
            listOf(SecurityRule.IS_AUTHENTICATED),
            secured.value.toList(),
            "Class-level access must stay IS_AUTHENTICATED so non-admin reads keep working"
        )
    }

    @Test
    fun `every mutating endpoint is ADMIN-only`() {
        mutatingMethods.forEach { name ->
            val method = controller.methods.firstOrNull { it.name == name }
            assertNotNull(method, "Expected method $name on TranslationConfigController")

            val secured = method!!.getAnnotation(Secured::class.java)
            assertNotNull(
                secured,
                "$name mutates LLM configuration and must carry a method-level @Secured(\"ADMIN\")"
            )
            assertEquals(
                listOf("ADMIN"),
                secured!!.value.toList(),
                "$name must be restricted to ADMIN"
            )
        }
    }

    @Test
    fun `read endpoints stay accessible to any authenticated user`() {
        readMethods.forEach { name ->
            val method = controller.methods.firstOrNull { it.name == name }
            assertNotNull(method, "Expected method $name on TranslationConfigController")

            assertNull(
                method!!.getAnnotation(Secured::class.java),
                "$name must inherit the class-level IS_AUTHENTICATED — the Export page depends on it"
            )
        }
    }
}
