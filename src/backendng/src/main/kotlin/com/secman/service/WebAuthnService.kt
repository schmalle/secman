package com.secman.service

import com.secman.domain.PasskeyCredential
import com.secman.domain.User
import com.secman.repository.PasskeyCredentialRepository
import com.webauthn4j.WebAuthnManager
import com.webauthn4j.converter.AttestedCredentialDataConverter
import com.webauthn4j.converter.util.ObjectConverter
import com.webauthn4j.credential.CredentialRecord
import com.webauthn4j.credential.CredentialRecordImpl
import com.webauthn4j.data.*
import com.webauthn4j.data.attestation.authenticator.AttestedCredentialData
import com.webauthn4j.data.client.Origin
import com.webauthn4j.data.client.challenge.Challenge
import com.webauthn4j.data.client.challenge.DefaultChallenge
import com.webauthn4j.server.ServerProperty
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory
import java.security.SecureRandom
import java.time.Instant
import java.util.*

/**
 * Service for WebAuthn/FIDO2 operations
 * Feature: Passkey MFA Support
 *
 * Handles passkey registration and authentication using WebAuthn4J 0.30.0
 */
@Singleton
class WebAuthnService(
    private val passkeyCredentialRepository: PasskeyCredentialRepository
) {
    private val logger = LoggerFactory.getLogger(WebAuthnService::class.java)
    private val webAuthnManager = WebAuthnManager.createNonStrictWebAuthnManager()
    private val objectConverter = ObjectConverter()
    private val attestedCredentialDataConverter = AttestedCredentialDataConverter(objectConverter)
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

            // Parse the registration response JSON
            val registrationDataJson = buildRegistrationResponseJson(registrationResponse)
            val registrationData = webAuthnManager.parseRegistrationResponseJSON(registrationDataJson)

            // Setup server property
            val serverProperty = ServerProperty.builder()
                .origin(Origin.create(ORIGIN))
                .rpId(RP_ID)
                .challenge(challenge)
                .build()

            // Define registration parameters
            val registrationParameters = RegistrationParameters(
                serverProperty,
                null, // pubKeyCredParams - null means accept any
                false, // userVerificationRequired
                true  // userPresenceRequired
            )

            // Verify registration
            webAuthnManager.verify(registrationData, registrationParameters)

            // Extract credential information
            val attestationObject = registrationData.attestationObject
            val authenticatorData = attestationObject?.authenticatorData
            val attestedCredentialData = authenticatorData?.attestedCredentialData
                ?: throw IllegalArgumentException("Attested credential data not found")

            val credentialIdBase64 = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(attestedCredentialData.credentialId)

            // Serialize the entire AttestedCredentialData (includes COSEKey/public key)
            val attestedCredentialDataBytes = attestedCredentialDataConverter.convert(attestedCredentialData)
            val attestedCredentialDataBase64 = Base64.getEncoder().encodeToString(attestedCredentialDataBytes)

            val aaguid = attestedCredentialData.aaguid?.toString()

            val credential = PasskeyCredential(
                user = user,
                credentialId = credentialIdBase64,
                publicKeyCose = attestedCredentialDataBase64, // Store full attested credential data
                signCount = authenticatorData.signCount,
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

            // Parse authentication response JSON
            val authenticationDataJson = buildAuthenticationResponseJson(authenticationResponse)
            val authenticationData = webAuthnManager.parseAuthenticationResponseJSON(authenticationDataJson)

            // Setup server property
            val serverProperty = ServerProperty.builder()
                .origin(Origin.create(ORIGIN))
                .rpId(RP_ID)
                .challenge(challenge)
                .build()

            // Deserialize the stored attested credential data
            val attestedCredentialDataBytes = Base64.getDecoder().decode(credential.publicKeyCose)
            val attestedCredentialData = attestedCredentialDataConverter.convert(attestedCredentialDataBytes)

            // Create credential record using CredentialRecordImpl
            // Using the extended constructor with all parameters
            val credentialRecord = CredentialRecordImpl(
                null, // attestationStatement
                null, // userPresent
                null, // userVerified
                null, // backupEligible
                credential.signCount, // signCount
                attestedCredentialData, // attestedCredentialData
                null, // authenticatorExtensions
                null, // clientData
                null, // clientExtensions
                null  // transports
            )

            // Define authentication parameters
            val authenticationParameters = AuthenticationParameters(
                serverProperty,
                credentialRecord,
                null, // allowCredentials - null means accept any
                false, // userVerificationRequired
                true  // userPresenceRequired
            )

            // Verify authentication
            webAuthnManager.verify(authenticationData, authenticationParameters)

            // Update sign count
            val newSignCount = authenticationData.authenticatorData?.signCount ?: 0L
            credential.signCount = newSignCount
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

    /**
     * Build registration response JSON for WebAuthn4J parsing
     */
    private fun buildRegistrationResponseJson(response: RegistrationCredentialResponse): String {
        return """
        {
            "id": "${response.id}",
            "rawId": "${response.rawId}",
            "response": {
                "clientDataJSON": "${response.response.clientDataJSON}",
                "attestationObject": "${response.response.attestationObject}",
                "transports": ${response.response.transports?.let { "[\"${it.joinToString("\", \"")}\"]" } ?: "[]"}
            },
            "type": "${response.type}"
        }
        """.trimIndent()
    }

    /**
     * Build authentication response JSON for WebAuthn4J parsing
     */
    private fun buildAuthenticationResponseJson(response: AuthenticationCredentialResponse): String {
        return """
        {
            "id": "${response.id}",
            "rawId": "${response.rawId}",
            "response": {
                "clientDataJSON": "${response.response.clientDataJSON}",
                "authenticatorData": "${response.response.authenticatorData}",
                "signature": "${response.response.signature}",
                "userHandle": ${response.response.userHandle?.let { "\"$it\"" } ?: "null"}
            },
            "type": "${response.type}"
        }
        """.trimIndent()
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
