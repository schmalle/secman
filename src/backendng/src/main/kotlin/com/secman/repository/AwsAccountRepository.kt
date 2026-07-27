package com.secman.repository

import com.secman.domain.AwsAccount
import io.micronaut.data.annotation.Repository
import io.micronaut.data.jpa.repository.JpaRepository
import java.util.Optional

@Repository
interface AwsAccountRepository : JpaRepository<AwsAccount, Long> {

    fun findByAwsAccountId(awsAccountId: String): Optional<AwsAccount>

    fun findByAwsAccountIdIn(awsAccountIds: Collection<String>): List<AwsAccount>
}
