package dev.a2wp.wallet

import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.ECDSASigner
import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.gen.ECKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import eu.europa.ec.eudi.openid4vp.dcql.DCQL
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Paths
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The wallet is the agent's wallet: a credential is only ever usable by the agent whose API key acquired it. */
class AgentScopingTest {
    private val vct = "https://registry.localtest.me/credentials/agent/v1"
    private val issuer = "https://issuer.localtest.me/openid4vci"
    private val policy = PolicyConfig(
        allowedIssuers = listOf(PolicyConfig.AllowedIssuer(issuer, listOf(vct))),
        allowedVerifiers = listOf(PolicyConfig.AllowedVerifier("x509_san_dns:tickets.localtest.me", "Lantern Tickets", listOf("agent_id", "agent_name"))),
    )
    private val rule = policy.allowedVerifiers.single()

    // Two agents of the SAME principal, plus a third with no credentials.
    private val ada = AgentContext("openclaw", "OpenClaw", "user-1", policy)
    private val sam = AgentContext("manual", "Manual", "user-1", policy)
    private val empty = AgentContext("other", "Other", "user-1", policy)

    private val dcql: DCQL = Json.decodeFromString(
        DCQL.serializer(),
        """{"credentials":[{"id":"agent","format":"dc+sd-jwt","meta":{"vct_values":["$vct"]},
            "claims":[{"path":["agent_id"]},{"path":["agent_name"]},{"path":["operator"]}]}],
           "credential_sets":[{"options":[["agent"]]}]}""",
    )

    private fun b64(b: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(b)

    /** A syntactically valid SD-JWT VC: agent_id plain, agent_name selectively disclosable. */
    private fun sdJwt(agentId: String, agentName: String): String {
        val disclosure = b64("""["${b64(agentId.toByteArray())}","agent_name","$agentName"]""".toByteArray())
        val digest = b64(MessageDigest.getInstance("SHA-256").digest(disclosure.toByteArray(Charsets.US_ASCII)))
        val claims = JWTClaimsSet.Builder()
            .issuer(issuer)
            .claim("vct", vct)
            .claim("agent_id", agentId)
            .claim("_sd", listOf(digest))
            .claim("_sd_alg", "sha-256")
            .build()
        val key = ECKeyGenerator(Curve.P_256).generate()
        val jwt = SignedJWT(JWSHeader.Builder(JWSAlgorithm.ES256).type(JOSEObjectType("dc+sd-jwt")).build(), claims)
        jwt.sign(ECDSASigner(key))
        return "${jwt.serialize()}~$disclosure~"
    }

    private fun cred(agent: AgentContext, agentId: String, name: String, storedAt: Instant) = StoredCredential(
        handle = "cred_" + OperationManager.randomId(),
        agentId = agent.agentId,
        principalId = agent.principalId,
        format = "dc+sd-jwt",
        vct = vct,
        issuer = issuer,
        credential = sdJwt(agentId, name),
        holderKey = "{}",
        storedAt = storedAt.toString(),
    )

    private fun service(): Pair<PresentService, EncryptedStore> {
        val dir = Files.createTempDirectory("a2wp-scope")
        val store = EncryptedStore(dir.resolve("s.store"), EncryptedStore.decodeKey(Base64.getEncoder().encodeToString(ByteArray(32) { 7 })))
        val anchors = TrustAnchors.load(listOf(Paths.get(javaClass.getResource("/test-ca.pem")!!.toURI())))
        return PresentService(HttpClient(OkHttp), store, anchors) to store
    }

    @Test
    fun `an agent never presents another agent's credential, even for the same principal`() {
        val (svc, store) = service()
        val t = Instant.parse("2026-10-12T10:00:00Z")
        store.addCredentials(listOf(cred(ada, "agt_ada", "Ada's OpenClaw", t)))
        // Sam's credential is newer, and Sam shares Ada's principal: it must still be invisible to Ada's agent.
        store.addCredentials(listOf(cred(sam, "agt_sam", "Sam", t.plusSeconds(60))))

        val adaPlan = svc.planPresentation(ada, dcql, rule)
        assertEquals("openclaw", adaPlan.items.single().credential.agentId)
        assertTrue(adaPlan.items.single().shownValues.contains("agent_id" to "agt_ada"))

        val samPlan = svc.planPresentation(sam, dcql, rule)
        assertEquals("manual", samPlan.items.single().credential.agentId)
        assertTrue(samPlan.items.single().shownValues.contains("agent_id" to "agt_sam"))

        val e = assertFailsWith<OperationFailure> { svc.planPresentation(empty, dcql, rule) }
        assertEquals(ErrorCodes.NO_MATCHING_CREDENTIAL, e.code)
    }

    @Test
    fun `with several matching credentials of one agent, the most recently acquired wins`() {
        val (svc, store) = service()
        val t = Instant.parse("2026-10-12T10:00:00Z")
        store.addCredentials(listOf(cred(ada, "agt_old", "Old", t)))
        store.addCredentials(listOf(cred(ada, "agt_new", "New", t.plusMillis(1500))))
        store.addCredentials(listOf(cred(ada, "agt_mid", "Mid", t.plusSeconds(1))))
        val plan = svc.planPresentation(ada, dcql, rule)
        assertTrue(plan.items.single().shownValues.contains("agent_id" to "agt_new"))
        // Minimal disclosure still applies: operator was requested but is not allowed for this verifier.
        assertEquals(listOf("agent_id", "agent_name"), plan.items.single().disclose)
        assertEquals(listOf("operator"), plan.items.single().withheld)
    }

    @Test
    fun `store lookups and approval memory are per agent`() {
        val (_, store) = service()
        store.addCredentials(listOf(cred(ada, "agt_ada", "Ada's OpenClaw", Instant.now())))
        assertEquals(1, store.credentialsFor("openclaw").size)
        assertEquals(0, store.credentialsFor("manual").size)
        store.recordApproval("openclaw", "x509_san_dns:tickets.localtest.me", listOf("agent_id"))
        assertEquals(setOf("agent_id"), store.approvedClaims("openclaw", "x509_san_dns:tickets.localtest.me"))
        assertEquals(null, store.approvedClaims("manual", "x509_san_dns:tickets.localtest.me"))
    }
}
