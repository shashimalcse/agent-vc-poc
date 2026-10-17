package dev.a2wp.wallet

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.net.URI
import java.net.URLDecoder

/** A request error detected before an operation exists. Reported as a tool error, not an operation. */
class RequestError(val code: String, val detail: String) : RuntimeException(code) {
    override fun fillInStackTrace(): Throwable = this
}

sealed interface ParsedOffer {
    /** Key used to recognise a retried call with the same offer. */
    val dedupeKey: String

    /** The `openid-credential-offer://` form accepted by the OpenID4VCI library. */
    val libraryUri: String

    data class ByValue(val offerJson: String) : ParsedOffer {
        override val dedupeKey: String = "offer-value:" + ApiKeyAuthenticator.sha256Hex(offerJson)
        override val libraryUri: String =
            "openid-credential-offer://?credential_offer=" + java.net.URLEncoder.encode(offerJson, Charsets.UTF_8)
    }

    data class ByReference(val offerUri: String) : ParsedOffer {
        override val dedupeKey: String = "offer-uri:$offerUri"
        override val libraryUri: String =
            "openid-credential-offer://?credential_offer_uri=" + java.net.URLEncoder.encode(offerUri, Charsets.UTF_8)
    }
}

data class ParsedPresentationRequest(
    /** The URL exactly as received; the OpenID4VP library parses it again. */
    val url: String,
    val clientId: String,
    val requestUri: String?,
    val dedupeKey: String,
)

/** What a submitted request turned out to be (A2WP -01: the wallet identifies the request). */
sealed interface ParsedRequest {
    data class Offer(val offer: ParsedOffer) : ParsedRequest
    data class Presentation(val request: ParsedPresentationRequest) : ParsedRequest
}

object RequestParsing {
    private const val OFFER_SCHEME = "openid-credential-offer"
    private val VP_SCHEMES = setOf("openid4vp", "haip-vp", "eudi-openid4vp")

    /**
     * Identifies a request as received: the custom scheme decides it; an https link is a presentation request when it
     * has client_id, otherwise a credential offer (by value, or the https URL is itself the offer's reference).
     */
    fun classify(input: String, maxChars: Int = 16_384): ParsedRequest {
        val s = input.trim()
        if (s.isEmpty() || s.length > maxChars) throw invalid("The wallet does not recognize this request.")
        val uri = parseUri(s)
        return when (uri.scheme?.lowercase()) {
            OFFER_SCHEME -> ParsedRequest.Offer(parseOffer(s, maxChars))
            in VP_SCHEMES -> ParsedRequest.Presentation(parsePresentationRequest(s, maxChars))
            "https" ->
                if ("client_id" in queryParams(uri)) ParsedRequest.Presentation(parsePresentationRequest(s, maxChars))
                else ParsedRequest.Offer(parseOffer(s, maxChars))
            else -> throw invalid("The wallet does not recognize this request.")
        }
    }

    fun parseOffer(input: String, maxChars: Int = 16_384): ParsedOffer {
        val s = input.trim()
        if (s.isEmpty() || s.length > maxChars) throw invalid("The offer must be a non-empty URL.")
        val uri = parseUri(s)
        val scheme = uri.scheme?.lowercase() ?: throw invalid("The offer must be an openid-credential-offer:// or https URL.")
        val params = queryParams(uri)
        return when (scheme) {
            OFFER_SCHEME -> fromParams(params) ?: throw invalid("The offer URL has neither credential_offer nor credential_offer_uri.")
            "https" -> fromParams(params) ?: run {
                if (uri.host.isNullOrBlank()) throw invalid("The credential_offer_uri has no host.")
                if (uri.rawFragment != null) throw invalid("The credential_offer_uri must not have a fragment.")
                ParsedOffer.ByReference(s)
            }
            else -> throw invalid("The offer must be an openid-credential-offer:// or https URL.")
        }
    }

    private fun fromParams(params: Map<String, List<String>>): ParsedOffer? {
        val byValue = params["credential_offer"]
        val byRef = params["credential_offer_uri"]
        if ((byValue?.size ?: 0) > 1 || (byRef?.size ?: 0) > 1) throw invalid("Duplicate offer parameters.")
        val v = byValue?.firstOrNull()?.takeIf { it.isNotBlank() }
        val r = byRef?.firstOrNull()?.takeIf { it.isNotBlank() }
        return when {
            v != null && r != null -> throw invalid("The offer has both credential_offer and credential_offer_uri.")
            v != null -> {
                val obj = runCatching { Json.parseToJsonElement(v) as JsonObject }.getOrNull()
                    ?: throw invalid("credential_offer is not a JSON object.")
                if (obj.isEmpty()) throw invalid("credential_offer is empty.")
                ParsedOffer.ByValue(v)
            }
            r != null -> {
                val ru = parseUri(r)
                if (ru.scheme?.lowercase() != "https" || ru.host.isNullOrBlank()) throw invalid("credential_offer_uri must be an https URL.")
                ParsedOffer.ByReference(r)
            }
            else -> null
        }
    }

    fun parsePresentationRequest(input: String, maxChars: Int = 16_384): ParsedPresentationRequest {
        val s = input.trim()
        if (s.isEmpty() || s.length > maxChars) throw invalid("The request must be a non-empty URL.")
        val uri = parseUri(s)
        val scheme = uri.scheme?.lowercase() ?: throw invalid("The request must be an openid4vp:// or https URL.")
        if (scheme !in VP_SCHEMES && scheme != "https") throw invalid("The request must be an openid4vp:// or https URL.")
        val params = queryParams(uri)
        params.forEach { (k, v) -> if (v.size > 1) throw invalid("Duplicate request parameter '$k'.") }
        val clientId = params["client_id"]?.firstOrNull()?.takeIf { it.isNotBlank() }
            ?: throw invalid("The request has no client_id.")
        val requestUri = params["request_uri"]?.firstOrNull()?.takeIf { it.isNotBlank() }
        val requestJwt = params["request"]?.firstOrNull()?.takeIf { it.isNotBlank() }
        if (requestUri != null && requestJwt != null) throw invalid("The request has both request and request_uri.")
        if (requestUri == null && requestJwt == null) {
            throw RequestError(ErrorCodes.UNSUPPORTED_REQUEST, "Only signed requests (request or request_uri) are supported.")
        }
        if (requestUri != null) {
            val ru = parseUri(requestUri)
            if (ru.scheme?.lowercase() != "https" || ru.host.isNullOrBlank()) throw invalid("request_uri must be an https URL.")
        }
        params["request_uri_method"]?.firstOrNull()?.let {
            if (it != "get" && it != "post") throw invalid("request_uri_method must be get or post.")
        }
        val key = requestUri?.let { "vp-request-uri:$it" } ?: ("vp-request:" + ApiKeyAuthenticator.sha256Hex(requestJwt!!))
        return ParsedPresentationRequest(s, clientId, requestUri, key)
    }

    private fun parseUri(s: String): URI =
        try {
            URI(s)
        } catch (_: Exception) {
            throw invalid("Not a valid URL.")
        }

    /** Splits a raw query, URL-decoding each name and value exactly once. */
    fun queryParams(uri: URI): Map<String, List<String>> {
        val raw = (if (uri.isOpaque) uri.rawSchemeSpecificPart.substringAfter('?', "") else uri.rawQuery)
            ?: return emptyMap()
        if (raw.isEmpty()) return emptyMap()
        return raw.split('&').filter { it.isNotEmpty() }.map { pair ->
            val i = pair.indexOf('=')
            val k = if (i < 0) pair else pair.substring(0, i)
            val v = if (i < 0) "" else pair.substring(i + 1)
            decode(k) to decode(v)
        }.groupBy({ it.first }, { it.second })
    }

    private fun decode(s: String): String =
        try {
            URLDecoder.decode(s, Charsets.UTF_8)
        } catch (_: IllegalArgumentException) {
            throw invalid("Malformed URL encoding.")
        }

    private fun invalid(detail: String) = RequestError(ErrorCodes.UNSUPPORTED_REQUEST, detail)
}
