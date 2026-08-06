package com.secman.service

import io.micronaut.context.annotation.Value
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

/**
 * Validates and normalizes uploaded profile pictures.
 *
 * Feature: Profile Picture Management
 *
 * The upload is never stored as-is. It is decoded to a raster, centre-cropped to a square, scaled
 * to [targetEdge] and re-encoded. That round trip is the strongest control in this pipeline: any
 * polyglot payload (PNG header with trailing HTML, EXIF-embedded script, GIF with a comment
 * block) does not survive it, whereas magic-byte sniffing only ever inspects the first few bytes.
 * It also strips EXIF/GPS metadata and keeps stored rows small.
 *
 * Validation order is cheapest-and-most-decisive first:
 *   1. size cap
 *   2. declared MIME against the allowlist
 *   3. filename extension against the declared MIME
 *   4. magic bytes against the declared MIME
 *   5. header-only dimensions (decompression-bomb guard, before any raster is allocated)
 *   6. the reader's actual format against the declared MIME
 *   7. decode -> centre-crop -> scale -> re-encode
 *
 * WEBP is deliberately absent from the allowlist: the JDK has no WEBP reader, so accepting it
 * would mean storing bytes that were never decoded. Browsers re-encode to PNG when the frontend
 * cropper exports its canvas, so WEBP and HEIC sources still work end to end.
 */
@Singleton
open class ProfilePictureService(
    private val securityService: SecurityService,
    @Value("\${secman.profile-picture.max-upload-bytes:2097152}")
    private val maxUploadBytes: Long,
    @Value("\${secman.profile-picture.max-source-dimension:10000}")
    private val maxSourceDimension: Int,
    @Value("\${secman.profile-picture.max-source-pixels:40000000}")
    private val maxSourcePixels: Long,
    @Value("\${secman.profile-picture.target-edge:256}")
    private val targetEdge: Int
) {
    private val logger = LoggerFactory.getLogger(ProfilePictureService::class.java)

    init {
        // BufferedImage/Graphics2D raster work needs no display, and the JDK normally auto-detects
        // headless on a server. Set it explicitly before the first ImageIO call so a container with
        // a stray DISPLAY can never push us onto a windowing toolkit.
        if (System.getProperty("java.awt.headless") == null) {
            System.setProperty("java.awt.headless", "true")
        }
    }

    companion object {
        val ALLOWED_UPLOAD_TYPES = setOf("image/png", "image/jpeg", "image/gif")

        const val OUTPUT_PNG = "image/png"
        const val OUTPUT_JPEG = "image/jpeg"

        private const val JPEG_QUALITY = 0.85f

        private val MAGIC_PNG = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
        )
        private val MAGIC_JPEG = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
        private val MAGIC_GIF87 = "GIF87a".toByteArray(Charsets.US_ASCII)
        private val MAGIC_GIF89 = "GIF89a".toByteArray(Charsets.US_ASCII)

        /** Filename characters kept for the audit record. Spaces are allowed on purpose. */
        private val AUDIT_FILENAME_ALLOWED = Regex("[^A-Za-z0-9._ -]")
    }

    /** Outcome of [normalize]. */
    sealed interface Result {
        data class Ok(
            val bytes: ByteArray,
            val contentType: String,
            val width: Int,
            val height: Int,
            val sha256: String,
            val originalFilename: String?
        ) : Result

        data class Rejected(val message: String) : Result
    }

    /**
     * Validate and normalize an uploaded image.
     *
     * @param bytes raw uploaded bytes
     * @param filename client-supplied filename (may be null/blank)
     * @param declaredContentType client-supplied content type (may be null/blank)
     */
    fun normalize(bytes: ByteArray, filename: String?, declaredContentType: String?): Result {
        // 1. Size. The caller should also check CompletedFileUpload.size before materializing
        // the bytes; this is the authoritative check.
        if (bytes.isEmpty()) {
            return Result.Rejected("The uploaded file is empty")
        }
        if (bytes.size > maxUploadBytes) {
            return Result.Rejected("Image must be ${maxUploadBytes / (1024 * 1024)} MB or smaller")
        }

        // 2. Effective MIME against the allowlist. SVG never reaches step 5 (the JDK has no SVG
        // reader) but is rejected here explicitly so the message is useful.
        //
        // The declared type is a client claim and, for the multipart parts real browsers and
        // curl send, Micronaut does not surface it on CompletedFileUpload at all - so it arrives
        // null and treating that as a rejection failed every upload of every format. Fall back
        // to the magic bytes, which are the stronger signal. A declaration that IS present is
        // still honoured, so step 4 continues to cross-check a real claim against the content,
        // and step 6 re-checks the reader's own format regardless of where this value came from.
        val declaredRaw = declaredContentType?.substringBefore(';')?.trim()?.lowercase()
        val declared = if (declaredRaw.isNullOrBlank()) sniffContentType(bytes) else declaredRaw
        if (declared == null || declared !in ALLOWED_UPLOAD_TYPES) {
            return Result.Rejected("Only PNG, JPEG and GIF images are allowed")
        }
        if (!securityService.isAllowedMimeType(declared)) {
            // Defensive: keeps this endpoint aligned with the repo-wide allowlist if that
            // ever narrows.
            return Result.Rejected("Only PNG, JPEG and GIF images are allowed")
        }

        // 3. Extension against declared MIME, when the client sent a filename with an extension.
        // NOTE: SecurityService.validateAndSanitizeFilename is deliberately NOT used as a gate
        // here - its SAFE_FILENAME_PATTERN rejects spaces, which would reject "my photo.png".
        val trimmedName = filename?.trim().orEmpty()
        if (trimmedName.isNotBlank() && trimmedName.contains('.')) {
            if (!securityService.validateFileExtensionMatchesMimeType(trimmedName, declared)) {
                return Result.Rejected("File extension does not match its content type")
            }
        }

        // 4. Magic bytes against declared MIME.
        val sniffed = sniffContentType(bytes)
        if (sniffed == null || sniffed != declared) {
            return Result.Rejected("File content does not match its declared type")
        }

        // 5-7. Header-only dimension probe, format cross-check, then decode and re-encode.
        return try {
            decodeAndNormalize(bytes, declared, sanitizeForAudit(trimmedName))
        } catch (e: Exception) {
            logger.warn("Profile picture could not be processed: {}", e.message)
            Result.Rejected("The image could not be processed")
        }
    }

    private fun decodeAndNormalize(
        bytes: ByteArray,
        declared: String,
        auditFilename: String?
    ): Result =
        ImageIO.createImageInputStream(ByteArrayInputStream(bytes)).use { input ->
            if (input == null) return@use Result.Rejected("Unsupported or corrupt image")

            val readers = ImageIO.getImageReaders(input)
            if (!readers.hasNext()) return@use Result.Rejected("Unsupported or corrupt image")

            val reader = readers.next()
            try {
                reader.setInput(input, true, true)

                // 5. Dimensions come from the header - no raster is allocated yet. Without this
                // guard a 2 MB PNG could decode into a multi-gigabyte BufferedImage and take the
                // JVM down on a single request.
                val sourceWidth = reader.getWidth(0)
                val sourceHeight = reader.getHeight(0)

                when {
                    sourceWidth <= 0 || sourceHeight <= 0 ->
                        Result.Rejected("Unsupported or corrupt image")

                    sourceWidth > maxSourceDimension || sourceHeight > maxSourceDimension ||
                        sourceWidth.toLong() * sourceHeight.toLong() > maxSourcePixels ->
                        Result.Rejected("Image dimensions exceed the allowed limit")

                    // 6. The reader's own view of the format, stronger than a header sniff.
                    !formatMatchesDeclared(reader.formatName, declared) ->
                        Result.Rejected("File content does not match its declared type")

                    else -> {
                        // 7. Decode frame 0 only (a multi-frame GIF collapses to one frame).
                        val source = reader.read(0)
                        if (source == null) {
                            Result.Rejected("The image could not be decoded")
                        } else {
                            val hasAlpha = source.colorModel.hasAlpha()
                            val square = cropToSquareAndScale(source, hasAlpha)
                            val outputType = if (hasAlpha) OUTPUT_PNG else OUTPUT_JPEG
                            val encoded = encode(square, outputType)

                            Result.Ok(
                                bytes = encoded,
                                contentType = outputType,
                                width = square.width,
                                height = square.height,
                                sha256 = sha256Hex(encoded),
                                originalFilename = auditFilename
                            )
                        }
                    }
                }
            } finally {
                reader.dispose()
            }
        }

    /** Centre-crop to a square, then scale to [targetEdge]. */
    private fun cropToSquareAndScale(source: BufferedImage, hasAlpha: Boolean): BufferedImage {
        val edge = minOf(source.width, source.height)
        val x = (source.width - edge) / 2
        val y = (source.height - edge) / 2
        val cropped = source.getSubimage(x, y, edge, edge)

        val outEdge = minOf(targetEdge, edge).coerceAtLeast(1)
        val type = if (hasAlpha) BufferedImage.TYPE_INT_ARGB else BufferedImage.TYPE_INT_RGB
        val scaled = BufferedImage(outEdge, outEdge, type)

        val g = scaled.createGraphics()
        try {
            g.setRenderingHint(
                RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR
            )
            g.setRenderingHint(
                RenderingHints.KEY_RENDERING,
                RenderingHints.VALUE_RENDER_QUALITY
            )
            g.setRenderingHint(
                RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON
            )
            // Image.getScaledInstance is deliberately avoided: poorer quality and asynchronous.
            g.drawImage(cropped, 0, 0, outEdge, outEdge, null)
        } finally {
            g.dispose()
        }
        return scaled
    }

    private fun encode(image: BufferedImage, contentType: String): ByteArray {
        val out = ByteArrayOutputStream()
        if (contentType == OUTPUT_JPEG) {
            val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
            try {
                ImageIO.createImageOutputStream(out).use { output ->
                    writer.output = output
                    val params = writer.defaultWriteParam
                    if (params.canWriteCompressed()) {
                        params.compressionMode = ImageWriteParam.MODE_EXPLICIT
                        params.compressionQuality = JPEG_QUALITY
                    }
                    writer.write(null, IIOImage(image, null, null), params)
                }
            } finally {
                writer.dispose()
            }
        } else {
            ImageIO.write(image, "png", out)
        }
        return out.toByteArray()
    }

    private fun sniffContentType(bytes: ByteArray): String? = when {
        startsWith(bytes, MAGIC_PNG) -> "image/png"
        startsWith(bytes, MAGIC_JPEG) -> "image/jpeg"
        startsWith(bytes, MAGIC_GIF87) || startsWith(bytes, MAGIC_GIF89) -> "image/gif"
        else -> null
    }

    private fun startsWith(bytes: ByteArray, prefix: ByteArray): Boolean {
        if (bytes.size < prefix.size) return false
        for (i in prefix.indices) {
            if (bytes[i] != prefix[i]) return false
        }
        return true
    }

    private fun formatMatchesDeclared(formatName: String?, declared: String): Boolean {
        val format = formatName?.lowercase() ?: return false
        return when (declared) {
            "image/png" -> format == "png"
            "image/jpeg" -> format == "jpeg" || format == "jpg"
            "image/gif" -> format == "gif"
            else -> false
        }
    }

    /**
     * Keep a readable filename for the audit trail. This is never echoed into a response header -
     * the Content-Disposition filename is synthesized by the controller.
     */
    private fun sanitizeForAudit(filename: String): String? {
        if (filename.isBlank()) return null
        val base = filename.substringAfterLast('/').substringAfterLast('\\')
        val cleaned = AUDIT_FILENAME_ALLOWED.replace(base, "_").trim()
        return cleaned.take(255).ifBlank { null }
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
}
