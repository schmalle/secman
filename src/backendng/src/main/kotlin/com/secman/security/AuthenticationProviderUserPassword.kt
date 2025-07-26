package com.secman.security

import com.secman.repository.UserRepository
import io.micronaut.http.HttpRequest
import io.micronaut.security.authentication.AuthenticationProvider
import io.micronaut.security.authentication.AuthenticationRequest
import io.micronaut.security.authentication.AuthenticationResponse
import jakarta.inject.Singleton
import org.reactivestreams.Publisher
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import reactor.core.publisher.Flux
import reactor.core.publisher.FluxSink

@Singleton
class AuthenticationProviderUserPassword(
    private val userRepository: UserRepository
) : AuthenticationProvider<HttpRequest<*>> {

    private val passwordEncoder = BCryptPasswordEncoder()

    override fun authenticate(
        httpRequest: HttpRequest<*>?,
        authenticationRequest: AuthenticationRequest<*, *>
    ): Publisher<AuthenticationResponse> {
        return Flux.create { emitter: FluxSink<AuthenticationResponse> ->
            val username = authenticationRequest.identity.toString()
            val password = authenticationRequest.secret.toString()

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
                    emitter.error(AuthenticationResponse.exception("User not found"))
                }
            } catch (e: Exception) {
                emitter.error(AuthenticationResponse.exception("Authentication error: ${e.message}"))
            }
            
            emitter.complete()
        }
    }
}