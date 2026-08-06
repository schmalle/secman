@file:Suppress("DEPRECATION")

package com.secman.security

import com.secman.repository.UserRepository
import io.micronaut.http.HttpRequest
import io.micronaut.security.authentication.AuthenticationRequest
import io.micronaut.security.authentication.AuthenticationResponse
import io.micronaut.security.authentication.provider.HttpRequestReactiveAuthenticationProvider
import jakarta.inject.Singleton
import org.reactivestreams.Publisher
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import reactor.core.publisher.Flux
import reactor.core.publisher.FluxSink

@Singleton
@Suppress("DEPRECATION")
class AuthenticationProviderUserPassword<B : Any>(
    private val userRepository: UserRepository
) : HttpRequestReactiveAuthenticationProvider<B> {

    private val passwordEncoder = BCryptPasswordEncoder()

    override fun authenticate(
        httpRequest: HttpRequest<B>?,
        authenticationRequest: AuthenticationRequest<String, String>
    ): Publisher<AuthenticationResponse> {
        return Flux.create { emitter: FluxSink<AuthenticationResponse> ->
            val username = authenticationRequest.identity
            val password = authenticationRequest.secret

            try {
                val userOptional = userRepository.findByUsername(username)
                
                if (userOptional.isPresent) {
                    val user = userOptional.get()
                    
                    if (passwordEncoder.matches(password, user.passwordHash)) {
                        // Extract roles as strings
                        val roles = user.roles.map { it.name }
                        
                        emitter.next(AuthenticationResponse.success(
                            username,
                            roles,
                            mapOf(
                                "userId" to user.id.toString(),
                                "email" to user.email
                            )
                        ))
                    } else {
                        emitter.error(AuthenticationResponse.exception("Invalid credentials"))
                    }
                } else {
                    // Use same error message as invalid password to prevent user enumeration
                    emitter.error(AuthenticationResponse.exception("Invalid credentials"))
                }
            } catch (e: Exception) {
                emitter.error(AuthenticationResponse.exception("Authentication error: ${e.message}"))
            }
            
            emitter.complete()
        }
    }
}
