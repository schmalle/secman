package com.secman.cli.service

import jakarta.inject.Singleton
import java.time.Duration

@Singleton
class CliJavaHttpClientFactory {
    fun create(insecureMode: Boolean): java.net.http.HttpClient {
        val builder = java.net.http.HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .followRedirects(java.net.http.HttpClient.Redirect.NORMAL)

        if (insecureMode) {
            builder.sslContext(createTrustAllSslContext())
            val sslParams = javax.net.ssl.SSLParameters()
            sslParams.endpointIdentificationAlgorithm = null
            builder.sslParameters(sslParams)
        }

        return builder.build()
    }

    private fun createTrustAllSslContext(): javax.net.ssl.SSLContext {
        val trustAllCerts = arrayOf<javax.net.ssl.TrustManager>(object : javax.net.ssl.X509TrustManager {
            override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
        })
        return javax.net.ssl.SSLContext.getInstance("TLS").apply {
            init(null, trustAllCerts, java.security.SecureRandom())
        }
    }
}
