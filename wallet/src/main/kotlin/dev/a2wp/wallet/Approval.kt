package dev.a2wp.wallet

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.header
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.header
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingCall
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.slf4j.LoggerFactory
import java.net.URI
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.seconds

/**
 * The approval page at /approve/{id} and its result pages, styled after docs/design/WalletApprove and WalletDone.
 * GET is side-effect free (chat apps prefetch links); only POST acts. No JavaScript.
 * The agent relays the link but must not complete it; an optional principal passcode enforces that.
 */
class ApprovalPages(
    private val ops: OperationManager,
    private val cfg: LoadedConfig,
    private val store: EncryptedStore,
) {
    private val log = LoggerFactory.getLogger(ApprovalPages::class.java)
    private val failedAttempts = ConcurrentHashMap<String, Int>()
    private val publicOrigin: String = URI(cfg.config.publicBaseUrl).let { "${it.scheme}://${it.authority}" }

    fun install(route: Route) {
        route.get("/assets/fonts/{name}") {
            val bytes = FONTS[call.parameters["name"].orEmpty()]
            if (bytes == null) {
                call.respondText("not found", ContentType.Text.Plain, HttpStatusCode.NotFound)
            } else {
                call.response.header(HttpHeaders.CacheControl, "public, max-age=604800, immutable")
                call.response.header("X-Content-Type-Options", "nosniff")
                call.respondBytes(bytes, ContentType("font", "woff2"))
            }
        }

        route.get("/approve/{id}") {
            val p = ops.interaction(call.parameters["id"].orEmpty())
                ?: return@get call.page(HttpStatusCode.NotFound, "Link not valid", null, notFound())
            if (isOpen(p)) call.page(HttpStatusCode.OK, title(p), p, approveScreen(p, null))
            else call.page(HttpStatusCode.OK, RESULT_TITLE, p, resultFor(p))
        }

        route.post("/approve/{id}") {
            val origin = call.request.header(HttpHeaders.Origin)
            if (origin != null && origin != "null" && origin != publicOrigin) {
                return@post call.page(
                    HttpStatusCode.Forbidden, "Request refused", null,
                    resultScreen(Icon.STOP, "Request refused", "This form can only be sent from the wallet's own page."),
                )
            }
            val id = call.parameters["id"].orEmpty()
            val p = ops.interaction(id) ?: return@post call.page(HttpStatusCode.NotFound, "Link not valid", null, notFound())
            if (!isOpen(p)) return@post call.page(HttpStatusCode.OK, RESULT_TITLE, p, resultFor(p))
            val params = call.receiveParameters()
            val action = params["action"]
            if (action == "deny") {
                ops.resolveInteraction(id, InteractionOutcome.Denied)
                log.info("interaction for {} denied by user", p.operation.id)
                return@post call.page(HttpStatusCode.OK, "Declined", p, declined(p))
            }
            if (action != "approve") {
                return@post call.page(HttpStatusCode.BadRequest, title(p), p, approveScreen(p, "Choose Approve or Decline."))
            }

            val principal = cfg.principal(p.principalId)
            principal.approvalPasscodeSha256?.let { expected ->
                val given = params["passcode"].orEmpty()
                val ok = MessageDigest.isEqual(
                    ApiKeyAuthenticator.sha256Hex(given).toByteArray(),
                    expected.lowercase().toByteArray(),
                )
                if (!ok) {
                    val n = failedAttempts.merge(id, 1, Int::plus) ?: 1
                    if (n >= MAX_PASSCODE_ATTEMPTS) {
                        ops.resolveInteraction(id, InteractionOutcome.Denied)
                        return@post call.page(
                            HttpStatusCode.Forbidden, "Declined", p,
                            declined(p, "Too many wrong passcodes, so the wallet declined the request."),
                        )
                    }
                    return@post call.page(HttpStatusCode.Forbidden, title(p), p, approveScreen(p, "That passcode is not right."))
                }
            }

            var txCode: String? = null
            val prompt = p.prompt
            if (prompt is ApprovalPrompt.Acquire && prompt.txCodeRequired) {
                val t = params["tx_code"].orEmpty().trim()
                val bad = t.isEmpty() || t.length > 64 ||
                    (prompt.txCodeLength != null && t.length != prompt.txCodeLength) ||
                    (prompt.txCodeNumeric && !t.all { it.isDigit() })
                if (bad) {
                    return@post call.page(HttpStatusCode.BadRequest, title(p), p, approveScreen(p, "Enter the code exactly as the issuer showed it."))
                }
                txCode = t
            }
            if (!ops.resolveInteraction(id, InteractionOutcome.Approved(txCode))) {
                return@post call.page(HttpStatusCode.OK, RESULT_TITLE, p, resultFor(p))
            }
            log.info("interaction for {} approved by user", p.operation.id)
            // Show the real outcome, not an optimistic one: wait briefly for the operation to finish.
            withTimeoutOrNull(RESULT_WAIT) { p.operation.state.first { it.status.terminal } }
            call.page(HttpStatusCode.OK, RESULT_TITLE, p, resultFor(p))
        }
    }

    private fun isOpen(p: PendingInteraction): Boolean =
        !p.resolved && Instant.now().isBefore(p.expiresAt) &&
            p.operation.snapshot().status == OpStatus.INTERACTION_REQUIRED

    private fun agentName(p: PendingInteraction): String =
        cfg.config.agents.firstOrNull { it.id == p.operation.agentId }?.name ?: p.operation.agentId

    private fun approvalMode(p: PendingInteraction): ApprovalMode? =
        cfg.config.agents.firstOrNull { it.id == p.operation.agentId }?.policy?.requireApproval

    /** The name the user sees for the verifier: their own label for this verified client_id, else the verified host. */
    private fun verifierName(pr: ApprovalPrompt.Present): String =
        pr.verifierConfiguredName ?: PresentService.verifierDisplayName(pr.verifierClientId)

    private fun issuerHost(issuer: String): String = runCatching { URI(issuer).host }.getOrNull() ?: issuer

    private fun title(p: PendingInteraction): String = when (val pr = p.prompt) {
        is ApprovalPrompt.Present -> "Share with ${verifierName(pr)}?"
        is ApprovalPrompt.Acquire -> "Add a credential from ${issuerHost(pr.issuer)}?"
    }

    // ---------------------------------------------------------------- approval screen

    private fun approveScreen(p: PendingInteraction, error: String?): String = buildString {
        append("""<div class="intro"><h1>${esc(title(p))}</h1>""")
        when (val pr = p.prompt) {
            is ApprovalPrompt.Present ->
                append("""<p class="lead">Your agent's wallet asks you before it shares the agent's credential with ${esc(verifierName(pr))}.</p></div>""")
            is ApprovalPrompt.Acquire ->
                append("""<p class="lead">Your agent's wallet asks you before adding a credential from ${esc(issuerHost(pr.issuer))}.</p></div>""")
        }
        if (error != null) append("""<p class="error" role="alert">${esc(error)}</p>""")

        when (val pr = p.prompt) {
            is ApprovalPrompt.Present -> {
                append(badge("Verified requester", PresentService.verifierDisplayName(pr.verifierClientId), pr.verifierClientId))
                append("""<section aria-labelledby="share-title" class="rows"><h2 id="share-title">WHAT WILL BE SHARED</h2>""")
                orderClaims(pr.claims).forEach { (k, v) ->
                    val mono = if (k in MONO_CLAIMS) " mono" else ""
                    append("""<div class="row"><span class="k">${esc(label(k))}</span><span class="v$mono">${esc(displayValue(k, v))}</span></div>""")
                }
                append("</section>")
                if (pr.withheldClaims.isNotEmpty()) {
                    append("""<p class="withheld">Asked for but not shared, because your settings don't allow it: ${esc(pr.withheldClaims.joinToString(", ") { label(it) })}.</p>""")
                }
            }
            is ApprovalPrompt.Acquire -> {
                append(badge("Allowed issuer", issuerHost(pr.issuer), pr.issuer))
                append("""<section aria-labelledby="cred-title" class="rows"><h2 id="cred-title">CREDENTIAL</h2>""")
                pr.credentialConfigurations.forEach {
                    append("""<div class="row"><span class="k">Type</span><span class="v">${esc(it)}</span></div>""")
                }
                append("</section>")
            }
        }

        append("""<form method="post" class="actions">""")
        val pr = p.prompt
        if (pr is ApprovalPrompt.Acquire && pr.txCodeRequired) {
            val mode = if (pr.txCodeNumeric) """ inputmode="numeric" pattern="[0-9]*"""" else ""
            val len = pr.txCodeLength?.let { """ maxlength="$it" minlength="$it"""" } ?: ""
            val hint = pr.txCodeDescription?.let { """<span class="hint">${esc(it)}</span>""" } ?: ""
            append("""<label class="field"><span class="flabel">Code from the issuer</span>$hint""")
            append("""<input class="code" name="tx_code" autocomplete="one-time-code" required$mode$len></label>""")
        }
        if (cfg.principal(p.principalId).approvalPasscodeSha256 != null) {
            append("""<label class="field"><span class="flabel">Your approval passcode</span>""")
            append("""<input name="passcode" type="password" autocomplete="current-password" required></label>""")
        }
        append("""<p class="note">Only you can approve this. The wallet holds your agent's credential; the agent itself never sees it.</p>""")
        append("""<div class="btns"><button class="btn secondary" name="action" value="deny" formnovalidate>Decline</button>""")
        append("""<button class="btn primary" name="action" value="approve">Approve</button></div>""")
        append("""<p class="expiry">This request expires at ${esc(time(p.expiresAt))}.</p></form>""")
    }

    private fun badge(title: String, host: String, full: String): String =
        """<div class="badge">$SHIELD<span class="badge-text"><span class="badge-title">${esc(title)}</span>""" +
            """<span class="badge-host" title="${esc(full)}">${esc(host)}</span></span></div>"""

    // ---------------------------------------------------------------- result screens

    /** The result for an interaction that is no longer open, from the operation's real state. Side-effect free. */
    private fun resultFor(p: PendingInteraction): String {
        val s = p.operation.snapshot()
        val outcome = if (p.outcome.isCompleted) p.outcome.getCompleted() else null
        return when {
            s.status == OpStatus.COMPLETED -> when (val pr = p.prompt) {
                is ApprovalPrompt.Present -> shared(p, pr, s)
                is ApprovalPrompt.Acquire -> added(p, pr, s)
            }
            outcome == InteractionOutcome.Denied -> declined(p)
            outcome == InteractionOutcome.Expired || s.error?.code == ErrorCodes.INTERACTION_EXPIRED ||
                s.error?.code == ErrorCodes.OPERATION_EXPIRED -> expired()
            s.status == OpStatus.CANCELLED ->
                resultScreen(Icon.STOP, "Request cancelled", "Your agent cancelled this request. Nothing was shared.")
            s.status == OpStatus.FAILED -> failed(p, s)
            !s.status.terminal && outcome !is InteractionOutcome.Approved -> expired()
            else -> working(p)
        }
    }

    private fun shared(p: PendingInteraction, pr: ApprovalPrompt.Present, s: OperationSnapshot): String {
        val name = verifierName(pr)
        val again = if (approvalMode(p) == ApprovalMode.FIRST_PRESENTATION_TO_VERIFIER) {
            " Next time it shares the same details with $name, its wallet won't ask you again."
        } else ""
        val agentName = pr.claims.firstOrNull { it.first == "agent_name" }?.second ?: "Agent credential"
        return resultScreen(
            Icon.CHECK, "Shared with $name", "Your agent can finish.$again",
            card = card(credentialLabel(pr.credentialVct), agentName, "${pr.claims.size} details shared · ${time(s.completedAt ?: Instant.now())}"),
        )
    }

    private fun added(p: PendingInteraction, pr: ApprovalPrompt.Acquire, s: OperationSnapshot): String {
        val handle = s.result?.get("credentials")?.jsonArray?.firstOrNull()?.jsonObject?.get("credential_handle")
            ?.let { (it as? JsonPrimitive)?.content }
        val stored = handle?.let { h -> store.credentialsFor(p.operation.agentId).firstOrNull { it.handle == h } }
        val values = stored?.let { runCatching { PresentService.claimValues(it).first }.getOrNull() }.orEmpty()
        return resultScreen(
            Icon.CHECK, "Credential added", "It stays in your agent's wallet. Your agent can use it, but never sees it.",
            card = card(
                stored?.let { credentialLabel(it.vct) } ?: "Credential",
                values["agent_name"] ?: "Credential",
                "From ${issuerHost(pr.issuer)} · ${time(s.completedAt ?: Instant.now())}",
            ),
        )
    }

    private fun declined(p: PendingInteraction, extra: String? = null): String {
        val body = when (val pr = p.prompt) {
            is ApprovalPrompt.Present -> "Nothing was shared with ${verifierName(pr)}. Your agent will be told you declined."
            is ApprovalPrompt.Acquire -> "The credential was not added. Your agent will be told you declined."
        }
        val title = if (p.prompt is ApprovalPrompt.Present) "Not shared" else "Not added"
        return resultScreen(Icon.STOP, title, listOfNotNull(extra, body).joinToString(" "))
    }

    private fun expired(): String =
        resultScreen(Icon.CLOCK, "This request has expired", "Nothing was shared. If you still want to go ahead, ask your agent to start again.")

    private fun notFound(): String =
        resultScreen(Icon.STOP, "This link isn't valid", "It may have expired. If you still want to go ahead, ask your agent to start again.")

    private fun working(p: PendingInteraction): String = when (val pr = p.prompt) {
        is ApprovalPrompt.Present ->
            resultScreen(Icon.CLOCK, "Sharing with ${verifierName(pr)}", "Approved. The wallet is still sending the details; your agent will see the result.")
        is ApprovalPrompt.Acquire ->
            resultScreen(Icon.CLOCK, "Adding the credential", "Thanks. The wallet is still collecting it; your agent will see the result.")
    }

    private fun failed(p: PendingInteraction, s: OperationSnapshot): String {
        val name = when (val pr = p.prompt) {
            is ApprovalPrompt.Present -> verifierName(pr)
            is ApprovalPrompt.Acquire -> issuerHost(pr.issuer)
        }
        if (s.error?.code == ErrorCodes.OUTCOME_UNKNOWN) {
            return resultScreen(
                Icon.ALERT, "Not sure it arrived",
                "The details may or may not have reached $name. Your agent should check with you before trying again.",
            )
        }
        return when (p.prompt) {
            is ApprovalPrompt.Present ->
                resultScreen(Icon.ALERT, "Couldn't share", "$name didn't accept the details. Your agent will see what went wrong.")
            is ApprovalPrompt.Acquire ->
                resultScreen(Icon.ALERT, "Couldn't add the credential", "$name didn't issue it. If you entered a code, check it and ask your agent to try again.")
        }
    }

    private enum class Icon { CHECK, STOP, CLOCK, ALERT }

    private fun resultScreen(icon: Icon, title: String, body: String, card: String? = null): String = buildString {
        val (cls, svg) = when (icon) {
            Icon.CHECK -> "ok" to """<svg width="44" height="44" viewBox="0 0 24 24" aria-hidden="true" fill="none" stroke="#0B6B49" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"><path d="M5 12.5l4.5 4.5L19 7.5"/></svg>"""
            Icon.STOP -> "neutral" to """<svg width="40" height="40" viewBox="0 0 24 24" aria-hidden="true" fill="none" stroke="#4B5260" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"><path d="M7 7l10 10M17 7L7 17"/></svg>"""
            Icon.CLOCK -> "neutral" to """<svg width="40" height="40" viewBox="0 0 24 24" aria-hidden="true" fill="none" stroke="#4B5260" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="8.5"/><path d="M12 7.5V12l3 2"/></svg>"""
            Icon.ALERT -> "warn" to """<svg width="40" height="40" viewBox="0 0 24 24" aria-hidden="true" fill="none" stroke="#9A3412" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"><path d="M12 7v6M12 17h.01"/></svg>"""
        }
        append("""<div class="result"><span class="icon $cls">$svg</span><h1>${esc(title)}</h1>""")
        append("""<p class="lead center">${esc(body)}</p></div>""")
        if (card != null) append(card)
        append("""<p class="close">You can close this page.</p>""")
    }

    private fun card(label: String, title: String, meta: String): String =
        """<div class="card"><span class="card-label">${esc(label.uppercase())}</span>""" +
            """<span class="card-title">${esc(title)}</span><span class="card-meta">${esc(meta)}</span></div>"""

    // ---------------------------------------------------------------- page shell

    private suspend fun RoutingCall.page(status: HttpStatusCode, title: String, p: PendingInteraction?, body: String) {
        response.header(HttpHeaders.CacheControl, "no-store")
        response.header(
            "Content-Security-Policy",
            "default-src 'none'; style-src 'unsafe-inline'; font-src 'self'; form-action 'self'; frame-ancestors 'none'; base-uri 'none'",
        )
        response.header("Referrer-Policy", "no-referrer")
        response.header("X-Content-Type-Options", "nosniff")
        // This is the agent's wallet; the page is shown to the agent's owner (the principal), who approves.
        val agent = p?.let { agentName(it) }
        val header = if (agent.isNullOrBlank()) "Agent wallet" else "$agent · agent wallet"
        respondText(
            """<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<meta name="robots" content="noindex"><title>${esc(if (title == RESULT_TITLE) header else "$title · $header")}</title><style>$CSS</style></head>
<body><main class="screen"><div class="brand">$WALLET_ICON${esc(header)}</div>$body</main></body></html>""",
            ContentType.Text.Html.withParameter("charset", "utf-8"),
            status,
        )
    }

    companion object {
        private const val MAX_PASSCODE_ATTEMPTS = 5
        private const val RESULT_TITLE = "Result"
        private val RESULT_WAIT = 8.seconds
        private val TIME = DateTimeFormatter.ofPattern("HH:mm")

        private val FONTS: Map<String, ByteArray> = listOf("Geist-Variable.woff2", "GeistMono-Variable.woff2")
            .mapNotNull { n -> ApprovalPages::class.java.getResourceAsStream("/assets/fonts/$n")?.use { n to it.readBytes() } }
            .toMap()

        private val CLAIM_ORDER = listOf("agent_id", "agent_name", "provider", "operator", "accreditation_level")
        private val LABELS = mapOf(
            "agent_id" to "Agent ID",
            "agent_name" to "Agent name",
            "provider" to "Runs on",
            "operator" to "Operator",
            "accreditation_level" to "Accreditation",
        )
        private val MONO_CLAIMS = setOf("agent_id")

        fun label(claim: String): String = LABELS[claim] ?: claim.replace('_', ' ').replaceFirstChar { it.uppercase() }

        fun displayValue(claim: String, value: String): String =
            if (claim == "accreditation_level") value.replaceFirstChar { it.uppercase() } else value

        fun orderClaims(claims: List<Pair<String, String>>): List<Pair<String, String>> =
            claims.sortedBy { (k, _) -> CLAIM_ORDER.indexOf(k).let { if (it < 0) Int.MAX_VALUE else it } }

        private fun credentialLabel(vct: String): String =
            if (vct.contains("/credentials/agent/")) "Agent credential" else "Credential"

        private fun time(i: Instant): String = TIME.format(i.atZone(ZoneId.systemDefault()))

        fun esc(s: String): String = buildString(s.length) {
            for (c in s) when (c) {
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '&' -> append("&amp;")
                '"' -> append("&quot;")
                '\'' -> append("&#39;")
                else -> append(c)
            }
        }

        private const val WALLET_ICON =
            """<svg width="22" height="22" viewBox="0 0 24 24" aria-hidden="true" fill="none" stroke="#0E1116" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="6" width="18" height="13" rx="3"/><path d="M16 12.5h.01M3 10h18"/></svg>"""

        private const val SHIELD =
            """<svg width="22" height="22" viewBox="0 0 24 24" aria-hidden="true" fill="none" stroke="#0B6B49" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" style="flex:none"><path d="M12 3l7 3v5c0 4.5-3 8.5-7 10-4-1.5-7-5.5-7-10V6z"/><path d="M8.5 12l2.5 2.5 4.5-4.5"/></svg>"""

        // Registry brand tokens from docs/design/README.md. Light only, as in the design.
        private const val CSS = """
@font-face{font-family:"Geist";src:url("/assets/fonts/Geist-Variable.woff2") format("woff2");font-weight:100 900;font-style:normal;font-display:swap}
@font-face{font-family:"Geist Mono";src:url("/assets/fonts/GeistMono-Variable.woff2") format("woff2");font-weight:100 900;font-style:normal;font-display:swap}
:root{--white:#FFFFFF;--ground:#F5F6F8;--ink:#0E1116;--muted:#4B5260;--line:#E3E6EB;--btn-line:#C9CED6;--blue:#2443E6;--blue-soft:#D6DDFF;--ok:#0B6B49;--ok-bg:#E6F6EF;--warn:#9A3412;--warn-bg:#FDEFE6;--neutral-bg:#EEF0F3;color-scheme:light}
*{box-sizing:border-box}
html,body{margin:0;background:var(--white);color:var(--ink)}
body{font-family:"Geist",system-ui,-apple-system,"Segoe UI",sans-serif;-webkit-font-smoothing:antialiased}
.mono,.badge-host,.code{font-family:"Geist Mono",ui-monospace,SFMono-Regular,Menlo,monospace}
.screen{width:100%;max-width:390px;min-height:100vh;min-height:100dvh;margin:0 auto;padding:28px 20px 24px;display:flex;flex-direction:column;gap:24px;background:var(--white)}
@media (min-width:480px){html,body{background:var(--ground)}.screen{min-height:844px;margin:40px auto;border:1px solid var(--line);border-radius:24px}}
.brand{display:flex;align-items:center;gap:10px;font-size:14px;font-weight:600;color:var(--muted)}
.intro{display:flex;flex-direction:column;gap:10px}
h1{margin:0;font-size:30px;line-height:1.15;font-weight:600;letter-spacing:-0.025em;overflow-wrap:anywhere}
.lead{margin:0;font-size:15px;line-height:1.5;color:var(--muted)}
.lead.center{max-width:300px}
.error{margin:0;padding:12px 14px;border-radius:12px;background:var(--warn-bg);color:var(--warn);font-size:14px;font-weight:600}
.badge{display:flex;align-items:center;gap:12px;padding:14px 16px;border-radius:14px;background:var(--ok-bg)}
.badge-text{display:flex;flex-direction:column;gap:2px;min-width:0}
.badge-title{font-size:14px;font-weight:600;color:var(--ok)}
.badge-host{font-size:13px;color:var(--ok);overflow-wrap:anywhere}
.rows{display:flex;flex-direction:column;gap:4px}
.rows h2{margin:0 0 8px;font-size:13px;font-weight:600;letter-spacing:0.06em;color:var(--muted)}
.row{display:flex;justify-content:space-between;gap:12px;padding:12px 0;border-bottom:1px solid var(--line);font-size:15px}
.row:last-child{border-bottom:0}
.row .k{color:var(--muted);flex:none}
.row .v{text-align:right;overflow-wrap:anywhere;min-width:0}
.withheld{margin:0;font-size:13px;line-height:1.5;color:var(--muted)}
.actions{margin-top:auto;display:flex;flex-direction:column;gap:16px}
.field{display:flex;flex-direction:column;gap:6px}
.flabel{font-size:14px;font-weight:600}
.hint{font-size:13px;color:var(--muted)}
input{font:inherit;font-size:16px;height:52px;padding:0 14px;width:100%;border:1px solid var(--btn-line);border-radius:14px;background:var(--white);color:var(--ink)}
input.code{font-size:22px;letter-spacing:0.2em}
input:focus,.btn:focus-visible{outline:2px solid var(--blue);outline-offset:2px}
.note{margin:0;font-size:13px;line-height:1.5;color:var(--muted)}
.btns{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:12px}
.btn{height:52px;border-radius:14px;font:inherit;font-size:16px;font-weight:600;cursor:pointer}
.btn.secondary{border:1px solid var(--btn-line);background:var(--white);color:var(--ink)}
.btn.primary{border:0;background:var(--ink);color:var(--white)}
.expiry{margin:0;font-size:12px;color:var(--muted);text-align:center}
.result{margin-top:72px;display:flex;flex-direction:column;align-items:center;gap:20px;text-align:center}
.icon{width:88px;height:88px;border-radius:44px;display:flex;align-items:center;justify-content:center}
.icon.ok{background:var(--ok-bg)}.icon.neutral{background:var(--neutral-bg)}.icon.warn{background:var(--warn-bg)}
.card{border-radius:18px;padding:20px;display:flex;flex-direction:column;gap:14px;background:var(--blue);color:var(--white)}
.card-label{font-size:12px;font-weight:600;letter-spacing:0.08em;color:var(--blue-soft)}
.card-title{font-size:22px;font-weight:600;letter-spacing:-0.02em;overflow-wrap:anywhere}
.card-meta{font-size:13px;color:var(--blue-soft)}
.close{margin:auto 0 0;font-size:15px;color:var(--muted);text-align:center}
"""
    }
}
