package com.secman.config

import com.secman.domain.UseCase
import com.secman.repository.UseCaseRepository
import io.micronaut.context.event.ApplicationEventListener
import io.micronaut.runtime.event.ApplicationStartupEvent
import io.micronaut.transaction.annotation.Transactional
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory

@Singleton
open class SystemUseCaseInitializer(
    private val useCaseRepository: UseCaseRepository
) : ApplicationEventListener<ApplicationStartupEvent> {

    private val log = LoggerFactory.getLogger(SystemUseCaseInitializer::class.java)

    companion object {
        val SYSTEM_USE_CASE_NAMES = listOf("IT", "OT", "NT")
    }

    override fun onApplicationEvent(event: ApplicationStartupEvent) {
        initializeSystemUseCases()
    }

    @Transactional
    open fun initializeSystemUseCases() {
        for (name in SYSTEM_USE_CASE_NAMES) {
            try {
                val existing = useCaseRepository.findByNameIgnoreCase(name)
                if (existing.isPresent) {
                    val useCase = existing.get()
                    if (!useCase.systemProtected) {
                        useCase.systemProtected = true
                        useCaseRepository.update(useCase)
                        log.info("Marked existing use case '{}' as system-protected", name)
                    }
                } else {
                    val useCase = UseCase(name = name, systemProtected = true)
                    useCaseRepository.save(useCase)
                    log.info("Created system use case '{}'", name)
                }
            } catch (e: Exception) {
                log.error("Failed to initialize system use case '{}': {}", name, e.message, e)
            }
        }
    }
}
