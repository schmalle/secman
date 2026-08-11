package com.secman.tools

import com.secman.service.ExampleRequirementExportTemplateBuilder
import java.io.File

/**
 * Writes the example company requirement-export template to the path given as the first argument.
 *
 * Invoked by the `generateExampleRequirementTemplate` Gradle task; it exists so the committed
 * `.docx` is always a build product of [ExampleRequirementExportTemplateBuilder] rather than a
 * binary somebody edited by hand and nobody can review.
 */
fun main(args: Array<String>) {
    require(args.isNotEmpty()) { "Usage: GenerateExampleRequirementTemplate <output-path>" }

    val target = File(args[0])
    target.parentFile?.mkdirs()

    val bytes = ExampleRequirementExportTemplateBuilder().build()
    target.writeBytes(bytes)

    println("Wrote ${bytes.size} bytes to ${target.absolutePath}")
}
