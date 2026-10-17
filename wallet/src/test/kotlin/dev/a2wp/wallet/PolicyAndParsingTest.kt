package dev.a2wp.wallet

import java.net.URLEncoder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PolicyTest {
    private val policy = PolicyConfig(
        allowedIssuers = listOf(PolicyConfig.AllowedIssuer("https://issuer.localtest.me/openid4vci", listOf("urn:vct:agent"))),
        allowedVerifiers = listOf(
            PolicyConfig.AllowedVerifier("x509_san_dns:tickets.localtest.me", "Tickets", listOf("agent_id", "agent_name")),
        ),
    )

    @Test
    fun `issuer allow-list is exact and default-deny`() {
        assertIs<PolicyEngine.Decision.Allow>(PolicyEngine.authorizeIssuer(policy, "https://issuer.localtest.me/openid4vci"))
        assertIs<PolicyEngine.Decision.Deny>(PolicyEngine.authorizeIssuer(policy, "https://issuer.localtest.me"))
        assertIs<PolicyEngine.Decision.Deny>(PolicyEngine.authorizeIssuer(policy, "https://issuer.localtest.me/openid4vci/"))
        assertIs<PolicyEngine.Decision.Deny>(PolicyEngine.authorizeIssuer(PolicyConfig(), "https://issuer.localtest.me/openid4vci"))
    }

    @Test
    fun `credential type must be allowed for the issuer`() {
        assertIs<PolicyEngine.Decision.Allow>(PolicyEngine.authorizeCredentialType(policy, "https://issuer.localtest.me/openid4vci", "urn:vct:agent"))
        assertIs<PolicyEngine.Decision.Deny>(PolicyEngine.authorizeCredentialType(policy, "https://issuer.localtest.me/openid4vci", "urn:vct:pid"))
    }

    @Test
    fun `verifier is matched on the verified client_id with prefix`() {
        assertEquals("Tickets", PolicyEngine.verifierRule(policy, "x509_san_dns:tickets.localtest.me")?.name)
        assertNull(PolicyEngine.verifierRule(policy, "tickets.localtest.me"))
        assertNull(PolicyEngine.verifierRule(policy, "x509_san_dns:evil.localtest.me"))
        assertNull(PolicyEngine.verifierRule(policy, "redirect_uri:https://tickets.localtest.me"))
    }

    @Test
    fun `minimal disclosure is requested intersect allowed`() {
        val rule = policy.allowedVerifiers.single()
        val (disclose, withheld) = PolicyEngine.selectClaims(rule, listOf("agent_id", "operator", "agent_name", "agent_id"))
        assertEquals(listOf("agent_id", "agent_name"), disclose)
        assertEquals(listOf("operator"), withheld)
        assertEquals(emptyList(), PolicyEngine.selectClaims(rule, emptyList()).first)
    }

    @Test
    fun `approval on first presentation and on new claims`() {
        val m = ApprovalMode.FIRST_PRESENTATION_TO_VERIFIER
        assertTrue(PolicyEngine.approvalRequired(m, null, listOf("agent_id")))
        assertFalse(PolicyEngine.approvalRequired(m, setOf("agent_id", "agent_name"), listOf("agent_id")))
        assertTrue(PolicyEngine.approvalRequired(m, setOf("agent_id"), listOf("agent_id", "operator")))
        assertTrue(PolicyEngine.approvalRequired(ApprovalMode.ALWAYS, setOf("agent_id"), listOf("agent_id")))
        assertFalse(PolicyEngine.approvalRequired(ApprovalMode.NEVER, null, listOf("agent_id")))
    }
}

class RequestParsingTest {
    private fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8)

    @Test
    fun `offer by reference via custom scheme`() {
        val o = RequestParsing.parseOffer("openid-credential-offer://?credential_offer_uri=${enc("https://issuer.localtest.me/openid4vci/offers/abc")}")
        val r = assertIs<ParsedOffer.ByReference>(o)
        assertEquals("https://issuer.localtest.me/openid4vci/offers/abc", r.offerUri)
        assertTrue(r.libraryUri.startsWith("openid-credential-offer://?credential_offer_uri="))
    }

    @Test
    fun `offer by value`() {
        val json = """{"credential_issuer":"https://issuer.localtest.me/openid4vci","credential_configuration_ids":["AgentCredential"]}"""
        val o = assertIs<ParsedOffer.ByValue>(RequestParsing.parseOffer("openid-credential-offer://?credential_offer=${enc(json)}"))
        assertEquals(json, o.offerJson)
    }

    @Test
    fun `bare https credential_offer_uri`() {
        val o = assertIs<ParsedOffer.ByReference>(RequestParsing.parseOffer("  https://issuer.localtest.me/openid4vci/offers/abc \n"))
        assertEquals("https://issuer.localtest.me/openid4vci/offers/abc", o.offerUri)
    }

    @Test
    fun `bad offers are invalid-request`() {
        listOf(
            "",
            "not a url",
            "http://issuer.localtest.me/offer",
            "openid-credential-offer://?foo=bar",
            "openid-credential-offer://?credential_offer_uri=${enc("http://insecure.example/x")}",
            "openid-credential-offer://?credential_offer=notjson",
            "openid-credential-offer://?credential_offer=%7B%7D&credential_offer_uri=${enc("https://a.example/x")}",
            "openid-credential-offer://?credential_offer_uri=${enc("https://a.example/x")}&credential_offer_uri=${enc("https://b.example/x")}",
            "x".repeat(20_000),
        ).forEach { s ->
            val e = assertFailsWith<RequestError>(s.take(60)) { RequestParsing.parseOffer(s) }
            assertEquals(ErrorCodes.UNSUPPORTED_REQUEST, e.code)
        }
    }

    @Test
    fun `openid4vp by reference`() {
        val url = "openid4vp://authorize?client_id=${enc("x509_san_dns:tickets.localtest.me")}" +
            "&request_uri=${enc("https://tickets.localtest.me/verification-session/s1/request")}&request_uri_method=get"
        val r = RequestParsing.parsePresentationRequest(url)
        assertEquals("x509_san_dns:tickets.localtest.me", r.clientId)
        assertEquals("https://tickets.localtest.me/verification-session/s1/request", r.requestUri)
        assertEquals(url, r.url)
        assertEquals("vp-request-uri:https://tickets.localtest.me/verification-session/s1/request", r.dedupeKey)
    }

    @Test
    fun `openid4vp opaque and by value forms`() {
        val r = RequestParsing.parsePresentationRequest("openid4vp:?client_id=x509_san_dns%3Aa.example&request=eyJ.eyJ.sig")
        assertNull(r.requestUri)
        assertTrue(r.dedupeKey.startsWith("vp-request:"))
    }

    @Test
    fun `bad presentation requests`() {
        val cases = mapOf(
            "https://tickets.localtest.me/" to ErrorCodes.UNSUPPORTED_REQUEST, // no client_id
            "openid4vp://?client_id=a" to ErrorCodes.UNSUPPORTED_REQUEST, // unsigned
            "openid4vp://?client_id=a&request_uri=${enc("http://a.example/r")}" to ErrorCodes.UNSUPPORTED_REQUEST,
            "openid4vp://?client_id=a&request=x&request_uri=${enc("https://a.example/r")}" to ErrorCodes.UNSUPPORTED_REQUEST,
            "openid4vp://?client_id=a&client_id=b&request_uri=${enc("https://a.example/r")}" to ErrorCodes.UNSUPPORTED_REQUEST,
            "openid4vp://?client_id=a&request_uri=${enc("https://a.example/r")}&request_uri_method=put" to ErrorCodes.UNSUPPORTED_REQUEST,
            "mailto:x@example.com" to ErrorCodes.UNSUPPORTED_REQUEST,
        )
        cases.forEach { (url, code) ->
            val e = assertFailsWith<RequestError>(url) { RequestParsing.parsePresentationRequest(url) }
            assertEquals(code, e.code, url)
        }
    }

    @Test
    fun `the wallet identifies a request from the string alone`() {
        val vp = "openid4vp://?client_id=x509_san_dns%3Aa.example&request_uri=${enc("https://a.example/r")}"
        assertIs<ParsedRequest.Presentation>(RequestParsing.classify(vp))
        assertIs<ParsedRequest.Presentation>(RequestParsing.classify("https://wallet.example/cb?client_id=x509_san_dns%3Aa.example&request_uri=${enc("https://a.example/r")}"))
        assertIs<ParsedRequest.Offer>(RequestParsing.classify("openid-credential-offer://?credential_offer_uri=${enc("https://issuer.example/o/1")}"))
        assertIs<ParsedRequest.Offer>(RequestParsing.classify("https://issuer.example/o/1"))
        listOf("hello", "mailto:x@example.com", "", "ftp://a.example/x").forEach { s ->
            val e = assertFailsWith<RequestError>(s) { RequestParsing.classify(s) }
            assertEquals(ErrorCodes.UNSUPPORTED_REQUEST, e.code)
        }
    }
}
