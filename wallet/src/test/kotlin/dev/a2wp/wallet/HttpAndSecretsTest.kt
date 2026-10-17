package dev.a2wp.wallet

import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.parameters
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HttpAndSecretsTest {
    private val apiKey = "a2wp_test_key_1"
    private val otherKey = "a2wp_test_key_2"
    private val secretCredential = "eyJhbGciOiJFUzI1NiJ9.SECRET-CREDENTIAL-PAYLOAD.sig~WyJzYWx0IiwiYWdlbnRfbmFtZSIsIlNFQ1JFVCJd~"
    private val secretKeyD = "SECRET-PRIVATE-D-VALUE"
    private val secretOfferCode = "SECRETPREAUTHCODE"
    private val passcode = "2468"

    private fun config(dir: Path): LoadedConfig {
        val ca = Paths.get(javaClass.getResource("/test-ca.pem")!!.toURI()).toString()
        val policy = """{"allowed_issuers":[{"issuer":"https://issuer.test"}],
            "allowed_verifiers":[{"client_id":"x509_san_dns:verifier.test","claims":["agent_id"]}]}"""
        val json = """{
          "public_base_url": "https://wallet.test",
          "allowed_hosts": ["localhost", "wallet.test"],
          "trust_anchors": ["$ca"],
          "store": {"path": "wallet.store", "key_env": null, "key_file": "store.key"},
          "principals": [{"id": "p1", "name": "User", "display_name": "Ada", "approval_passcode_sha256": "${ApiKeyAuthenticator.sha256Hex(passcode)}"}],
          "agents": [
            {"id": "a1", "name": "Agent One", "api_key_sha256": "${ApiKeyAuthenticator.sha256Hex(apiKey)}", "principal": "p1", "policy": $policy},
            {"id": "a2", "name": "Agent Two", "api_key_sha256": "${ApiKeyAuthenticator.sha256Hex(otherKey)}", "principal": "p1", "policy": $policy}
          ]
        }"""
        return ConfigLoader.parse(json, dir)
    }

    /** A wallet whose external protocol steps are faked; everything else (auth, MCP, operations, store) is real. */
    private fun wallet(dir: Path): WalletApp {
        val cfg = config(dir)
        lateinit var app: WalletApp
        app = WalletApp(
            cfg,
            acquireOverride = { ctx, agent, _ ->
                ctx.processing()
                val c = StoredCredential(
                    handle = "cred_test1", agentId = agent.agentId, principalId = agent.principalId, format = "dc+sd-jwt",
                    vct = "urn:vct:agent", issuer = "https://issuer.test", credential = secretCredential,
                    holderKey = """{"kty":"EC","crv":"P-256","d":"$secretKeyD","x":"x","y":"y"}""",
                    storedAt = Instant.now().toString(),
                )
                ctx.commit()
                app.store.addCredentials(listOf(c))
                ctx.complete()
            },
            presentOverride = { ctx, _, _ ->
                ctx.processing()
                ctx.interact(ApprovalPrompt.Present("x509_san_dns:verifier.test", "CN=verifier.test", null, "urn:vct:agent", listOf("agent_id" to "agt_1"), listOf("operator")))
                ctx.commit()
                // A library failure whose message carries secret material must not leak into the detail.
                throw AcquireService.mapVciError("Posting failed", IllegalStateException("vp_token=$secretCredential"))
            },
        )
        return app
    }

    private suspend fun ApplicationTestBuilder.rpc(key: String?, method: String, params: JsonObject = JsonObject(emptyMap()), id: Int = 1) =
        client.post("/mcp") {
            key?.let { header(HttpHeaders.Authorization, "Bearer $it") }
            header(HttpHeaders.Host, "wallet.test")
            header(HttpHeaders.Accept, "application/json, text/event-stream")
            header("mcp-protocol-version", "2025-06-18")
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", id)
                put("method", method)
                put("params", params)
            }.toString())
        }

    private suspend fun ApplicationTestBuilder.tool(key: String, name: String, args: JsonObject): JsonObject {
        val r = rpc(key, "tools/call", buildJsonObject {
            put("name", name)
            put("arguments", args)
        })
        assertEquals(HttpStatusCode.OK, r.status)
        val body = r.bodyAsText()
        allOutputs += body
        return Json.parseToJsonElement(body).jsonObject["result"]!!.jsonObject
    }

    private val allOutputs = mutableListOf<String>()

    private fun JsonObject.op(): JsonObject = this["structuredContent"]!!.jsonObject["operation"]!!.jsonObject
    private fun JsonObject.s(k: String) = this[k]?.jsonPrimitive?.content

    @Test
    fun `requests without a valid key get 401 before MCP handling`() = testApplication {
        val dir = Files.createTempDirectory("a2wp")
        application { wallet(dir).module(this) }
        assertEquals(HttpStatusCode.Unauthorized, rpc(null, "initialize").status)
        val bad = rpc("wrong", "initialize")
        assertEquals(HttpStatusCode.Unauthorized, bad.status)
        assertTrue(bad.headers[HttpHeaders.WWWAuthenticate]!!.startsWith("Bearer"))
        assertEquals(HttpStatusCode.Unauthorized, client.get("/mcp").status)
        val basic = client.post("/mcp") { header(HttpHeaders.Authorization, "Basic $apiKey") }
        assertEquals(HttpStatusCode.Unauthorized, basic.status)
        // With a key, MCP works.
        val ok = rpc(apiKey, "initialize", buildJsonObject {
            put("protocolVersion", "2025-06-18")
            put("capabilities", JsonObject(emptyMap()))
            put("clientInfo", buildJsonObject { put("name", "t"); put("version", "1") })
        })
        assertEquals(HttpStatusCode.OK, ok.status)
        val tools = rpc(apiKey, "tools/list", id = 2).bodyAsText()
        listOf("submit", "get", "cancel").forEach {
            assertTrue(tools.contains("\"$it\""), it)
        }
        // The approval page and health check are not behind the API key.
        assertEquals(HttpStatusCode.OK, client.get("/healthz").status)
    }

    @Test
    fun `api key authenticator`() {
        val a = ApiKeyAuthenticator(config(Files.createTempDirectory("a2wp")).config.agents)
        assertEquals("a1", a.authenticate("Bearer $apiKey")?.agentId)
        assertEquals("a1", a.authenticate("bearer   $apiKey ")?.agentId)
        assertEquals("a2", a.authenticate("Bearer $otherKey")?.agentId)
        assertNull(a.authenticate(null))
        assertNull(a.authenticate(""))
        assertNull(a.authenticate("Bearer"))
        assertNull(a.authenticate("Bearer ${apiKey}x"))
        assertNull(a.authenticate(apiKey))
        assertNull(a.authenticate("Basic $apiKey"))
    }

    @Test
    fun `full tool flow never returns secret material`() = testApplication {
        val dir = Files.createTempDirectory("a2wp")
        val w = wallet(dir)
        application { w.module(this) }

        // Issuance: the agent submits the offer link; the wallet works out what it is.
        val offer = "openid-credential-offer://?credential_offer=" +
            java.net.URLEncoder.encode("""{"credential_issuer":"https://issuer.test","grants":{"urn:ietf:params:oauth:grant-type:pre-authorized_code":{"pre-authorized_code":"$secretOfferCode"}}}""", Charsets.UTF_8)
        var op = tool(apiKey, "submit", buildJsonObject { put("request", offer) }).op()
        val acqId = op.s("id")!!
        assertTrue(acqId.startsWith("op_"))
        op = tool(apiKey, "get", buildJsonObject { put("id", acqId); put("wait_seconds", 5) }).op()
        if (op.s("status") != "completed") op = tool(apiKey, "get", buildJsonObject { put("id", acqId); put("wait_seconds", 5) }).op()
        assertEquals("completed", op.s("status"))
        // A2WP -01: an issuance has no continuation, and the operation carries no credential handle.
        assertEquals(setOf("id", "status"), op.keys)

        // Retried call with the same offer returns the same operation.
        val again = tool(apiKey, "submit", buildJsonObject { put("request", offer) }).op()
        assertEquals(acqId, again.s("id"))

        // Another agent cannot see it.
        val foreign = tool(otherKey, "get", buildJsonObject { put("id", acqId) })
        assertEquals(true, foreign["isError"]?.jsonPrimitive?.content?.toBoolean())
        assertEquals(ErrorCodes.NOT_FOUND, foreign["structuredContent"]!!.jsonObject["error"]!!.jsonObject.s("code"))

        // Presentation -> pending with an interaction -> approve on the page -> failure after commit
        val req = "openid4vp://?client_id=x509_san_dns%3Averifier.test&request_uri=https%3A%2F%2Fverifier.test%2Fr%2F1"
        op = tool(apiKey, "submit", buildJsonObject { put("request", req) }).op()
        val pid = op.s("id")!!
        while (op["interaction"] == null) {
            op = tool(apiKey, "get", buildJsonObject { put("id", pid); put("wait_seconds", 5) }).op()
        }
        assertEquals("pending", op.s("status"))
        val uri = op["interaction"]!!.jsonObject.s("uri")!!
        assertTrue(uri.startsWith("https://wallet.test/approve/ia_"))
        val path = uri.removePrefix("https://wallet.test")

        // The approval page shows the verified verifier, the claims to share and the withheld ones.
        val pageResponse = client.get(path)
        val page = pageResponse.bodyAsText()
        allOutputs += page
        assertTrue(page.contains("Agent One · agent wallet"))
        assertTrue(page.contains("Your agent's wallet asks you before it shares the agent's credential with verifier.test."))
        assertTrue(page.contains("Share with verifier.test?")) // verified host, not agent-supplied purpose
        assertTrue(page.contains("Verified requester"))
        assertTrue(page.contains("x509_san_dns:verifier.test"))
        assertTrue(page.contains("Agent ID") && page.contains("agt_1"))
        assertTrue(page.contains("not shared") && page.contains("Operator")) // withheld claim listed by label
        assertFalse(page.contains("<script"))
        val csp = pageResponse.headers["Content-Security-Policy"]!!
        assertTrue(csp.contains("default-src 'none'") && csp.contains("font-src 'self'") && !csp.contains("script-src"))
        assertEquals("no-store", pageResponse.headers[HttpHeaders.CacheControl])
        assertEquals("no-referrer", pageResponse.headers["Referrer-Policy"])
        // GET is side-effect free: the operation is still waiting for the user.
        assertNotNull(tool(apiKey, "get", buildJsonObject { put("id", pid) }).op()["interaction"])
        // Fonts are served by the wallet itself.
        val font = client.get("/assets/fonts/Geist-Variable.woff2")
        assertEquals(HttpStatusCode.OK, font.status)
        assertEquals("font/woff2", font.headers[HttpHeaders.ContentType])
        assertEquals(HttpStatusCode.NotFound, client.get("/assets/fonts/..%2Fwallet.store").status)
        assertEquals(HttpStatusCode.Forbidden, client.submitForm(path, parameters { append("action", "approve") }) {
            header(HttpHeaders.Origin, "https://evil.test")
        }.status)
        // Without the principal's passcode, the link alone cannot approve.
        assertEquals(HttpStatusCode.Forbidden, client.submitForm(path, parameters { append("action", "approve") }).status)
        val approved = client.submitForm(path, parameters { append("action", "approve"); append("passcode", passcode) })
        assertEquals(HttpStatusCode.OK, approved.status)
        // The result page reflects the real outcome (here the fake verifier step failed after commit).
        assertTrue(approved.bodyAsText().contains("Couldn&#39;t share"))
        assertTrue(client.get(path).bodyAsText().contains("Couldn&#39;t share"))

        while (op.s("status") != "failed") {
            op = tool(apiKey, "get", buildJsonObject { put("id", pid); put("wait_seconds", 5) }).op()
        }
        val err = op["error"]!!.jsonObject
        assertEquals(ErrorCodes.PROCESSING_ERROR, err.s("code"))
        assertFalse(err.s("detail")!!.contains("vp_token"))

        // Cancel after terminal is not-cancellable.
        val c = tool(apiKey, "cancel", buildJsonObject { put("id", pid) })
        assertEquals(ErrorCodes.NOT_CANCELLABLE, c["structuredContent"]!!.jsonObject["error"]!!.jsonObject.s("code"))

        // Only a submission without a request string is rejected (an error and no operation).
        val noRequest = tool(apiKey, "submit", buildJsonObject { })
        assertEquals(true, noRequest["isError"]?.jsonPrimitive?.content?.toBoolean())
        assertNull(noRequest["structuredContent"]!!.jsonObject["operation"])
        assertEquals(ErrorCodes.UNSUPPORTED_REQUEST, noRequest["structuredContent"]!!.jsonObject["error"]!!.jsonObject.s("code"))
        // A request the wallet does not recognize is an operation that fails with unsupported-request.
        var bad = tool(apiKey, "submit", buildJsonObject { put("request", "hello") }).op()
        while (bad.s("status") != "failed") bad = tool(apiKey, "get", buildJsonObject { put("id", bad.s("id")!!); put("wait_seconds", 5) }).op()
        assertEquals(ErrorCodes.UNSUPPORTED_REQUEST, bad["error"]!!.jsonObject.s("code"))
        // wait_seconds out of range is clamped, not rejected.
        assertEquals("failed", tool(apiKey, "get", buildJsonObject { put("id", pid); put("wait_seconds", 99) }).op().s("status"))

        // Nothing secret in any tool output or page.
        val everything = allOutputs.joinToString("\n")
        listOf(secretCredential, "SECRET-CREDENTIAL-PAYLOAD", secretKeyD, secretOfferCode, "\"d\"").forEach {
            assertFalse(everything.contains(it), "output leaked: $it")
        }

        // The store is encrypted at rest.
        val storeBytes = Files.readAllBytes(dir.resolve("wallet.store")).toString(Charsets.ISO_8859_1)
        assertFalse(storeBytes.contains("SECRET"))
        assertNotNull(w.store.credentialsFor("a1").singleOrNull())
        // And reopens with the same key.
        val reopened = EncryptedStore(dir.resolve("wallet.store"), EncryptedStore.loadKey(config(dir)))
        assertEquals("cred_test1", reopened.credentialsFor("a1").single().handle)
        reopened.recordApproval("a1", "x509_san_dns:verifier.test", listOf("agent_id"))
        assertEquals(setOf("agent_id"), EncryptedStore(dir.resolve("wallet.store"), EncryptedStore.loadKey(config(dir))).approvedClaims("a1", "x509_san_dns:verifier.test"))
    }

    @Test
    fun `operation json carries only contract members`() {
        val s = OperationSnapshot(
            "op_1", OpKind.PRESENT, OpStatus.FAILED, null, null, OpError(ErrorCodes.OUTCOME_UNKNOWN, "x"), Instant.now(), Instant.now(),
        )
        assertEquals(setOf("id", "status", "error"), s.toJson().keys)
        val text = WalletTools.summary(s)
        assertTrue(text.contains("outcome-unknown"))
        assertTrue(text.contains("Do not submit this request again"))
        // A continuation is passed through unchanged, also on failure.
        val withContinuation = s.copy(continuation = "https://verifier.test/cb?error=x")
        assertEquals("https://verifier.test/cb?error=x", withContinuation.toJson()["continuation"]!!.jsonPrimitive.content)
    }
}
