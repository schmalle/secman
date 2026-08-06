package com.secman.domain

import io.micronaut.serde.annotation.Serdeable

/**
 * Defines the types of entities that can serve as the basis for a risk assessment.
 * Risk assessments can be conducted against either demands (new or changes to assets) 
 * or existing assets directly.
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
    ASSET
}