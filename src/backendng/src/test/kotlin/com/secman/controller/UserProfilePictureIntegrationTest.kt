package com.secman.controller

import com.secman.repository.UserProfilePictureRepository
import com.secman.repository.UserRepository
import com.secman.testutil.BaseIntegrationTest
import com.secman.testutil.TestAuthHelper
import com.secman.testutil.TestDataFactory
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpStatus
import io.micronaut.http.MediaType
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.http.client.exceptions.HttpClientResponseException
import io.micronaut.http.client.multipart.MultipartBody
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/**
 * Integration tests for the profile picture endpoints.
 *
 * Feature: Profile Picture Management
 *
 * Covers the happy path, replacement, every rejection branch, the idempotent delete, and — most
 * importantly — that one user can never reach another user's picture.
 */
class UserProfilePictureIntegrationTest : BaseIntegrationTest() {

    @Inject
    @field:Client("/")
    lateinit var client: HttpClient

    @Inject
    lateinit var userRepository: UserRepository

    @Inject
    lateinit var profilePictureRepository: UserProfilePictureRepository

    private lateinit var userA: String
    private lateinit var userB: String
    private var userAId: Long = 0

    @BeforeEach
    fun setup() {
        val nonce = System.nanoTime()
        userA = "pic-a-$nonce"
        userB = "pic-b-$nonce"

        userAId = userRepository.save(
            TestDataFactory.createRegularUser(userA, "$userA@secman.test")
        ).id!!
        userRepository.save(
            TestDataFactory.createRegularUser(userB, "$userB@secman.test")
        )
    }

    private fun authHeader(username: String) =
        "Bearer ${TestAuthHelper.getAuthToken(client, username)}"

    private fun pngBytes(width: Int = 400, height: Int = 400, fill: Color = Color.BLUE): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        try {
            g.color = fill
            g.fillRect(0, 0, width, height)
        } finally {
            g.dispose()
        }
        val out = ByteArrayOutputStream()
        ImageIO.write(image, "png", out)
        return out.toByteArray()
    }

    private fun upload(
        username: String,
        bytes: ByteArray,
        filename: String = "avatar.png",
        contentType: MediaType = MediaType.IMAGE_PNG_TYPE
    ) = client.toBlocking().exchange(
        HttpRequest.POST(
            "/api/users/profile/picture",
            MultipartBody.builder().addPart("file", filename, contentType, bytes).build()
        )
            .header("Authorization", authHeader(username))
            .contentType(MediaType.MULTIPART_FORM_DATA),
        Map::class.java
    )

    /**
     * Uploads a part WITHOUT a per-part content type.
     *
     * This shape is the one that matters: Micronaut does not surface a Content-Type on
     * CompletedFileUpload for the multipart parts real browsers and curl send, so the service
     * receives null. Every other test here declares the type explicitly via MultipartBody's
     * 4-arg overload, which is a wire shape no real client produces — that is exactly why this
     * suite stayed green while every upload in production was rejected.
     */
    private fun uploadWithoutDeclaredType(
        username: String,
        bytes: ByteArray,
        filename: String = "avatar.png"
    ) = client.toBlocking().exchange(
        HttpRequest.POST(
            "/api/users/profile/picture",
            MultipartBody.builder().addPart("file", filename, bytes).build()
        )
            .header("Authorization", authHeader(username))
            .contentType(MediaType.MULTIPART_FORM_DATA),
        Map::class.java
    )

    private fun fetchPicture(username: String) = client.toBlocking().exchange(
        HttpRequest.GET<Any>("/api/users/profile/picture")
            .header("Authorization", authHeader(username)),
        ByteArray::class.java
    )

    @Test
    fun `uploads and serves a profile picture`() {
        val uploadResponse = upload(userA, pngBytes())
        assertThat(uploadResponse.status).isEqualTo(HttpStatus.OK)
        assertThat(uploadResponse.body()!!["hasProfilePicture"]).isEqualTo(true)
        assertThat(uploadResponse.body()!!["width"]).isEqualTo(256)
        assertThat(uploadResponse.body()!!["height"]).isEqualTo(256)

        val getResponse = fetchPicture(userA)
        assertThat(getResponse.status).isEqualTo(HttpStatus.OK)
        assertThat(getResponse.contentType.get().toString()).startsWith("image/")
        assertThat(getResponse.header("Content-Disposition")).startsWith("inline")
        assertThat(getResponse.header("X-Content-Type-Options")).isEqualTo("nosniff")
        assertThat(getResponse.header("ETag")).isNotBlank()
        assertThat(getResponse.body()).isNotEmpty()

        // What is served must be a real, decodable image — proving re-encoding happened.
        val decoded = ImageIO.read(getResponse.body()!!.inputStream())
        assertThat(decoded.width).isEqualTo(256)
    }

    @Test
    fun `uploads a picture when the part carries no declared content type`() {
        // Regression: this is the shape a real browser and curl produce. Before the fix the
        // service treated an absent declaration as grounds for rejection, so no upload of any
        // format could ever succeed — while every other test in this class passed.
        val uploadResponse = uploadWithoutDeclaredType(userA, pngBytes())

        assertThat(uploadResponse.status).isEqualTo(HttpStatus.OK)
        assertThat(uploadResponse.body()!!["hasProfilePicture"]).isEqualTo(true)
        assertThat(uploadResponse.body()!!["width"]).isEqualTo(256)

        val decoded = ImageIO.read(fetchPicture(userA).body()!!.inputStream())
        assertThat(decoded.width).isEqualTo(256)
    }

    @Test
    fun `rejects a non-image even when the part carries no declared content type`() {
        // The fallback must sniff, not wave things through: with nothing declared, the content
        // is the only signal left, so it has to be the one that decides.
        val html = "<html><body><script>alert(1)</script></body></html>".toByteArray()

        val thrown = assertThrows<HttpClientResponseException> {
            uploadWithoutDeclaredType(userA, html)
        }

        assertThat(thrown.status).isEqualTo(HttpStatus.BAD_REQUEST)
    }

    @Test
    fun `replacing a picture updates the existing row rather than adding another`() {
        upload(userA, pngBytes(fill = Color.BLUE))
        val firstEtag = fetchPicture(userA).header("ETag")
        val firstRowId = profilePictureRepository.findByUserId(userAId).orElseThrow().id

        upload(userA, pngBytes(fill = Color.RED))
        val secondEtag = fetchPicture(userA).header("ETag")
        val secondRow = profilePictureRepository.findByUserId(userAId).orElseThrow()

        assertThat(secondEtag).isNotEqualTo(firstEtag)
        // Same row id => an update, not a second row. Asserted per user rather than via a global
        // count(), which other tests in this class would pollute (no DB reset between methods).
        assertThat(secondRow.id).isEqualTo(firstRowId)
    }

    @Test
    fun `returns 404 when the user has no picture`() {
        val ex = assertThrows<HttpClientResponseException> { fetchPicture(userA) }
        assertThat(ex.status).isEqualTo(HttpStatus.NOT_FOUND)
    }

    @Test
    fun `rejects an oversized upload`() {
        // A 3000x3000 noise-free PNG compresses well, so pad past the 2 MB cap explicitly.
        val oversized = pngBytes(64, 64) + ByteArray(3 * 1024 * 1024)
        val ex = assertThrows<HttpClientResponseException> { upload(userA, oversized) }
        assertThat(ex.status).isEqualTo(HttpStatus.BAD_REQUEST)
    }

    @Test
    fun `rejects a non-image content type`() {
        val ex = assertThrows<HttpClientResponseException> {
            upload(userA, "not an image".toByteArray(), "notes.txt", MediaType.TEXT_PLAIN_TYPE)
        }
        assertThat(ex.status).isEqualTo(HttpStatus.BAD_REQUEST)
    }

    @Test
    fun `rejects HTML disguised as a PNG`() {
        val ex = assertThrows<HttpClientResponseException> {
            upload(userA, "<script>alert(1)</script>".toByteArray())
        }
        assertThat(ex.status).isEqualTo(HttpStatus.BAD_REQUEST)
    }

    @Test
    fun `rejects an SVG upload`() {
        val svg = """<svg xmlns="http://www.w3.org/2000/svg" onload="alert(1)"></svg>"""
            .toByteArray()
        val ex = assertThrows<HttpClientResponseException> {
            upload(userA, svg, "avatar.svg", MediaType.of("image/svg+xml"))
        }
        assertThat(ex.status).isEqualTo(HttpStatus.BAD_REQUEST)
    }

    @Test
    fun `requires authentication on all three routes`() {
        val get = assertThrows<HttpClientResponseException> {
            client.toBlocking().exchange(
                HttpRequest.GET<Any>("/api/users/profile/picture"), ByteArray::class.java
            )
        }
        assertThat(get.status).isEqualTo(HttpStatus.UNAUTHORIZED)

        val post = assertThrows<HttpClientResponseException> {
            client.toBlocking().exchange(
                HttpRequest.POST(
                    "/api/users/profile/picture",
                    MultipartBody.builder()
                        .addPart("file", "avatar.png", MediaType.IMAGE_PNG_TYPE, pngBytes())
                        .build()
                ).contentType(MediaType.MULTIPART_FORM_DATA),
                Map::class.java
            )
        }
        assertThat(post.status).isEqualTo(HttpStatus.UNAUTHORIZED)

        val delete = assertThrows<HttpClientResponseException> {
            client.toBlocking().exchange(
                HttpRequest.DELETE<Any>("/api/users/profile/picture"), Any::class.java
            )
        }
        assertThat(delete.status).isEqualTo(HttpStatus.UNAUTHORIZED)
    }

    @Test
    fun `delete is idempotent and returns 204 whether or not a picture exists`() {
        val first = client.toBlocking().exchange(
            HttpRequest.DELETE<Any>("/api/users/profile/picture")
                .header("Authorization", authHeader(userA)),
            Any::class.java
        )
        assertThat(first.status).isEqualTo(HttpStatus.NO_CONTENT)

        upload(userA, pngBytes())
        assertThat(profilePictureRepository.existsByUserId(userAId)).isTrue()

        val second = client.toBlocking().exchange(
            HttpRequest.DELETE<Any>("/api/users/profile/picture")
                .header("Authorization", authHeader(userA)),
            Any::class.java
        )
        assertThat(second.status).isEqualTo(HttpStatus.NO_CONTENT)
        assertThat(profilePictureRepository.existsByUserId(userAId)).isFalse()

        val ex = assertThrows<HttpClientResponseException> { fetchPicture(userA) }
        assertThat(ex.status).isEqualTo(HttpStatus.NOT_FOUND)
    }

    @Test
    fun `each user only ever sees their own picture`() {
        upload(userA, pngBytes(fill = Color.BLUE))

        // B has no picture of their own and cannot reach A's — there is no identifier in the
        // route for B to vary, so isolation is structural rather than merely enforced.
        val notFound = assertThrows<HttpClientResponseException> { fetchPicture(userB) }
        assertThat(notFound.status).isEqualTo(HttpStatus.NOT_FOUND)

        upload(userB, pngBytes(fill = Color.GREEN))

        val aBytes = fetchPicture(userA).body()!!
        val bBytes = fetchPicture(userB).body()!!
        assertThat(aBytes).isNotEqualTo(bBytes)
    }

    @Test
    fun `profile endpoint reports picture state`() {
        val before = client.toBlocking().exchange(
            HttpRequest.GET<Any>("/api/users/profile").header("Authorization", authHeader(userA)),
            Map::class.java
        ).body()!!
        assertThat(before["hasProfilePicture"]).isEqualTo(false)
        assertThat(before["profilePictureUpdatedAt"]).isNull()

        upload(userA, pngBytes())

        val after = client.toBlocking().exchange(
            HttpRequest.GET<Any>("/api/users/profile").header("Authorization", authHeader(userA)),
            Map::class.java
        ).body()!!
        assertThat(after["hasProfilePicture"]).isEqualTo(true)
        assertThat(after["profilePictureUpdatedAt"]).isNotNull()
    }

    @Test
    fun `auth status reports picture state so the header can gate the avatar`() {
        upload(userA, pngBytes())

        val status = client.toBlocking().exchange(
            HttpRequest.GET<Any>("/api/auth/status").header("Authorization", authHeader(userA)),
            Map::class.java
        ).body()!!

        assertThat(status["hasProfilePicture"]).isEqualTo(true)
        assertThat(status["profilePictureUpdatedAt"]).isNotNull()
    }

    // NOTE: the ON DELETE CASCADE from user_profile_picture to users is deliberately not asserted
    // here. Flyway is disabled in the `test` environment and the schema is generated by Hibernate
    // from the entities; UserProfilePicture holds a plain userId rather than a @ManyToOne, so no
    // foreign key exists in the test schema to exercise. The cascade is a V251 migration-level
    // guarantee and is verified against a Flyway-migrated database, not here.
}
