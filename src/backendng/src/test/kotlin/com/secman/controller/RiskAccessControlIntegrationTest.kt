package com.secman.controller

import com.secman.domain.AssessmentBasisType
import com.secman.domain.Risk
import com.secman.domain.RiskAssessment
import com.secman.domain.User
import com.secman.domain.Workgroup
import com.secman.repository.AssetRepository
import com.secman.repository.RiskAssessmentRepository
import com.secman.repository.RiskRepository
import com.secman.repository.UserRepository
import com.secman.repository.WorkgroupRepository
import com.secman.testutil.BaseIntegrationTest
import com.secman.testutil.TestAuthHelper
import com.secman.testutil.TestDataFactory
import io.micronaut.core.type.Argument
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpStatus
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.http.client.exceptions.HttpClientResponseException
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import io.micronaut.transaction.TransactionOperations
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.time.LocalDate

/**
 * SECURITY (A01) regression coverage for RiskController / RiskAssessmentController.
 *
 * A RISK-role user with no workgroup membership, ownership or mapping for an asset must
 * not be able to read, list, link-to, or otherwise act on a Risk/RiskAssessment linked to
 * that asset — matching the Unified Asset Access model (see project CLAUDE.md) and the
 * fix already applied to DemandController for the same bug class.
 */
@MicronautTest(environments = ["test"], transactional = false)
class RiskAccessControlIntegrationTest : BaseIntegrationTest() {

    @Inject
    @field:Client("/")
    lateinit var client: HttpClient

    @Inject
    lateinit var userRepository: UserRepository

    @Inject
    lateinit var workgroupRepository: WorkgroupRepository

    @Inject
    lateinit var assetRepository: AssetRepository

    @Inject
    lateinit var riskRepository: RiskRepository

    @Inject
    lateinit var riskAssessmentRepository: RiskAssessmentRepository

    @Inject
    lateinit var transactionOperations: TransactionOperations<Connection>

    private lateinit var riskUser: User

    @BeforeEach
    fun setUp() {
        val suffix = System.nanoTime()
        riskUser = userRepository.save(TestDataFactory.createUserWithRoles(
            "risk-user-$suffix", "risk-user-$suffix@test.com", User.Role.USER, User.Role.RISK
        ))
    }

    private fun assignUserToWorkgroup(userId: Long, workgroupId: Long) {
        transactionOperations.executeWrite<Unit> { status ->
            status.connection.prepareStatement("INSERT INTO user_workgroups (user_id, workgroup_id) VALUES (?, ?)").use { ps ->
                ps.setLong(1, userId)
                ps.setLong(2, workgroupId)
                ps.executeUpdate()
            }
        }
    }

    private fun assignAssetToWorkgroup(assetId: Long, workgroupId: Long) {
        transactionOperations.executeWrite<Unit> { status ->
            status.connection.prepareStatement("INSERT INTO asset_workgroups (asset_id, workgroup_id) VALUES (?, ?)").use { ps ->
                ps.setLong(1, assetId)
                ps.setLong(2, workgroupId)
                ps.executeUpdate()
            }
        }
    }

    @Test
    fun `RISK user cannot list, read or delete a risk linked to an inaccessible asset`() {
        val suffix = System.nanoTime()
        val inaccessibleAsset = assetRepository.save(TestDataFactory.createAsset(name = "risk-hidden-asset-$suffix"))
        val hiddenRisk = riskRepository.save(
            Risk(name = "Hidden risk $suffix", asset = inaccessibleAsset)
        )
        val token = TestAuthHelper.getAuthToken(client, riskUser.username)

        val risks = client.toBlocking().retrieve(
            HttpRequest.GET<Any>("/api/risks").bearerAuth(token),
            Argument.listOf(Risk::class.java)
        )
        assertThat(risks.map { it.id }).doesNotContain(hiddenRisk.id)

        val getException = org.junit.jupiter.api.assertThrows<HttpClientResponseException> {
            client.toBlocking().exchange(
                HttpRequest.GET<Any>("/api/risks/${hiddenRisk.id}").bearerAuth(token),
                String::class.java
            )
        }
        assertThat(getException.status).isEqualTo(HttpStatus.NOT_FOUND)

        val deleteException = org.junit.jupiter.api.assertThrows<HttpClientResponseException> {
            client.toBlocking().exchange(
                HttpRequest.DELETE<Any>("/api/risks/${hiddenRisk.id}").bearerAuth(token),
                String::class.java
            )
        }
        assertThat(deleteException.status).isEqualTo(HttpStatus.NOT_FOUND)

        // Confirm the risk was NOT deleted by the denied request above.
        assertThat(riskRepository.findById(hiddenRisk.id!!)).isPresent
    }

    @Test
    fun `RISK user can list and read a risk linked to a workgroup-accessible asset`() {
        val suffix = System.nanoTime()
        val workgroup = workgroupRepository.save(Workgroup(name = "Risk WG $suffix"))
        val accessibleAsset = assetRepository.save(TestDataFactory.createAsset(name = "risk-visible-asset-$suffix"))
        assignUserToWorkgroup(riskUser.id!!, workgroup.id!!)
        assignAssetToWorkgroup(accessibleAsset.id!!, workgroup.id!!)
        val visibleRisk = riskRepository.save(
            Risk(name = "Visible risk $suffix", asset = accessibleAsset)
        )
        val token = TestAuthHelper.getAuthToken(client, riskUser.username)

        val risks = client.toBlocking().retrieve(
            HttpRequest.GET<Any>("/api/risks").bearerAuth(token),
            Argument.listOf(Risk::class.java)
        )
        assertThat(risks.map { it.id }).contains(visibleRisk.id)

        val getResponse = client.toBlocking().exchange(
            HttpRequest.GET<Any>("/api/risks/${visibleRisk.id}").bearerAuth(token),
            Risk::class.java
        )
        assertThat(getResponse.status).isEqualTo(HttpStatus.OK)
        assertThat(getResponse.body()!!.id).isEqualTo(visibleRisk.id)
    }

    @Test
    fun `RISK user cannot create a risk linked to an inaccessible assetId`() {
        val suffix = System.nanoTime()
        val inaccessibleAsset = assetRepository.save(TestDataFactory.createAsset(name = "risk-create-hidden-$suffix"))
        val token = TestAuthHelper.getAuthToken(client, riskUser.username)

        val exception = org.junit.jupiter.api.assertThrows<HttpClientResponseException> {
            client.toBlocking().exchange(
                HttpRequest.POST(
                    "/api/risks",
                    RiskController.CreateRiskRequest(name = "Should not link $suffix", assetId = inaccessibleAsset.id)
                ).bearerAuth(token),
                String::class.java
            )
        }
        assertThat(exception.status).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(riskRepository.findByNameContainingIgnoreCase("Should not link $suffix")).isEmpty()
    }

    @Test
    fun `RISK user cannot list or read an asset-based risk assessment linked to an inaccessible asset`() {
        val suffix = System.nanoTime()
        val inaccessibleAsset = assetRepository.save(TestDataFactory.createAsset(name = "ra-hidden-asset-$suffix"))
        val hiddenAssessment = riskAssessmentRepository.save(
            RiskAssessment(
                startDate = LocalDate.now(),
                endDate = LocalDate.now().plusDays(30),
                assessmentBasisType = AssessmentBasisType.ASSET,
                assessmentBasisId = inaccessibleAsset.id!!,
                asset = inaccessibleAsset,
                assessor = riskUser,
                requestor = riskUser
            )
        )
        val token = TestAuthHelper.getAuthToken(client, riskUser.username)

        val assessments = client.toBlocking().retrieve(
            HttpRequest.GET<Any>("/api/risk-assessments").bearerAuth(token),
            Argument.listOf(RiskAssessment::class.java)
        )
        assertThat(assessments.map { it.id }).doesNotContain(hiddenAssessment.id)

        val getException = org.junit.jupiter.api.assertThrows<HttpClientResponseException> {
            client.toBlocking().exchange(
                HttpRequest.GET<Any>("/api/risk-assessments/${hiddenAssessment.id}").bearerAuth(token),
                String::class.java
            )
        }
        assertThat(getException.status).isEqualTo(HttpStatus.NOT_FOUND)
    }

    @Test
    fun `RISK user cannot create an asset-based risk assessment for an inaccessible asset`() {
        val suffix = System.nanoTime()
        val inaccessibleAsset = assetRepository.save(TestDataFactory.createAsset(name = "ra-create-hidden-$suffix"))
        val token = TestAuthHelper.getAuthToken(client, riskUser.username)

        val exception = org.junit.jupiter.api.assertThrows<HttpClientResponseException> {
            client.toBlocking().exchange(
                HttpRequest.POST(
                    "/api/risk-assessments",
                    RiskAssessmentController.CreateRiskAssessmentRequest(
                        assessorId = riskUser.id,
                        endDate = LocalDate.now().plusDays(30),
                        assetId = inaccessibleAsset.id
                    )
                ).bearerAuth(token),
                String::class.java
            )
        }
        assertThat(exception.status).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(riskAssessmentRepository.findByAssetId(inaccessibleAsset.id!!)).isEmpty()
    }
}
