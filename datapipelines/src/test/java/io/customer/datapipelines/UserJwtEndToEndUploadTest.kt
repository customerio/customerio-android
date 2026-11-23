package io.customer.datapipelines

import com.segment.analytics.kotlin.core.Analytics
import com.segment.analytics.kotlin.core.Configuration
import io.customer.datapipelines.network.UserJwtRequestFactory
import io.customer.sdk.core.identity.UserJwtStore
import java.io.File
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import java.util.Properties
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * Uploads real events through analytics-kotlin and the SDK's [UserJwtRequestFactory] to a CDP edge
 * running locally over TLS (cdp's TestUserJwtE2EServer). It runs only when
 * `build/user-jwt-e2e.properties` names the server, the truststore that trusts its certificate,
 * the private signing key and the file the server writes queued events to. The assertions on what
 * the edge queued run in the harness script, which reads that file.
 */
class UserJwtEndToEndUploadTest {

    @Test
    fun uploadThroughTheRealEdge() {
        val configFile = File("build/user-jwt-e2e.properties")
        assumeTrue(configFile.exists(), "no end-to-end server configured")
        val config = Properties().apply { configFile.inputStream().use { load(it) } }
        System.setProperty("javax.net.ssl.trustStore", config.getProperty("truststore"))
        System.setProperty("javax.net.ssl.trustStorePassword", "changeit")
        System.setProperty("javax.net.ssl.trustStoreType", "PKCS12")

        Analytics.debugLogsEnabled = true
        Analytics.setLogger(object : com.segment.analytics.kotlin.core.platform.plugins.logger.Logger {
            override fun parseLog(log: com.segment.analytics.kotlin.core.platform.plugins.logger.LogMessage) {
                println("analytics: ${log.message}")
            }
        })
        val store = UserJwtStore()
        val analytics = Analytics(
            Configuration(
                writeKey = "e2e-write-key",
                // Any non-null application keeps events in files under /tmp on the JVM.
                application = "e2e",
                apiHost = config.getProperty("apiHost"),
                cdnHost = "127.0.0.1:1/v1",
                flushAt = 100,
                flushInterval = 3600,
                requestFactory = UserJwtRequestFactory { store.current?.token },
                defaultSettings = com.segment.analytics.kotlin.core.Settings(
                    integrations = kotlinx.serialization.json.buildJsonObject {
                        put("Segment.io", kotlinx.serialization.json.buildJsonObject { put("apiKey", kotlinx.serialization.json.JsonPrimitive("e2e-write-key")) })
                    }
                )
            )
        )

        // The app signs alex in and hands over alex's token.
        analytics.identify("alex")
        // analytics-kotlin sets the userId on an event when it processes it, not when it is
        // tracked, so wait for each identify to land before tracking as that user.
        Thread.sleep(1_000)
        store.set(UserJwtStore.Entry("alex", sign(config.getProperty("signingKey"), uid = "alex", cioId = "1a2b3c")))
        analytics.track("Device Created or Updated", kotlinx.serialization.json.buildJsonObject {})
        analytics.track("Viewed Item", kotlinx.serialization.json.buildJsonObject {})
        Thread.sleep(1_000)

        // Someone switches to mallory without a token for mallory: alex's token can't vouch for them.
        analytics.identify("mallory")
        Thread.sleep(1_000)
        analytics.track("Device Created or Updated", kotlinx.serialization.json.buildJsonObject {})
        analytics.track("Viewed Item", kotlinx.serialization.json.buildJsonObject {})

        // Events reach the upload queue asynchronously, after the library starts; flush until the
        // server has written what it queued.
        val queued = File(config.getProperty("queued"))
        val deadline = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < deadline && queued.readLines().size < 4) {
            analytics.flush()
            Thread.sleep(2_000)
        }
    }

    private fun sign(privateKeyPem: String, uid: String, cioId: String): String {
        val der = Base64.getMimeDecoder().decode(
            File(privateKeyPem).readText().lines().filterNot { it.startsWith("-----") }.joinToString("")
        )
        val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(der))
        val b64 = Base64.getUrlEncoder().withoutPadding()
        val exp = System.currentTimeMillis() / 1000 + 600
        val input = b64.encodeToString("""{"alg":"RS256","typ":"JWT"}""".toByteArray()) + "." +
            b64.encodeToString("""{"sub":"cio_$cioId","uid":"$uid","exp":$exp}""".toByteArray())
        val signature = Signature.getInstance("SHA256withRSA").run {
            initSign(key)
            update(input.toByteArray())
            sign()
        }
        return "$input.${b64.encodeToString(signature)}"
    }
}
