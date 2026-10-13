package dev.a2wp.wallet

import io.ktor.http.ContentType
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.modelcontextprotocol.kotlin.sdk.server.mcpStatelessStreamableHttp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

/** Assembles the wallet from a loaded config. */
class WalletApp(
    val cfg: LoadedConfig,
    storeOverride: EncryptedStore? = null,
    acquireOverride: (suspend (OperationContext, AgentContext, ParsedOffer) -> Unit)? = null,
    presentOverride: (suspend (OperationContext, AgentContext, ParsedPresentationRequest) -> Unit)? = null,
) {
    private val c = cfg.config
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val anchors = TrustAnchors.load(c.trustAnchors.map { cfg.resolve(it) })
    private val guard = NetworkGuard(c.allowPrivateNetworks)
    private val okHttp = OutboundHttp.okHttp(anchors, c.trustSystemCas, guard, c.limits.httpTimeoutSeconds, c.limits.httpMaxResponseBytes)
    val http = OutboundHttp.ktor(okHttp)
    val store = storeOverride ?: EncryptedStore.open(cfg)
    val ops = OperationManager(
        scope = scope,
        publicBaseUrl = c.publicBaseUrl,
        operationTtl = c.limits.operationTtlSeconds.seconds,
        interactionTtl = c.limits.interactionTtlSeconds.seconds,
        retention = c.limits.retentionSeconds.seconds,
        maxActivePerAgent = c.limits.maxActiveOperationsPerAgent,
    )
    private val acquireService = AcquireService(http, store, anchors, c.publicBaseUrl)
    private val presentService = PresentService(http, store, anchors)
    val tools = WalletTools(
        ops,
        acquireOverride ?: acquireService::run,
        presentOverride ?: presentService::run,
        c.limits.maxInputChars,
    )
    val authenticator = ApiKeyAuthenticator(c.agents)
    private val approvals = ApprovalPages(ops, cfg, store)

    fun module(app: Application) {
        app.install(apiKeyAuthPlugin(authenticator, "/mcp"))
        // Stateless Streamable HTTP: every POST is authenticated on its own, and the agent identity for the
        // request comes from the API key, never from an MCP session id.
        app.mcpStatelessStreamableHttp(
            path = "/mcp",
            enableDnsRebindingProtection = true,
            allowedHosts = c.allowedHosts,
        ) {
            McpServerFactory.create(tools, call.attributes[AgentContextKey])
        }
        app.routing {
            approvals.install(this)
            get("/healthz") { call.respondText("ok", ContentType.Text.Plain) }
        }
    }
}

fun main(args: Array<String>) {
    val log = LoggerFactory.getLogger("a2wp-kt")
    val configPath = Path.of(args.firstOrNull() ?: System.getenv("A2WP_CONFIG") ?: "config/wallet.json")
    val cfg = ConfigLoader.load(configPath)
    val app = WalletApp(cfg)
    val listen = cfg.config.listen
    log.info(
        "a2wp-kt listening on {}:{} (public {}), {} agent(s), allowPrivateNetworks={}",
        listen.host, listen.port, cfg.config.publicBaseUrl, cfg.config.agents.size, cfg.config.allowPrivateNetworks,
    )
    embeddedServer(Netty, port = listen.port, host = listen.host) { app.module(this) }.start(wait = true)
}
