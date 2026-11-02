package com.blindingwulf.randomimages

import android.content.Context
import android.util.Base64
import android.util.Log
import java.io.ByteArrayInputStream
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Builds the TLS trust anchors and installs them as the process default.
 *
 * Why this exists: FurAffinity serves a Let's Encrypt certificate whose root
 * (ISRG Root X1) is absent from Android's built-in store before 7.1.1, and the
 * cross-signed chain that used to cover older phones expired in September 2025.
 * e621 is issued under Google Trust Services, whose roots are likewise missing
 * on older releases. On an Android 5.x or 6.x phone both sites therefore fail
 * the handshake no matter which minSdk the APK declares, so the app carries its
 * own anchor set (res/raw/mozilla_cacert.pem) and validates against
 * device-roots OR bundled-roots.
 *
 * Deliberate limits:
 *  - Only added anchors are trusted for added hosts; the device store is never
 *    weakened or replaced, it is unioned with ours.
 *  - Hostname verification is left untouched (still HttpsURLConnection's
 *    default), so a valid-but-wrong-host certificate is still rejected.
 *  - No certificate-pinning bypass, no trust-all fallback anywhere.
 */
object TrustBundle {

    private const val TAG = "TrustBundle"
    private const val PEM_LIMIT = 2 * 1024 * 1024

    @Volatile
    private var installed = false

    /**
     * Installs the merged SSLContext as the default for HttpsURLConnection, so
     * every existing HttpURLConnection call benefits without per-call plumbing.
     * Must run off the main thread (PEM parsing of ~120 certificates).
     */
    @Synchronized
    fun install(context: Context) {
        if (installed) return
        val factory = try {
            val tm = compositeTrustManager(context)
            val ctx = SSLContext.getInstance("TLS")
            ctx.init(null, arrayOf<TrustManager>(tm), java.security.SecureRandom())
            ctx.socketFactory
        } catch (e: Exception) {
            Log.w(TAG, "Bundled trust anchors unavailable, keeping system default", e)
            null
        }
        if (factory != null) {
            HttpsURLConnection.setDefaultSSLSocketFactory(factory)
        }
        installed = true
    }

    /** Trusts a chain if the device store or the bundled bundle trusts it. */
    private fun compositeTrustManager(context: Context): X509TrustManager {
        val system = systemTrustManager()
        val bundled = loadBundledAnchors(context)
        val delegates = listOfNotNull(system).plus(
            if (bundled.isEmpty()) emptyList() else listOf(trustManagerFor(bundled))
        )
        return object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
                Unit

            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                var last: CertificateException? = null
                for (delegate in delegates) {
                    try {
                        delegate.checkServerTrusted(chain, authType)
                        return
                    } catch (e: CertificateException) {
                        last = e
                    }
                }
                throw last ?: CertificateException("No trust anchor accepted the chain")
            }

            override fun getAcceptedIssuers(): Array<X509Certificate> =
                delegates.flatMap { it.acceptedIssuers.toList() }.toTypedArray()
        }
    }

    private fun systemTrustManager(): X509TrustManager? = try {
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(null as KeyStore?)
        tmf.trustManagers.firstOrNull { it is X509TrustManager } as? X509TrustManager
    } catch (e: Exception) {
        Log.w(TAG, "Could not read the device trust store", e)
        null
    }

    private fun trustManagerFor(anchors: List<X509Certificate>): X509TrustManager {
        val ks = KeyStore.getInstance(KeyStore.getDefaultType())
        ks.load(null, null)
        anchors.forEachIndexed { i, cert -> ks.setCertificateEntry("bundled$i", cert) }
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(ks)
        return tmf.trustManagers.first { it is X509TrustManager } as X509TrustManager
    }

    /**
     * Splits the PEM bundle and parses each certificate, skipping any block the
     * platform cannot parse rather than failing the whole set.
     */
    private fun loadBundledAnchors(context: Context): List<X509Certificate> {
        val text = try {
            context.resources.openRawResource(R.raw.mozilla_cacert)
                .use { input -> input.readBytes().take(PEM_LIMIT).toByteArray().decodeToString() }
        } catch (e: Exception) {
            Log.w(TAG, "Bundled CA file missing", e)
            return emptyList()
        }
        val factory = CertificateFactory.getInstance("X.509")
        val out = ArrayList<X509Certificate>(128)
        var idx = 0
        while (true) {
            val begin = text.indexOf(BEGIN, idx)
            if (begin < 0) break
            val end = text.indexOf(END, begin)
            if (end < 0) break
            val body = text.substring(begin + BEGIN.length, end)
                .filterNot { it == '\n' || it == '\r' }
            idx = end + END.length
            if (body.isBlank()) continue
            try {
                val der = Base64.decode(body, Base64.DEFAULT)
                val cert = factory.generateCertificate(ByteArrayInputStream(der))
                if (cert is X509Certificate) out.add(cert)
            } catch (e: Exception) {
                Log.d(TAG, "Skipped unparsable bundled certificate: ${e.message}")
            }
        }
        Log.i(TAG, "Loaded ${out.size} bundled trust anchors")
        return out
    }

    private const val BEGIN = "-----BEGIN CERTIFICATE-----"
    private const val END = "-----END CERTIFICATE-----"
}
