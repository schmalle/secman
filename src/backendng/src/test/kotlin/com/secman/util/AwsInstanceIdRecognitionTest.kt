package com.secman.util

import com.secman.service.QueryType
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * ValidationUtils and InputDetectionUtils each carry their own private copy of the
 * AWS instance-id regex, and neither had a test. They must agree: InputDetectionUtils
 * decides whether a CrowdStrike lookup is routed as an instance-id query, while
 * ValidationUtils decides whether that same string is accepted at all. If the two
 * copies ever drift, an input is routed one way and rejected the other, and the
 * lookup fails for a value the UI happily accepted.
 *
 * ID prefix: AID-*
 */
class AwsInstanceIdRecognitionTest {

    /** Legacy 8-hex and current 17-hex forms, both of which AWS still issues or honours. */
    private val validIds = listOf(
        "i-1234567a",           // legacy, 8 hex
        "i-0048f94221fe110cf",  // current, 17 hex
        "i-ABCDEF01",           // uppercase hex is accepted
        "I-1234567a",           // IGNORE_CASE covers the whole pattern, so the prefix too
        "i-00000000000000000"   // 17 zeros — all-hex boundary value
    )

    private val invalidIds = listOf(
        "i-1234567",            // 7 hex — one below the lower bound
        "i-000000000000000000", // 18 hex — one above the upper bound
        "i-1234567g",           // 'g' is not hex
        "i-",                   // prefix only
        "1234567890abcdef",     // no prefix
        "web-01.example.com",   // an ordinary hostname
        " i-1234567a",          // leading space — anchors must reject, not trim
        "i-1234567a "           // trailing space
    )

    @Test
    @DisplayName("AID-001: ValidationUtils accepts the legacy and current id lengths")
    fun acceptsValidIds() {
        validIds.forEach { id ->
            assertThat(ValidationUtils.isValidAwsInstanceId(id)).describedAs("valid: %s", id).isTrue()
        }
    }

    @Test
    @DisplayName("AID-002: ValidationUtils rejects out-of-range, non-hex and unanchored input")
    fun rejectsInvalidIds() {
        invalidIds.forEach { id ->
            assertThat(ValidationUtils.isValidAwsInstanceId(id)).describedAs("invalid: %s", id).isFalse()
        }
    }

    @Test
    @DisplayName("AID-003: normalization lowercases and rejects anything invalid")
    fun normalizesToLowercase() {
        assertThat(ValidationUtils.validateAndNormalizeAwsInstanceId("i-ABCDEF01")).isEqualTo("i-abcdef01")
        assertThat(ValidationUtils.validateAndNormalizeAwsInstanceId("i-0048f94221fe110cf"))
            .isEqualTo("i-0048f94221fe110cf")

        assertThatThrownBy { ValidationUtils.validateAndNormalizeAwsInstanceId("web-01") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("Invalid instance ID format")
    }

    @Test
    @DisplayName("AID-004: the validation error message quotes the offending value")
    fun errorMessageNamesTheInput() {
        // The message is surfaced to the user, so it has to identify what was rejected.
        assertThat(ValidationUtils.getInstanceIdValidationError("i-zzz")).contains("i-zzz")
    }

    @Test
    @DisplayName("AID-005: detection routes instance ids and hostnames to the right QueryType")
    fun detectsQueryType() {
        validIds.forEach { id ->
            assertThat(InputDetectionUtils.detectQueryType(id)).describedAs("id: %s", id)
                .isEqualTo(QueryType.INSTANCE_ID)
        }
        listOf("web-01", "web-01.example.com", "i-1234567", "").forEach { host ->
            assertThat(InputDetectionUtils.detectQueryType(host)).describedAs("hostname: %s", host)
                .isEqualTo(QueryType.HOSTNAME)
        }
    }

    @Test
    @DisplayName("AID-006: the two duplicated regexes agree on every case")
    fun detectionAndValidationAgree() {
        // This is the assertion that matters: the duplication is the bug risk, and
        // nothing else in the build would notice the two copies diverging.
        (validIds + invalidIds).forEach { input ->
            assertThat(InputDetectionUtils.isAwsInstanceId(input))
                .describedAs("detection vs validation disagree on: %s", input)
                .isEqualTo(ValidationUtils.isValidAwsInstanceId(input))
        }
    }

    @Test
    @DisplayName("AID-007: isHostname is the exact complement of isAwsInstanceId")
    fun hostnameIsTheComplement() {
        (validIds + invalidIds).forEach { input ->
            assertThat(InputDetectionUtils.isHostname(input)).describedAs("input: %s", input)
                .isEqualTo(!InputDetectionUtils.isAwsInstanceId(input))
        }
    }
}
