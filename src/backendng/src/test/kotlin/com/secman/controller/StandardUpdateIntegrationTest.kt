package com.secman.controller

import com.secman.domain.Standard
import com.secman.domain.UseCase
import com.secman.repository.StandardRepository
import com.secman.repository.UseCaseRepository
import com.secman.repository.UserRepository
import com.secman.testutil.BaseIntegrationTest
import com.secman.testutil.TestAuthHelper
import com.secman.testutil.TestDataFactory
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpStatus
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * `PUT /api/standards/{id}` actually persists.
 *
 * Regression: `updateStandard` merged the entity and then immediately called
 * `entityManager.refresh(...)` — added only to force lazy loading of `useCases` for the response.
 * `update` merges without flushing, so the refresh re-read the row and overwrote every pending
 * change. The endpoint returned **200 with the old values**, which is indistinguishable from
 * success, and no field could be edited at all.
 *
 * Each field is asserted by RE-READING through the API rather than trusting the PUT response —
 * the response body was exactly what made the bug invisible.
 */
@MicronautTest(environments = ["test"], transactional = false)
class StandardUpdateIntegrationTest : BaseIntegrationTest() {

    @Inject
    @field:Client("/")
    lateinit var client: HttpClient

    @Inject
    lateinit var userRepository: UserRepository

    @Inject
    lateinit var standardRepository: StandardRepository

    @Inject
    lateinit var useCaseRepository: UseCaseRepository

    private lateinit var adminUser: String
    private lateinit var standard: Standard
    private lateinit var useCase: UseCase

    @BeforeEach
    fun setup() {
        val suffix = System.nanoTime()
        adminUser = "std-admin-$suffix"
        userRepository.save(TestDataFactory.createAdminUser(adminUser, "$adminUser@secman.test"))
        useCase = useCaseRepository.save(UseCase(name = "Use case $suffix"))
        standard = standardRepository.save(
            Standard(name = "Standard $suffix", description = "BEFORE", useCases = mutableSetOf())
        )
    }

    private fun authHeader() = "Bearer ${TestAuthHelper.getAuthToken(client, adminUser)}"

    private fun put(body: Map<String, Any?>) = client.toBlocking().exchange(
        HttpRequest.PUT("/api/standards/${standard.id}", body).header("Authorization", authHeader()),
        Map::class.java
    )

    /** Re-read through the API; the PUT response is exactly what hid the bug. */
    private fun reread(): Map<*, *> = client.toBlocking().exchange(
        HttpRequest.GET<Any>("/api/standards/${standard.id}").header("Authorization", authHeader()),
        Map::class.java
    ).body()!!

    @Test
    fun `a description update survives the request`() {
        val response = put(mapOf("description" to "AFTER"))

        assertThat(response.status).isEqualTo(HttpStatus.OK)
        assertThat(reread()["description"])
            .describedAs("PUT returned 200 while persisting nothing")
            .isEqualTo("AFTER")
    }

    @Test
    fun `a name update survives the request`() {
        val newName = "Renamed ${System.nanoTime()}"

        put(mapOf("name" to newName))

        assertThat(reread()["name"]).isEqualTo(newName)
    }

    @Test
    fun `a use case association survives the request`() {
        put(mapOf("useCaseIds" to listOf(useCase.id)))

        @Suppress("UNCHECKED_CAST")
        val useCases = reread()["useCases"] as List<Map<*, *>>
        // Compared as Long: JSON numbers deserialize into Integer here, so identity on the boxed
        // type would fail for a reason that has nothing to do with the association.
        assertThat(useCases.map { (it["id"] as Number).toLong() }).containsExactly(useCase.id)
    }

    @Test
    fun `the all-requirements flag can be turned on and back off`() {
        // Turning it off is the direction that matters: a flag that cannot be cleared traps a
        // standard at full coverage, which is the opposite of what an admin unticking it expects.
        put(mapOf("allRequirements" to true))
        assertThat(reread()["allRequirements"]).isEqualTo(true)

        put(mapOf("allRequirements" to false))
        assertThat(reread()["allRequirements"])
            .describedAs("clearing the flag must return the standard to its use-case subset")
            .isEqualTo(false)
    }

    @Test
    fun `omitting a field leaves it alone`() {
        // PUT here patches rather than replaces; a partial body must not blank the rest.
        put(mapOf("allRequirements" to true))

        put(mapOf("description" to "AFTER"))

        val after = reread()
        assertThat(after["description"]).isEqualTo("AFTER")
        assertThat(after["allRequirements"]).isEqualTo(true)
        assertThat(after["name"]).isEqualTo(standard.name)
    }
}
