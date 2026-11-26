package com.secman.service.cyberdef

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Unit tests for AwsArn parsing and type derivation. */
class AwsArnTest {
    @Test
    fun `common ARNs yield account, region and a resource type`() {
        val cases = mapOf(
            "arn:aws:ec2:eu-central-1:123456789012:instance/i-0abc" to Triple("123456789012", "eu-central-1", "EC2_INSTANCE"),
            "arn:aws:ec2:eu-central-1:123456789012:security-group/sg-1" to Triple("123456789012", "eu-central-1", "EC2_SECURITY_GROUP"),
            "arn:aws:rds:eu-west-1:123456789012:snapshot:db-1" to Triple("123456789012", "eu-west-1", "RDS_SNAPSHOT"),
            "arn:aws:rds:eu-west-1:123456789012:db:prod" to Triple("123456789012", "eu-west-1", "RDS_DB"),
            "arn:aws:iam::123456789012:role/Admin" to Triple("123456789012", "", "IAM_ROLE"),
            "arn:aws:s3:::prod-exports" to Triple("", "", "S3_BUCKET"),
            "arn:aws:sns:us-east-1:123456789012:alerts" to Triple("123456789012", "us-east-1", "SNS_TOPIC"),
            "arn:aws-us-gov:lambda:us-gov-west-1:123456789012:function:f" to Triple("123456789012", "us-gov-west-1", "LAMBDA_FUNCTION")
        )
        cases.forEach { (value, expected) ->
            val arn = AwsArn.parse(value)!!
            assertThat(Triple(arn.account, arn.region, arn.resourceType())).describedAs(value).isEqualTo(expected)
        }
    }

    @Test
    fun `malformed or foreign strings are not ARNs`() {
        listOf("i-0abc", "arn:aws:ec2", "arn:evil:ec2:eu-central-1:123456789012:x", "arn:aws:EC2:x:123456789012:i",
            "arn:aws:ec2:eu-central-1:12345:instance/i", "arn:aws:ec2:eu-central-1:123456789012:").forEach {
            assertThat(AwsArn.parse(it)).describedAs(it).isNull()
        }
    }
}
