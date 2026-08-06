package com.secman.service

import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/**
 * Unit tests for [ProfilePictureService].
 *
 * Feature: Profile Picture Management
 *
 * Fixtures are generated in-process so no binary files land in the repo.
 */
class ProfilePictureServiceTest {

    // The real SecurityService is used so its MIME/extension rules are genuinely exercised; the
    // two methods called here (isAllowedMimeType, validateFileExtensionMatchesMimeType) are pure
    // and never touch the injected repositories.
    private val securityService = SecurityService(mockk(), mockk(), mockk())

    private fun service(
        maxUploadBytes: Long = 2 * 1024 * 1024,
        maxSourceDimension: Int = 10_000,
        maxSourcePixels: Long = 40_000_000,
        targetEdge: Int = 256
    ) = ProfilePictureService(
        securityService = securityService,
        maxUploadBytes = maxUploadBytes,
        maxSourceDimension = maxSourceDimension,
        maxSourcePixels = maxSourcePixels,
        targetEdge = targetEdge
    )

    private fun imageBytes(
        width: Int,
        height: Int,
        format: String,
        alpha: Boolean = true
    ): ByteArray {
        val type = if (alpha) BufferedImage.TYPE_INT_ARGB else BufferedImage.TYPE_INT_RGB
        val image = BufferedImage(width, height, type)
        val g = image.createGraphics()
        try {
            g.color = Color.BLUE
            g.fillRect(0, 0, width, height)
            g.color = Color.ORANGE
            g.fillRect(0, 0, width / 2, height / 2)
        } finally {
            g.dispose()
        }
        val out = ByteArrayOutputStream()
        ImageIO.write(image, format, out)
        return out.toByteArray()
    }

    @Test
    fun `accepts a PNG and normalizes it to a square thumbnail`() {
        val result = service().normalize(
            imageBytes(800, 400, "png"),
            "avatar.png",
            "image/png"
        )

        assertThat(result).isInstanceOf(ProfilePictureService.Result.Ok::class.java)
        val ok = result as ProfilePictureService.Result.Ok
        assertThat(ok.width).isEqualTo(256)
        assertThat(ok.height).isEqualTo(256)
        assertThat(ok.contentType).isEqualTo(ProfilePictureService.OUTPUT_PNG)
        assertThat(ok.sha256).hasSize(64)
        assertThat(ok.originalFilename).isEqualTo("avatar.png")

        // The stored bytes must be a genuinely decodable image, not a passthrough.
        val decoded = ImageIO.read(ok.bytes.inputStream())
        assertThat(decoded.width).isEqualTo(256)
        assertThat(decoded.height).isEqualTo(256)
    }

    @Test
    fun `accepts a JPEG and stores it as JPEG when the source has no alpha`() {
        val result = service().normalize(
            imageBytes(600, 600, "jpeg", alpha = false),
            "photo.jpg",
            "image/jpeg"
        )

        val ok = result as ProfilePictureService.Result.Ok
        assertThat(ok.contentType).isEqualTo(ProfilePictureService.OUTPUT_JPEG)
        assertThat(ok.width).isEqualTo(256)
    }

    @Test
    fun `accepts a GIF`() {
        val result = service().normalize(
            imageBytes(300, 300, "gif"),
            "animation.gif",
            "image/gif"
        )

        assertThat(result).isInstanceOf(ProfilePictureService.Result.Ok::class.java)
    }

    @Test
    fun `does not upscale a source smaller than the target edge`() {
        val result = service().normalize(
            imageBytes(64, 64, "png"),
            "small.png",
            "image/png"
        )

        val ok = result as ProfilePictureService.Result.Ok
        assertThat(ok.width).isEqualTo(64)
        assertThat(ok.height).isEqualTo(64)
    }

    @Test
    fun `rejects an empty upload`() {
        val result = service().normalize(ByteArray(0), "avatar.png", "image/png")
        assertThat(result).isInstanceOf(ProfilePictureService.Result.Rejected::class.java)
    }

    @Test
    fun `rejects an upload over the size cap`() {
        val bytes = imageBytes(400, 400, "png")
        val result = service(maxUploadBytes = 10).normalize(bytes, "avatar.png", "image/png")

        val rejected = result as ProfilePictureService.Result.Rejected
        assertThat(rejected.message).contains("smaller")
    }

    @Test
    fun `rejects SVG`() {
        val svg = """<svg xmlns="http://www.w3.org/2000/svg" onload="alert(1)"></svg>"""
            .toByteArray()
        val result = service().normalize(svg, "avatar.svg", "image/svg+xml")

        assertThat(result).isInstanceOf(ProfilePictureService.Result.Rejected::class.java)
    }

    @Test
    fun `rejects WEBP because the JDK cannot decode it`() {
        val result = service().normalize(
            imageBytes(200, 200, "png"),
            "avatar.webp",
            "image/webp"
        )

        assertThat(result).isInstanceOf(ProfilePictureService.Result.Rejected::class.java)
    }

    @Test
    fun `rejects a magic-byte mismatch`() {
        val html = "<script>alert(1)</script>".toByteArray()
        val result = service().normalize(html, "avatar.png", "image/png")

        val rejected = result as ProfilePictureService.Result.Rejected
        assertThat(rejected.message).contains("does not match")
    }

    @Test
    fun `rejects real JPEG bytes declared as PNG`() {
        val result = service().normalize(
            imageBytes(200, 200, "jpeg", alpha = false),
            "avatar.png",
            "image/png"
        )

        assertThat(result).isInstanceOf(ProfilePictureService.Result.Rejected::class.java)
    }

    @Test
    fun `rejects a filename extension that contradicts the declared type`() {
        val result = service().normalize(
            imageBytes(200, 200, "png"),
            "avatar.gif",
            "image/png"
        )

        val rejected = result as ProfilePictureService.Result.Rejected
        assertThat(rejected.message).contains("extension")
    }

    @Test
    fun `accepts an upload with no filename`() {
        val result = service().normalize(imageBytes(200, 200, "png"), null, "image/png")

        val ok = result as ProfilePictureService.Result.Ok
        assertThat(ok.originalFilename).isNull()
    }

    @Test
    fun `rejects an image whose dimensions exceed the bomb guard`() {
        // Guard is checked from the image header, before any raster is allocated.
        val result = service(maxSourceDimension = 64).normalize(
            imageBytes(300, 300, "png"),
            "big.png",
            "image/png"
        )

        val rejected = result as ProfilePictureService.Result.Rejected
        assertThat(rejected.message).contains("dimensions")
    }

    @Test
    fun `rejects an image whose pixel count exceeds the bomb guard`() {
        val result = service(maxSourcePixels = 1000).normalize(
            imageBytes(300, 300, "png"),
            "big.png",
            "image/png"
        )

        assertThat(result).isInstanceOf(ProfilePictureService.Result.Rejected::class.java)
    }

    @Test
    fun `re-encoding strips a payload appended to a valid image`() {
        // A PNG with trailing script bytes still decodes as a PNG. Magic-byte sniffing alone would
        // pass it through; the decode/re-encode round trip is what actually removes the payload.
        val payload = "<script>alert(1)</script>".toByteArray()
        val polyglot = imageBytes(300, 300, "png") + payload

        val result = service().normalize(polyglot, "avatar.png", "image/png")

        val ok = result as ProfilePictureService.Result.Ok
        assertThat(String(ok.bytes, Charsets.ISO_8859_1)).doesNotContain("<script")
    }

    @Test
    fun `produces a stable sha256 for identical input`() {
        val bytes = imageBytes(300, 300, "png")
        val first = service().normalize(bytes, "avatar.png", "image/png")
            as ProfilePictureService.Result.Ok
        val second = service().normalize(bytes, "avatar.png", "image/png")
            as ProfilePictureService.Result.Ok

        assertThat(first.sha256).isEqualTo(second.sha256)
    }

    @Test
    fun `sanitizes an audit filename without rejecting spaces`() {
        val result = service().normalize(
            imageBytes(200, 200, "png"),
            "my holiday photo.png",
            "image/png"
        )

        val ok = result as ProfilePictureService.Result.Ok
        assertThat(ok.originalFilename).isEqualTo("my holiday photo.png")
    }

    @Test
    fun `strips path segments from the audit filename`() {
        val result = service().normalize(
            imageBytes(200, 200, "png"),
            "../../etc/passwd.png",
            "image/png"
        )

        val ok = result as ProfilePictureService.Result.Ok
        assertThat(ok.originalFilename).isEqualTo("passwd.png")
    }

    @Test
    fun `tolerates a content type carrying parameters`() {
        val result = service().normalize(
            imageBytes(200, 200, "png"),
            "avatar.png",
            "image/png; charset=binary"
        )

        assertThat(result).isInstanceOf(ProfilePictureService.Result.Ok::class.java)
    }

    // A real browser or curl multipart part arrives with contentType absent: Micronaut does not
    // surface it on CompletedFileUpload, so the controller passes null. Treating that as a
    // rejection made every upload fail in production while the suites stayed green, because
    // every other test hard-codes the type and the integration test uses Micronaut's own
    // MultipartBody - a wire format no real client produces. The content itself is the
    // authoritative signal, so an absent declaration must fall back to sniffing, not reject.

    @Test
    fun `accepts a PNG when the client declares no content type`() {
        val result = service().normalize(imageBytes(300, 300, "png"), "avatar.png", null)

        assertThat(result).isInstanceOf(ProfilePictureService.Result.Ok::class.java)
    }

    @Test
    fun `accepts a JPEG when the client declares no content type`() {
        val result = service().normalize(
            imageBytes(300, 300, "jpeg", alpha = false),
            "avatar.jpg",
            null
        )

        assertThat(result).isInstanceOf(ProfilePictureService.Result.Ok::class.java)
    }

    @Test
    fun `accepts a GIF when the client declares no content type`() {
        val result = service().normalize(
            imageBytes(300, 300, "gif", alpha = false),
            "avatar.gif",
            null
        )

        assertThat(result).isInstanceOf(ProfilePictureService.Result.Ok::class.java)
    }

    @Test
    fun `accepts an image when the client declares a blank content type`() {
        val result = service().normalize(imageBytes(300, 300, "png"), "avatar.png", "   ")

        assertThat(result).isInstanceOf(ProfilePictureService.Result.Ok::class.java)
    }

    @Test
    fun `still rejects a non-image when the client declares no content type`() {
        val html = "<html><body><script>alert(1)</script></body></html>".toByteArray()

        val result = service().normalize(html, "avatar.png", null)

        assertThat(result).isInstanceOf(ProfilePictureService.Result.Rejected::class.java)
    }

    @Test
    fun `still rejects SVG when the client declares no content type`() {
        val svg = "<svg xmlns=\"http://www.w3.org/2000/svg\"><script>alert(1)</script></svg>".toByteArray()

        val result = service().normalize(svg, "avatar.svg", null)

        assertThat(result).isInstanceOf(ProfilePictureService.Result.Rejected::class.java)
    }

    @Test
    fun `rejects a filename extension that disagrees with sniffed content when none is declared`() {
        // The extension gate must keep working off the sniffed type, not silently pass.
        val result = service().normalize(imageBytes(200, 200, "png"), "avatar.gif", null)

        assertThat(result).isInstanceOf(ProfilePictureService.Result.Rejected::class.java)
    }
}
