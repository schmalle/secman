package com.secman.service

import com.secman.domain.User
import com.secman.repository.DemandRepository
import com.secman.repository.RequirementFileRepository
import com.secman.repository.RiskAssessmentRepository
import com.secman.repository.UserRepository
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.Optional

/**
 * Only references the database cannot detach may block a user deletion; nullable ones are
 * nulled by [UserService.deleteUser] and must not keep a user alive forever.
 */
class UserDeletionValidatorTest {

    private val userRepository = mockk<UserRepository>()
    private val demandRepository = mockk<DemandRepository>()
    private val riskAssessmentRepository = mockk<RiskAssessmentRepository>()
    private val requirementFileRepository = mockk<RequirementFileRepository>()

    private val validator = UserDeletionValidator(
        userRepository, demandRepository, riskAssessmentRepository, requirementFileRepository
    )

    private val userId = 42L

    @BeforeEach
    fun noReferences() {
        every { userRepository.findById(userId) } returns Optional.of(user(User.Role.USER))
        every { userRepository.countByRolesContaining(User.Role.ADMIN) } returns 2
        every { userRepository.countIntegrationScannersByServiceUser(userId) } returns 0
        every { demandRepository.findByRequestorId(userId) } returns emptyList()
        every { riskAssessmentRepository.findByAssessorId(userId) } returns emptyList()
        every { riskAssessmentRepository.findByRequestorId(userId) } returns emptyList()
        every { requirementFileRepository.countByUploadedById(userId) } returns 0
    }

    @Test
    fun `respondent, release creator, approver and classification references no longer block`() {
        // The validator no longer even consults those repositories; a clean user is deletable.
        val result = validator.validateUserDeletion(userId)

        assertThat(result.canDelete).isTrue()
        assertThat(result.blockingReferences).isEmpty()
    }

    @Test
    fun `requirement file uploader blocks`() {
        every { requirementFileRepository.countByUploadedById(userId) } returns 3

        val result = validator.validateUserDeletion(userId)

        assertThat(result.canDelete).isFalse()
        assertThat(result.blockingReferences.map { it.entityType to it.role })
            .containsExactly("RequirementFile" to "uploader")
        assertThat(result.blockingReferences.single().count).isEqualTo(3)
    }

    @Test
    fun `integration scanner service user blocks`() {
        every { userRepository.countIntegrationScannersByServiceUser(userId) } returns 1

        val result = validator.validateUserDeletion(userId)

        assertThat(result.blockingReferences.map { it.entityType to it.role })
            .containsExactly("IntegrationScanner" to "service_user")
    }

    @Test
    fun `last admin still blocks`() {
        every { userRepository.findById(userId) } returns Optional.of(user(User.Role.ADMIN))
        every { userRepository.countByRolesContaining(User.Role.ADMIN) } returns 1

        val result = validator.validateUserDeletion(userId)

        assertThat(result.blockingReferences.map { it.entityType to it.role })
            .containsExactly("SystemConstraint" to "last_admin")
    }

    @Test
    fun `assessor and requestor still block`() {
        every { riskAssessmentRepository.findByAssessorId(userId) } returns listOf(mockk())
        every { riskAssessmentRepository.findByRequestorId(userId) } returns listOf(mockk(), mockk())
        every { demandRepository.findByRequestorId(userId) } returns listOf(mockk())

        val result = validator.validateUserDeletion(userId)

        assertThat(result.blockingReferences.map { it.entityType to it.role }).containsExactlyInAnyOrder(
            "RiskAssessment" to "assessor",
            "RiskAssessment" to "requestor",
            "Demand" to "requestor"
        )
    }

    private fun user(role: User.Role) = User(
        id = userId, username = "u", email = "u@secman.test", passwordHash = "x",
        roles = mutableSetOf(User.Role.USER, role)
    )
}
