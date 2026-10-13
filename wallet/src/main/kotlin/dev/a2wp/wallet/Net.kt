package dev.a2wp.wallet

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import okhttp3.Dns
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.asResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.Proxy
import java.net.UnknownHostException
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.security.cert.CertPathValidator
import java.security.cert.CertificateFactory
import java.security.cert.PKIXParameters
import java.security.cert.TrustAnchor
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/** Configured trust anchors (the mkcert root in dev). Used for TLS, verifier x5c and issuer x5c. */
class TrustAnchors(val certificates: List<X509Certificate>) {

    init {
        require(certificates.isNotEmpty()) { "no trust anchors" }
    }

    /** PKIX-validates an x5c chain (leaf first) against the anchors. Revocation is not checked (dev CA has none). */
    fun validate(chain: List<X509Certificate>): Boolean {
        if (chain.isEmpty()) return false
        return try {
            val path = chain.filterNot { c -> certificates.any { it == c } }
            if (path.isEmpty()) return false
            val cf = CertificateFactory.getInstance("X.509")
            val certPath = cf.generateCertPath(path)
            val params = PKIXParameters(certificates.map { TrustAnchor(it, null) }.toSet())
            params.isRevocationEnabled = false
            CertPathValidator.getInstance("PKIX").validate(certPath, params)
            true
        } catch (_: Exception) {
            false
        }
    }

    companion object {
        fun load(paths: List<Path>): TrustAnchors {
            val cf = CertificateFactory.getInstance("X.509")
            val certs = paths.flatMap { p ->
                Files.newInputStream(p).use { input -> cf.generateCertificates(input).map { it as X509Certificate } }
            }
            return TrustAnchors(certs)
        }

        fun dnsSans(cert: X509Certificate): List<String> =
            cert.subjectAlternativeNames.orEmpty()
                .filter { (it[0] as Int) == 2 }
                .map { it[1] as String }
    }
}

/** Address policy for outbound requests. */
class NetworkGuard(private val allowPrivateNetworks: Boolean) {

    fun check(addr: InetAddress) {
        if (isAlwaysBlocked(addr)) throw UnknownHostException("blocked address")
        if (!allowPrivateNetworks && isPrivate(addr)) throw UnknownHostException("blocked private address")
    }

    private fun isAlwaysBlocked(a: InetAddress): Boolean =
        a.isAnyLocalAddress || a.isLinkLocalAddress || a.isMulticastAddress ||
            (a is Inet4Address && a.address[0].toInt() and 0xff == 0) // 0.0.0.0/8

    private fun isPrivate(a: InetAddress): Boolean {
        if (a.isLoopbackAddress || a.isSiteLocalAddress) return true
        val b = a.address
        if (a is Inet4Address) {
            val b0 = b[0].toInt() and 0xff
            val b1 = b[1].toInt() and 0xff
            if (b0 == 100 && b1 in 64..127) return true // CGNAT
            if (b0 == 198 && (b1 == 18 || b1 == 19)) return true // benchmarking
        }
        if (a is Inet6Address) {
            val b0 = b[0].toInt() and 0xff
            if (b0 and 0xfe == 0xfc) return true // fc00::/7 ULA
            // IPv4-mapped (::ffff:a.b.c.d)
            if (b.copyOfRange(0, 10).all { it.toInt() == 0 } && b[10].toInt() and 0xff == 0xff && b[11].toInt() and 0xff == 0xff) {
                return isPrivate(InetAddress.getByAddress(b.copyOfRange(12, 16)))
            }
        }
        return false
    }

    /** DNS that refuses a host if any resolved address is blocked; OkHttp then connects only to vetted addresses. */
    val dns: Dns = Dns { host ->
        val addrs = Dns.SYSTEM.lookup(host)
        addrs.forEach { check(it) }
        addrs
    }

    /** IP-literal hosts bypass [Dns] in OkHttp, so check them per request (each redirect hop included). */
    fun interceptor(): Interceptor = Interceptor { chain ->
        val url = chain.request().url
        if (url.scheme != "https") throw IOException("only https is allowed")
        val host = url.host
        if (isIpLiteral(host)) check(InetAddress.getByName(host))
        chain.proceed(chain.request())
    }

    private fun isIpLiteral(host: String): Boolean =
        host.contains(':') || host.matches(Regex("^[0-9.]+$"))
}

class ResponseTooLarge : IOException("response too large")

/** Fails responses larger than [maxBytes], whether or not Content-Length is declared. */
fun sizeLimitInterceptor(maxBytes: Long): Interceptor = Interceptor { chain ->
    val response: Response = chain.proceed(chain.request())
    val body = response.body
    if (body.contentLength() > maxBytes) {
        response.close()
        throw ResponseTooLarge()
    }
    val limited = object : ForwardingSource(body.source()) {
        var total = 0L
        override fun read(sink: Buffer, byteCount: Long): Long {
            val n = super.read(sink, byteCount)
            if (n > 0) {
                total += n
                if (total > maxBytes) throw ResponseTooLarge()
            }
            return n
        }
    }
    val newBody: ResponseBody = limited.buffer().asResponseBody(body.contentType(), body.contentLength())
    response.newBuilder().body(newBody).build()
}

object OutboundHttp {

    fun sslTrustManager(anchors: TrustAnchors, includeSystem: Boolean): X509TrustManager {
        val ks = KeyStore.getInstance(KeyStore.getDefaultType())
        ks.load(null, null)
        anchors.certificates.forEachIndexed { i, c -> ks.setCertificateEntry("anchor-$i", c) }
        if (includeSystem) {
            val sys = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            sys.init(null as KeyStore?)
            sys.trustManagers.filterIsInstance<X509TrustManager>().flatMap { it.acceptedIssuers.toList() }
                .forEachIndexed { i, c -> ks.setCertificateEntry("system-$i", c) }
        }
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(ks)
        return tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    fun okHttp(
        anchors: TrustAnchors,
        includeSystemCas: Boolean,
        guard: NetworkGuard,
        timeoutSeconds: Long,
        maxResponseBytes: Long,
    ): OkHttpClient {
        val tm = sslTrustManager(anchors, includeSystemCas)
        val ssl = SSLContext.getInstance("TLS")
        ssl.init(null, arrayOf(tm), null)
        return OkHttpClient.Builder()
            .proxy(Proxy.NO_PROXY)
            .dns(guard.dns)
            .sslSocketFactory(ssl.socketFactory, tm)
            .addInterceptor(guard.interceptor())
            .addNetworkInterceptor(guard.interceptor())
            .addNetworkInterceptor(sizeLimitInterceptor(maxResponseBytes))
            .connectTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .writeTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .callTimeout(timeoutSeconds * 2, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(false)
            .build()
    }

    /** The single outbound client, injected into the EUDI libraries. */
    fun ktor(ok: OkHttpClient): HttpClient = HttpClient(OkHttp) {
        engine { preconfigured = ok }
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
        expectSuccess = false
    }
}
