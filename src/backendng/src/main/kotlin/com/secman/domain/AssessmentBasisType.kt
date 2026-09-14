package com.secman.domain

import io.micronaut.serde.annotation.Serdeable

/**
 * Defines the types of entities that can serve as the basis for a risk assessment.
 * Risk assessments can be conducted against demands, existing assets, or AWS accounts.
 */
@Serdeable
enum class AssessmentBasisType {
    /**
     * Risk assessment is based on a demand (request for new asset or changes to existing asset)
     */
    DEMAND,
    
    /**
     * Risk assessment is based directly on an existing asset
     */
    ASSET,

    /**
     * Risk assessment is based directly on an AWS account, without a synthetic asset.
     */
    AWS_ACCOUNT
}
