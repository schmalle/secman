package com.secman.service

import com.secman.domain.PasskeyCredential
import com.secman.domain.User
import com.secman.repository.PasskeyCredentialRepository
import com.webauthn4j.WebAuthnManager
import com.webauthn4j.authenticator.Authenticator
import com.webauthn4j.authenticator.AuthenticatorImpl
import com.webauthn4j.converter.util.ObjectConverter
import com.webauthn4j.data.*
import com.webauthn4j.data.attestation.statement.COSEAlgorithmIdentifier
import com.webauthn4j.data.client.Origin
import com.webauthn4j.data.client.challenge.Challenge
import com.webauthn4j.data.client.challenge.DefaultChallenge
import com.webauthn4j.server.ServerProperty
import com.webauthn4j.validator.WebAuthnRegistrationContextValidator
import com.webauthn4j.validator.WebAuthnAuthenticationContextValidator
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory
import java.security.SecureRandom
import java.time.Instant
import java.util.*

/**
 * Service for WebAuthn/FIDO2 operations
 * Feature: Passkey MFA Support
 *
 * Handles passkey registration and authentication using WebAuthn4J
 */
@Singleton
class WebAuthnService(
    private val passkeyCredentialRepository: PasskeyCredentialRepository
) {
    private val logger = LoggerFactory.getLogger(WebAuthnService::class.java)
    private val webAuthnManager = WebAuthnManager.createNonStrictWebAuthnManager()
    private val objectConverter = ObjectConverter()
    private val secureRandom = SecureRandom()

    // Store challenges temporarily (in production, use Redis or cache)
    private val challengeStore = mutableMapOf<String, Challenge>()

    companion object {
        private const val RP_NAME = "SecMan"
        private const val RP_ID = "localhost" // Should be configurable per environment
        private const val ORIGIN = "http://localhost:4321" // Should be configurable per environment
        private const val CHALLENGE_TIMEOUT_MS = 120000L // 2 minutes
    }

    /**
     * Generate registration options for WebAuthn
     */
    fun generateRegistrationOptions(user: User): RegistrationOptionsResponse {
        val challenge = generateChallenge()
        val challengeBase64 = Base64.getUrlEncoder().withoutPadding().encodeToString(challenge.value)

        // Store challenge with user ID
        challengeStore[user.id.toString()] = challenge

        val userIdBytes = user.id.toString().toByteArray()
        val userIdBase64 = Base64.getUrlEncoder().withoutPadding().encodeToString(userIdBytes)

        return RegistrationOptionsResponse(
            challenge = challengeBase64,
            rp = RelyingPartyInfo(
                name = RP_NAME,
                id = RP_ID
            ),
            user = UserInfo(
                id = userIdBase64,
                name = user.username,
                displayName = user.email
            ),
            pubKeyCredParams = listOf(
                PubKeyCredParam(type = "public-key", alg = -7), // ES256
                PubKeyCredParam(type = "public-key", alg = -257) // RS256
            ),
            timeout = CHALLENGE_TIMEOUT_MS,
            authenticatorSelection = AuthenticatorSelectionCriteria(
                authenticatorAttachment = "platform",
                requireResidentKey = true,
                residentKey = "required",
                userVerification = "required"
            ),
            attestation = "none",
            excludeCredentials = getExistingCredentials(user)
        )
    }

    /**
     * Verify and register a new passkey credential
     */
    fun registerCredential(
        user: User,
        credentialName: String,
        registrationResponse: RegistrationCredentialResponse
    ): PasskeyCredential {
        try {
            // Retrieve stored challenge
            val challenge = challengeStore.remove(user.id.toString())
                ?: throw IllegalArgumentException("Challenge not found or expired")

            // Decode the registration response
            val attestationObjectBytes = Base64.getUrlDecoder().decode(registrationResponse.response.attestationObject)
            val clientDataJSONBytes = Base64.getUrlDecoder().decode(registrationResponse.response.clientDataJSON)

            // Create WebAuthn registration context
            val registrationData = RegistrationData(
                attestationObjectBytes,
                clientDataJSONBytes,
                registrationResponse.response.transports,
                registrationResponse.response.clientExtensionResults
            )

            val registrationParameters = RegistrationParameters(
                ServerProperty(Origin.create(ORIGIN), RP_ID, challenge, null),
                null, // pubKeyCredParams
                false, // userVerificationRequired
                false // userPresenceRequired
            )

            // Validate registration
            val registrationContext = com.webauthn4j.data.RegistrationRequest(
                attestationObjectBytes,
                clientDataJSONBytes
            )

            val validator = WebAuthnRegistrationContextValidator()
            val validationData = validator.validate(
                com.webauthn4j.data.RegistrationContext(
                    registrationContext,
                    registrationParameters.serverProperty,
                    registrationParameters.pubKeyCredParams != null
                )
            )

            // Create and save credential
            val credentialIdBase64 = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(validationData.attestationObject.authenticatorData.attestedCredentialData?.credentialId)

            val publicKeyBytes = validationData.attestationObject.authenticatorData.attestedCredentialData?.coseKey?.encode()
                ?: throw IllegalArgumentException("Public key not found")
            val publicKeyBase64 = Base64.getEncoder().encodeToString(publicKeyBytes)

            val aaguid = validationData.attestationObject.authenticatorData.attestedCredentialData?.aaguid?.toString()

            val credential = PasskeyCredential(
                user = user,
                credentialId = credentialIdBase64,
                publicKeyCose = publicKeyBase64,
                signCount = validationData.attestationObject.authenticatorData.signCount,
                aaguid = aaguid,
                credentialName = credentialName,
                transports = registrationResponse.response.transports?.joinToString(",")
            )

            return passkeyCredentialRepository.save(credential)

        } catch (e: Exception) {
            logger.error("Failed to register passkey credential", e)
            throw IllegalArgumentException("Invalid registration response: ${e.message}")
        }
    }

    /**
     * Generate authentication options for WebAuthn
     */
    fun generateAuthenticationOptions(username: String): AuthenticationOptionsResponse {
        val challenge = generateChallenge()
        val challengeBase64 = Base64.getUrlEncoder().withoutPadding().encodeToString(challenge.value)

        // Store challenge with username
        challengeStore[username] = challenge

        return AuthenticationOptionsResponse(
            challenge = challengeBase64,
            timeout = CHALLENGE_TIMEOUT_MS,
            rpId = RP_ID,
            userVerification = "required",
            allowCredentials = emptyList() // Allow any credential (discoverable)
        )
    }

    /**
     * Verify authentication response and return the user
     */
    fun verifyAuthentication(
        username: String,
        authenticationResponse: AuthenticationCredentialResponse
    ): PasskeyCredential {
        try {
            // Retrieve stored challenge
            val challenge = challengeStore.remove(username)
                ?: throw IllegalArgumentException("Challenge not found or expired")

            // Find credential by ID
            val credentialIdBytes = Base64.getUrlDecoder().decode(authenticationResponse.id)
            val credentialIdBase64 = Base64.getUrlEncoder().withoutPadding().encodeToString(credentialIdBytes)

            val credential = passkeyCredentialRepository.findByCredentialId(credentialIdBase64)
                .orElseThrow { IllegalArgumentException("Credential not found") }

            // Decode response
            val authenticatorDataBytes = Base64.getUrlDecoder().decode(authenticationResponse.response.authenticatorData)
            val clientDataJSONBytes = Base64.getUrlDecoder().decode(authenticationResponse.response.clientDataJSON)
            val signatureBytes = Base64.getUrlDecoder().decode(authenticationResponse.response.signature)

            // Create authenticator
            val publicKeyBytes = Base64.getDecoder().decode(credential.publicKeyCose)
            val coseKey = objectConverter.cborConverter.readValue(publicKeyBytes, com.webauthn4j.data.attestation.authenticator.COSEKey::class.java)

            val authenticator = AuthenticatorImpl(
                coseKey.toAttestedCredentialData(),
                null, // attestationStatement
                credential.signCount
            )

            // Validate authentication
            val authenticationData = AuthenticationData(
                credentialIdBytes,
                null, // userHandle
                authenticatorDataBytes,
                clientDataJSONBytes,
                signatureBytes,
                authenticationResponse.response.clientExtensionResults
            )

            val authenticationParameters = AuthenticationParameters(
                ServerProperty(Origin.create(ORIGIN), RP_ID, challenge, null),
                authenticator,
                null, // allowCredentials
                false, // userVerificationRequired
                false // userPresenceRequired
            )

            val authenticationContext = com.webauthn4j.data.AuthenticationRequest(
                credentialIdBytes,
                null, // userHandle
                authenticatorDataBytes,
                clientDataJSONBytes,
                signatureBytes
            )

            val validator = WebAuthnAuthenticationContextValidator()
            val validationData = validator.validate(
                com.webauthn4j.data.AuthenticationContext(
                    authenticationContext,
                    authenticationParameters.serverProperty,
                    authenticationParameters.authenticator,
                    authenticationParameters.userVerificationRequired
                )
            )

            // Update sign count
            credential.signCount = validationData.authenticatorData.signCount
            credential.lastUsedAt = Instant.now()
            passkeyCredentialRepository.update(credential)

            return credential

        } catch (e: Exception) {
            logger.error("Failed to verify authentication", e)
            throw IllegalArgumentException("Invalid authentication response: ${e.message}")
        }
    }

    /**
     * Get all passkeys for a user
     */
    fun getUserPasskeys(user: User): List<PasskeyCredentialInfo> {
        return passkeyCredentialRepository.findByUserId(user.id!!)
            .map { credential ->
                PasskeyCredentialInfo(
                    id = credential.id!!,
                    credentialName = credential.credentialName,
                    createdAt = credential.createdAt!!.toString(),
                    lastUsedAt = credential.lastUsedAt?.toString()
                )
            }
    }

    /**
     * Delete a passkey credential
     */
    fun deletePasskey(user: User, credentialId: Long): Boolean {
        val credential = passkeyCredentialRepository.findById(credentialId).orElse(null) ?: return false

        // Verify ownership
        if (credential.user.id != user.id) {
            throw IllegalArgumentException("Unauthorized to delete this credential")
        }

        passkeyCredentialRepository.delete(credential)
        return true
    }

    private fun generateChallenge(): Challenge {
        val bytes = ByteArray(32)
        secureRandom.nextBytes(bytes)
        return DefaultChallenge(bytes)
    }

    private fun getExistingCredentials(user: User): List<PublicKeyCredentialDescriptor> {
        return passkeyCredentialRepository.findByUserId(user.id!!)
            .map { credential ->
                val idBytes = Base64.getUrlDecoder().decode(credential.credentialId)
                PublicKeyCredentialDescriptor(
                    type = "public-key",
                    id = Base64.getUrlEncoder().withoutPadding().encodeToString(idBytes),
                    transports = credential.transports?.split(",")
                )
            }
    }

    // Data classes for API responses
    data class RegistrationOptionsResponse(
        val challenge: String,
        val rp: RelyingPartyInfo,
        val user: UserInfo,
        val pubKeyCredParams: List<PubKeyCredParam>,
        val timeout: Long,
        val authenticatorSelection: AuthenticatorSelectionCriteria,
        val attestation: String,
        val excludeCredentials: List<PublicKeyCredentialDescriptor>
    )

    data class RelyingPartyInfo(
        val name: String,
        val id: String
    )

    data class UserInfo(
        val id: String,
        val name: String,
        val displayName: String
    )

    data class PubKeyCredParam(
        val type: String,
        val alg: Int
    )

    data class AuthenticatorSelectionCriteria(
        val authenticatorAttachment: String,
        val requireResidentKey: Boolean,
        val residentKey: String,
        val userVerification: String
    )

    data class PublicKeyCredentialDescriptor(
        val type: String,
        val id: String,
        val transports: List<String>?
    )

    data class RegistrationCredentialResponse(
        val id: String,
        val rawId: String,
        val type: String,
        val response: AttestationResponse
    )

    data class AttestationResponse(
        val clientDataJSON: String,
        val attestationObject: String,
        val transports: List<String>?,
        val clientExtensionResults: String?
    )

    data class AuthenticationOptionsResponse(
        val challenge: String,
        val timeout: Long,
        val rpId: String,
        val userVerification: String,
        val allowCredentials: List<PublicKeyCredentialDescriptor>
    )

    data class AuthenticationCredentialResponse(
        val id: String,
        val rawId: String,
        val type: String,
        val response: AssertionResponse
    )

    data class AssertionResponse(
        val clientDataJSON: String,
        val authenticatorData: String,
        val signature: String,
        val userHandle: String?,
        val clientExtensionResults: String?
    )

    data class PasskeyCredentialInfo(
        val id: Long,
        val credentialName: String,
        val createdAt: String,
        val lastUsedAt: String?
    )
}

// Extension function to create AttestedCredentialData from COSEKey
private fun com.webauthn4j.data.attestation.authenticator.COSEKey.toAttestedCredentialData(): com.webauthn4j.data.attestation.authenticator.AttestedCredentialData {
    // Create a minimal AttestedCredentialData for authentication
    // In a real implementation, you'd need to store and retrieve the full AttestedCredentialData
    return com.webauthn4j.data.attestation.authenticator.AttestedCredentialData(
        com.webauthn4j.data.attestation.authenticator.AAGUID.ZERO,
        ByteArray(16), // Placeholder credential ID
        this
    )
}
