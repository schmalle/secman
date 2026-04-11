package com.secman

import io.micronaut.runtime.Micronaut
import org.apache.poi.openxml4j.util.ZipSecureFile
import org.apache.poi.util.IOUtils

fun main(args: Array<String>) {
    // Security hardening for Apache POI before any workbook is parsed.
    // Defends against zip-bomb / decompression-bomb attacks via crafted XLSX uploads
    // (Apache POI CVE-2014-3574 and similar). Without these limits a 5 MB file can
    // expand to many gigabytes during XSSFWorkbook parsing and exhaust JVM memory.
    //
    // - setMinInflateRatio: rejects archives whose compression ratio is below the
    //   threshold (compressed/uncompressed). 0.001 means we accept up to 1:1000.
    // - setByteArrayMaxOverride: hard cap on the largest byte[] POI is allowed to
    //   allocate while parsing a single record (100 MB).
    ZipSecureFile.setMinInflateRatio(0.001)
    IOUtils.setByteArrayMaxOverride(100 * 1024 * 1024)

    Micronaut.build()
        .args(*args)
        .packages("com.secman")
        .start()
}