package com.secman.controller

import com.secman.domain.ProductClass
import com.secman.domain.ProductClassificationRule
import com.secman.domain.RuleMatchField
import com.secman.dto.ProductClassificationRuleRequest
import com.secman.dto.ProductClassificationRuleResponse
import com.secman.dto.ProductClassificationStatsResponse
import com.secman.dto.ProductClassificationTestRequest
import com.secman.dto.ProductClassificationTestResponse
import com.secman.repository.EolFindingRepository
import com.secman.repository.InstalledProductRepository
import com.secman.repository.ProductClassificationRuleRepository
import com.secman.repository.VulnerabilityRepository
import com.secman.service.ProductClassificationService
import com.secman.service.ProductClassifier
import io.micronaut.http.HttpResponse
import io.micronaut.http.annotation.Body
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Delete
import io.micronaut.http.annotation.Get
import io.micronaut.http.annotation.PathVariable
import io.micronaut.http.annotation.Post
import io.micronaut.http.annotation.Put
import io.micronaut.security.annotation.Secured
import io.micronaut.security.authentication.Authentication
import io.micronaut.validation.Validated
import jakarta.validation.Valid
import org.slf4j.LoggerFactory

/**
 * Admin CRUD over the installer/setup classification rules.
 *
 * ADMIN-only throughout: these rules decide what disappears from every vulnerability and EOL
 * read surface, so write access is strictly narrower than the read roles
 * (ADMIN / VULN / SECCHAMPION) on the data they affect.
 */
@Controller("/api/product-classification")
@Secured("ADMIN")
@Validated
open class ProductClassificationController(
    private val ruleRepository: ProductClassificationRuleRepository,
    private val classificationService: ProductClassificationService,
    private val vulnerabilityRepository: VulnerabilityRepository,
    private val installedProductRepository: InstalledProductRepository,
    private val eolFindingRepository: EolFindingRepository
) {
    private val log = LoggerFactory.getLogger(ProductClassificationController::class.java)

    @Get("/rules")
    open fun list(): HttpResponse<List<ProductClassificationRuleResponse>> =
        HttpResponse.ok(ruleRepository.findAllOrdered().map { ProductClassificationRuleResponse.from(it) })

    @Post("/rules")
    open fun create(
        @Body @Valid request: ProductClassificationRuleRequest,
        authentication: Authentication
    ): HttpResponse<*> {
        validate(request)?.let { return HttpResponse.badRequest(mapOf("error" to it)) }
        if (request.enabled && ruleRepository.countByEnabled(true) >= ProductClassificationRule.MAX_ENABLED_RULES) {
            return HttpResponse.badRequest(
                mapOf("error" to "At most ${ProductClassificationRule.MAX_ENABLED_RULES} rules may be enabled")
            )
        }
        val saved = ruleRepository.save(
            ProductClassificationRule(
                matchField = request.matchField,
                pattern = request.pattern.trim(),
                classification = request.classification,
                priority = request.priority,
                enabled = request.enabled,
                description = request.description?.trim(),
                createdBy = authentication.name
            )
        )
        classificationService.invalidateRules()
        log.info(
            "Product classification rule created: actor={} id={} field={} pattern='{}' class={} outcome=SUCCESS",
            authentication.name, saved.id, saved.matchField, sanitize(saved.pattern), saved.classification
        )
        return HttpResponse.ok(ProductClassificationRuleResponse.from(saved))
    }

    @Put("/rules/{id}")
    open fun update(
        @PathVariable id: Long,
        @Body @Valid request: ProductClassificationRuleRequest,
        authentication: Authentication
    ): HttpResponse<*> {
        validate(request)?.let { return HttpResponse.badRequest(mapOf("error" to it)) }
        val existing = ruleRepository.findById(id).orElse(null)
            ?: return HttpResponse.notFound(mapOf("error" to "Rule not found"))
        existing.matchField = request.matchField
        existing.pattern = request.pattern.trim()
        existing.classification = request.classification
        existing.priority = request.priority
        existing.enabled = request.enabled
        existing.description = request.description?.trim()
        val saved = ruleRepository.update(existing)
        classificationService.invalidateRules()
        log.info(
            "Product classification rule updated: actor={} id={} pattern='{}' class={} enabled={} outcome=SUCCESS",
            authentication.name, id, sanitize(saved.pattern), saved.classification, saved.enabled
        )
        return HttpResponse.ok(ProductClassificationRuleResponse.from(saved))
    }

    @Delete("/rules/{id}")
    open fun delete(@PathVariable id: Long, authentication: Authentication): HttpResponse<*> {
        if (!ruleRepository.existsById(id)) {
            return HttpResponse.notFound(mapOf("error" to "Rule not found"))
        }
        ruleRepository.deleteById(id)
        classificationService.invalidateRules()
        log.info("Product classification rule deleted: actor={} id={} outcome=SUCCESS", authentication.name, id)
        return HttpResponse.ok(mapOf("deleted" to id))
    }

    /** "What would this value classify as?" — no writes, no side effects. */
    @Post("/test")
    open fun test(@Body @Valid request: ProductClassificationTestRequest): HttpResponse<ProductClassificationTestResponse> {
        val rules = classificationService.rules()
        val classification = when (request.matchField) {
            RuleMatchField.INSTALL_PATH -> ProductClassifier.classifyProduct("", null, listOf(request.value), rules)
            else -> ProductClassifier.classifyVulnerability(request.value, rules)
        }
        val matched = firstMatching(request, rules)
        return HttpResponse.ok(
            ProductClassificationTestResponse(
                value = request.value,
                classification = classification,
                matchedRuleId = matched?.source?.id,
                matchedPattern = matched?.source?.pattern
            )
        )
    }

    /**
     * Re-apply the rules to every stored row. Returns 202 immediately: a full pass walks
     * `installed_product`, `eol_finding` and `vulnerability`, which is far past a request budget.
     */
    @Post("/reclassify")
    open fun reclassify(authentication: Authentication): HttpResponse<*> {
        log.info("Product reclassify requested: actor={} outcome=ACCEPTED", authentication.name)
        classificationService.reclassifyAllAsync()
        return HttpResponse.accepted<Any>().body(mapOf("status" to "STARTED"))
    }

    @Get("/stats")
    open fun stats(): HttpResponse<ProductClassificationStatsResponse> = HttpResponse.ok(
        ProductClassificationStatsResponse(
            installedProductArtifacts = installedProductRepository
                .countByProductClass(ProductClass.INSTALLER_ARTIFACT.name),
            eolFindingArtifacts = eolFindingRepository.countInstallerArtifacts(),
            vulnerabilityArtifacts = vulnerabilityRepository.countInstallerArtifacts(),
            enabledRules = ruleRepository.countByEnabled(true)
        )
    )

    private fun firstMatching(
        request: ProductClassificationTestRequest,
        rules: List<ProductClassifier.CompiledRule>
    ): ProductClassifier.CompiledRule? {
        val normalized = when (request.matchField) {
            RuleMatchField.INSTALL_PATH -> ProductClassifier.normalizePath(request.value)
            else -> ProductClassifier.normalizeText(request.value)
        }
        return rules.firstOrNull { it.matchField == request.matchField && it.regex.matches(normalized) }
    }

    /**
     * Reject a pattern the classifier would silently drop, so a typo surfaces on write rather than
     * as a rule that quietly never fires.
     */
    private fun validate(request: ProductClassificationRuleRequest): String? {
        val pattern = request.pattern.trim()
        if (pattern.isEmpty()) return "Pattern must not be blank"
        if (pattern.length > ProductClassificationRule.MAX_PATTERN_LENGTH) {
            return "Pattern must be at most ${ProductClassificationRule.MAX_PATTERN_LENGTH} characters"
        }
        if (ProductClassifier.globToRegex(pattern) == null) return "Pattern is not a valid glob"
        if (request.classification == ProductClass.UNKNOWN) {
            return "A rule must classify as INSTALLED or INSTALLER_ARTIFACT"
        }
        return null
    }

    /** Strip CR/LF before a user-supplied value reaches a log line (log forging). */
    private fun sanitize(value: String) = value.replace('\n', ' ').replace('\r', ' ').take(200)
}
