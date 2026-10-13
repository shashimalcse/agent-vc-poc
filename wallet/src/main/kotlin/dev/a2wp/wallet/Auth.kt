package dev.a2wp.wallet

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.ContentType
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.request.header
import io.ktor.server.request.path
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.util.AttributeKey
import java.security.MessageDigest

/** The authenticated caller: an agent acting for a principal under a policy. Established only from the API key. */
data class AgentContext(
    val agentId: String,
    val agentName: String,
    val principalId: String,
    val policy: PolicyConfig,
)

class ApiKeyAuthenticator(agents: List<AgentConfig>) {
    private val entries: List<Pair<ByteArray, AgentContext>> = agents.map {
        hexToBytes(it.apiKeySha256.lowercase()) to AgentContext(it.id, it.name, it.principal, it.policy)
    }

    /** Returns the agent for an `Authorization` header value, or null. Compares SHA-256 digests in constant time. */
    fun authenticate(authorizationHeader: String?): AgentContext? {
        if (authorizationHeader == null) return null
        val parts = authorizationHeader.trim().split(Regex("\\s+"), limit = 2)
        if (parts.size != 2 || !parts[0].equals("Bearer", ignoreCase = true)) return null
        val token = parts[1].trim()
        if (token.isEmpty() || token.length > 512) return null
        val digest = sha256(token.toByteArray(Charsets.UTF_8))
        var found: AgentContext? = null
        for ((hash, ctx) in entries) {
            if (MessageDigest.isEqual(hash, digest)) found = ctx
        }
        return found
    }

    companion object {
        fun sha256(b: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(b)
        fun sha256Hex(s: String): String = sha256(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        private fun hexToBytes(hex: String): ByteArray = ByteArray(hex.length / 2) { i ->
            hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }
}

val AgentContextKey = AttributeKey<AgentContext>("a2wp.agent")

/**
 * Rejects any request under [pathPrefix] without a valid Bearer API key with 401, before MCP handling.
 * On success, the agent context is stored in the call attributes.
 */
fun apiKeyAuthPlugin(authenticator: ApiKeyAuthenticator, pathPrefix: String = "/mcp") =
    createApplicationPlugin("A2wpApiKeyAuth") {
        onCall { call ->
            val p = call.request.path()
            if (p != pathPrefix && !p.startsWith("$pathPrefix/")) return@onCall
            val ctx = authenticator.authenticate(call.request.header(HttpHeaders.Authorization))
            if (ctx == null) {
                call.response.header(HttpHeaders.WWWAuthenticate, "Bearer realm=\"a2wp\"")
                call.response.header(HttpHeaders.CacheControl, "no-store")
                call.respondText(
                    "{\"error\":\"unauthorized\"}",
                    ContentType.Application.Json,
                    HttpStatusCode.Unauthorized,
                )
                return@onCall
            }
            call.attributes.put(AgentContextKey, ctx)
        }
    }
