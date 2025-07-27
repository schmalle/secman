package com.secman.service

import com.secman.domain.IdentityProvider
import com.secman.repository.IdentityProviderRepository
import io.micronaut.context.event.ApplicationEventListener
import io.micronaut.runtime.event.ApplicationStartupEvent
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory

@Singleton
class ProviderInitializationService(
    private val identityProviderRepository: IdentityProviderRepository
) : ApplicationEventListener<ApplicationStartupEvent> {
    
    private val logger = LoggerFactory.getLogger(ProviderInitializationService::class.java)

    override fun onApplicationEvent(event: ApplicationStartupEvent) {
        initializeDefaultProviders()
    }

    private fun initializeDefaultProviders() {
        try {
            // Check if GitHub provider exists
            val existingGitHubProvider = identityProviderRepository.findByNameIgnoreCase("GitHub")
            
            if (existingGitHubProvider.isEmpty) {
                logger.info("Creating default GitHub OAuth provider")
                
                val githubProvider = IdentityProvider(
                    name = "GitHub",
                    type = IdentityProvider.ProviderType.OIDC,
                    clientId = System.getenv("GITHUB_CLIENT_ID") ?: "your-github-client-id",
                    clientSecret = System.getenv("GITHUB_CLIENT_SECRET") ?: "your-github-client-secret",
                    authorizationUrl = "https://github.com/login/oauth/authorize",
                    tokenUrl = "https://github.com/login/oauth/access_token",
                    userInfoUrl = "https://api.github.com/user",
                    scopes = "user:email",
                    enabled = true,
                    autoProvision = true,
                    buttonText = "Sign in with GitHub",
                    buttonColor = "#333333"
                )
                
                val saved = identityProviderRepository.save(githubProvider)
                logger.info("Created GitHub OAuth provider with ID: {}", saved.id)
                
                if (System.getenv("GITHUB_CLIENT_ID") == null || System.getenv("GITHUB_CLIENT_SECRET") == null) {
                    logger.warn("GitHub OAuth provider created with placeholder credentials. " +
                               "Please set GITHUB_CLIENT_ID and GITHUB_CLIENT_SECRET environment variables " +
                               "or update the provider configuration via the API.")
                }
            } else {
                logger.info("GitHub OAuth provider already exists with ID: {}", existingGitHubProvider.get().id)
            }
            
        } catch (e: Exception) {
            logger.error("Failed to initialize default providers: {}", e.message, e)
        }
    }
}