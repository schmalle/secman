package com.secman.service

import java.security.MessageDigest
import java.util.HexFormat

/** Detects changes to an approved questionnaire or to a replayed submission. */
internal fun supplierPayloadFingerprint(payload: ByteArray): String =
    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload))
