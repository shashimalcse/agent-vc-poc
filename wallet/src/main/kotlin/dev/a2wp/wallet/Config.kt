package dev.a2wp.wallet

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path

/** Wallet configuration, loaded from `config/wallet.json`. Relative paths resolve against the config file's directory. */
@Serializable
data class WalletConfig(
    val listen: Listen = Listen(),
    /** Public base URL used to build approval links, e.g. `https://wallet.localtest.me`. */
    @SerialName("public_base_url") val publicBaseUrl: String,
    /** Hostnames accepted in the `Host` header of `/mcp` requests (DNS-rebinding protection). */
    @SerialName("allowed_hosts") val allowedHosts: List<String> = listOf("localhost", "127.0.0.1"),
    /** PEM files with CA certificates trusted for outbound TLS, verifier request objects and issuer credentials. */
    @SerialName("trust_anchors") val trustAnchors: List<String>,
    /** Also trust the JVM's default CA set for outbound TLS (not for x5c). */
    @SerialName("trust_system_cas") val trustSystemCas: Boolean = false,
    /** Dev flag: allow outbound connections to loopback and private addresses (`*.localtest.me` resolves to 127.0.0.1). */
    @SerialName("allowPrivateNetworks") val allowPrivateNetworks: Boolean = false,
    val store: StoreConfig,
    val limits: Limits = Limits(),
    val principals: List<PrincipalConfig> = emptyList(),
    val agents: List<AgentConfig>,
) {
    @Serializable
    data class Listen(val host: String = "127.0.0.1", val port: Int = 8180)

    @Serializable
    data class StoreConfig(
        /** Encrypted store file. */
        val path: String,
        /** Name of an env var holding the base64 AES-256 key. Takes precedence over [keyFile]. */
        @SerialName("key_env") val keyEnv: String? = "A2WP_STORE_KEY",
        /** File holding the base64 AES-256 key. Created (mode 600) if missing. */
        @SerialName("key_file") val keyFile: String? = null,
    )

    @Serializable
    data class Limits(
        @SerialName("operation_ttl_seconds") val operationTtlSeconds: Long = 600,
        @SerialName("interaction_ttl_seconds") val interactionTtlSeconds: Long = 300,
        @SerialName("retention_seconds") val retentionSeconds: Long = 3600,
        @SerialName("http_timeout_seconds") val httpTimeoutSeconds: Long = 15,
        @SerialName("http_max_response_bytes") val httpMaxResponseBytes: Long = 1_048_576,
        @SerialName("max_active_operations_per_agent") val maxActiveOperationsPerAgent: Int = 10,
        @SerialName("max_input_chars") val maxInputChars: Int = 16_384,
    )
}

@Serializable
data class PrincipalConfig(
    val id: String,
    val name: String,
    /** Short display name for the principal (the agent's owner who approves). Not used in page headers. */
    @SerialName("display_name") val displayName: String? = null,
    /**
     * Optional SHA-256 (hex) of a passcode the principal must type on the approval page.
     * Without it, possession of the approval link is enough to approve.
     */
    @SerialName("approval_passcode_sha256") val approvalPasscodeSha256: String? = null,
)

@Serializable
data class AgentConfig(
    val id: String,
    val name: String,
    @SerialName("api_key_sha256") val apiKeySha256: String,
    val principal: String,
    val policy: PolicyConfig,
)

@Serializable
data class PolicyConfig(
    @SerialName("allowed_issuers") val allowedIssuers: List<AllowedIssuer> = emptyList(),
    @SerialName("allowed_verifiers") val allowedVerifiers: List<AllowedVerifier> = emptyList(),
    @SerialName("require_approval") val requireApproval: ApprovalMode = ApprovalMode.FIRST_PRESENTATION_TO_VERIFIER,
) {
    @Serializable
    data class AllowedIssuer(
        /** Exact `credential_issuer` (and SD-JWT `iss`) string. */
        val issuer: String,
        /** Accepted `vct` values. Empty means any. */
        val vcts: List<String> = emptyList(),
    )

    @Serializable
    data class AllowedVerifier(
        /** Verified client_id including prefix, e.g. `x509_san_dns:tickets.localtest.me`. */
        @SerialName("client_id") val clientId: String,
        /** Display name for the approval page. Shown next to (never instead of) the verified identity. */
        val name: String? = null,
        /** Top-level claim names this verifier may receive. */
        val claims: List<String>,
    )
}

@Serializable
enum class ApprovalMode {
    @SerialName("always") ALWAYS,
    @SerialName("first_presentation_to_verifier") FIRST_PRESENTATION_TO_VERIFIER,
    @SerialName("never") NEVER,
}

object ConfigLoader {
    private val json = Json { ignoreUnknownKeys = false }

    fun load(path: Path): LoadedConfig {
        val text = Files.readString(path)
        val cfg = json.decodeFromString(WalletConfig.serializer(), text)
        validate(cfg)
        return LoadedConfig(cfg, path.toAbsolutePath().parent)
    }

    fun parse(text: String, baseDir: Path): LoadedConfig {
        val cfg = json.decodeFromString(WalletConfig.serializer(), text)
        validate(cfg)
        return LoadedConfig(cfg, baseDir)
    }

    private fun validate(cfg: WalletConfig) {
        require(cfg.publicBaseUrl.startsWith("https://")) { "public_base_url must be https" }
        require(cfg.trustAnchors.isNotEmpty()) { "trust_anchors must not be empty" }
        val ids = cfg.agents.map { it.id }
        require(ids.toSet().size == ids.size) { "duplicate agent id" }
        val hashes = cfg.agents.map { it.apiKeySha256.lowercase() }
        require(hashes.toSet().size == hashes.size) { "duplicate api_key_sha256" }
        cfg.agents.forEach { a ->
            require(a.apiKeySha256.matches(Regex("^[0-9a-fA-F]{64}$"))) { "agent ${a.id}: api_key_sha256 must be 64 hex chars" }
            require(cfg.principals.isEmpty() || cfg.principals.any { it.id == a.principal }) {
                "agent ${a.id}: unknown principal ${a.principal}"
            }
        }
    }
}

class LoadedConfig(val config: WalletConfig, val baseDir: Path) {
    fun resolve(p: String): Path = baseDir.resolve(p).normalize()
    fun principal(id: String): PrincipalConfig =
        config.principals.firstOrNull { it.id == id } ?: PrincipalConfig(id = id, name = id)
}
