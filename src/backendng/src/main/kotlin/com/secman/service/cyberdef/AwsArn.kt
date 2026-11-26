package com.secman.service.cyberdef

/**
 * The parts of an AWS ARN (`arn:partition:service:region:account:resource`) that ticket
 * validation needs. Parsing only; nothing here is looked up or fetched.
 */
data class AwsArn(val partition: String, val service: String, val region: String, val account: String, val resource: String) {

    /**
     * A resource type such as `EC2_INSTANCE` or `RDS_SNAPSHOT`: the service plus the leading
     * resource-type segment. ARNs without one get a well-known name or just the service.
     */
    fun resourceType(): String {
        val segment = resource.substringBefore('/').substringBefore(':').takeIf { resource.contains('/') || resource.contains(':') }
        val raw = when {
            segment != null -> "${service}_$segment"
            service in BARE_RESOURCE_NAMES -> BARE_RESOURCE_NAMES.getValue(service)
            else -> service
        }
        return raw.uppercase().replace(Regex("[^A-Z0-9]+"), "_").trim('_').take(32)
    }

    companion object {
        private val PARTITIONS = setOf("aws", "aws-cn", "aws-us-gov", "aws-iso", "aws-iso-b")
        private val SERVICE = Regex("^[a-z0-9-]{1,64}$")
        private val REGION = Regex("^([a-z0-9-]{1,32})?$")
        private val ACCOUNT = Regex("^([0-9]{12}|aws)?$")
        private val BARE_RESOURCE_NAMES = mapOf("s3" to "S3_BUCKET", "sns" to "SNS_TOPIC", "sqs" to "SQS_QUEUE")

        /** The ARN, or null when [value] is not one. */
        fun parse(value: String): AwsArn? {
            if (!value.startsWith("arn:")) return null
            val parts = value.split(':', limit = 6)
            if (parts.size < 6) return null
            val (_, partition, service, region, account) = parts
            val resource = parts[5]
            val wellFormed = partition in PARTITIONS && SERVICE.matches(service) && REGION.matches(region) &&
                ACCOUNT.matches(account) && resource.isNotBlank()
            return if (wellFormed) AwsArn(partition, service, region, account.takeIf { it != "aws" }.orEmpty(), resource) else null
        }
    }
}
