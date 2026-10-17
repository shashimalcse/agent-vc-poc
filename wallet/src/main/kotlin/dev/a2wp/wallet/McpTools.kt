package dev.a2wp.wallet

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.time.Duration.Companion.milliseconds

/**
 * A2WP -01 as MCP tools: one tool per call (Submit, Get, Cancel). Pure functions of (agent, arguments) ->
 * CallToolResult, so they are testable without HTTP. Nothing secret is ever placed in a result: only operation
 * state and the counterparty's continuation.
 */
class WalletTools(
    private val ops: OperationManager,
    private val acquire: suspend (OperationContext, AgentContext, ParsedOffer) -> Unit,
    private val present: suspend (OperationContext, AgentContext, ParsedPresentationRequest) -> Unit,
    private val maxInputChars: Int = 16_384,
) {
    /**
     * Submit: the request exactly as the agent received it. The wallet identifies it. Only a submission without a
     * request string is rejected; a request the wallet cannot handle becomes an operation that fails with
     * unsupported-request. The dedupe key is the request string itself.
     */
    suspend fun submit(agent: AgentContext, args: JsonObject?): CallToolResult = guarded {
        val raw = (args?.get("request") as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: throw RequestError(ErrorCodes.UNSUPPORTED_REQUEST, "The submission has no request string.")
        val parsed = try {
            RequestParsing.classify(raw, maxInputChars)
        } catch (e: RequestError) {
            return@guarded created(ops.create(agent, null, raw) { throw OperationFailure(e.code, e.detail) })
        }
        created(
            when (parsed) {
                is ParsedRequest.Offer -> ops.create(agent, OpKind.ACQUIRE, raw) { ctx -> acquire(ctx, agent, parsed.offer) }
                is ParsedRequest.Presentation -> ops.create(agent, OpKind.PRESENT, raw) { ctx -> present(ctx, agent, parsed.request) }
            },
        )
    }

    suspend fun get(agent: AgentContext, args: JsonObject?): CallToolResult = guarded {
        val op = operationArg(agent, args)
        // wait_seconds is a binding convenience: out-of-range or non-numeric values are clamped, not rejected.
        val wait = ((args?.get("wait_seconds") as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull ?: 0.0)
            .coerceIn(0.0, MAX_WAIT_SECONDS.toDouble())
        operationResult(ops.await(op, (wait * 1000).toLong().milliseconds))
    }

    suspend fun cancel(agent: AgentContext, args: JsonObject?): CallToolResult = guarded {
        val op = operationArg(agent, args)
        when (val r = ops.cancel(op)) {
            is OperationManager.CancelOutcome.Cancelled -> operationResult(r.snapshot)
            is OperationManager.CancelOutcome.NotCancellable ->
                errorResult(ErrorCodes.NOT_CANCELLABLE, "Operation ${op.id} has passed its commit point or has completed or failed.")
        }
    }

    private fun operationArg(agent: AgentContext, args: JsonObject?): Operation {
        val id = (args?.get("id") as? JsonPrimitive)?.takeIf { it.isString }?.content
        return id?.let { ops.get(agent, it) } ?: throw RequestError(ErrorCodes.NOT_FOUND, "No such operation.")
    }

    private fun created(r: OperationManager.CreateResult): CallToolResult = when (r) {
        is OperationManager.CreateResult.Created -> operationResult(r.op.snapshot())
        is OperationManager.CreateResult.Existing -> operationResult(r.op.snapshot(), duplicate = true)
        OperationManager.CreateResult.TooMany ->
            throw RequestError(ErrorCodes.DENIED, "Too many active operations; wait for some to finish.")
    }

    private inline fun guarded(block: () -> CallToolResult): CallToolResult =
        try {
            block()
        } catch (e: RequestError) {
            errorResult(e.code, e.detail)
        }

    companion object {
        const val MAX_WAIT_SECONDS = 25

        /** A rejection: an error and no operation. */
        fun errorResult(code: String, detail: String): CallToolResult = CallToolResult(
            content = listOf(TextContent("Rejected: $code. $detail")),
            isError = true,
            structuredContent = buildJsonObject {
                putJsonObject("error") {
                    put("code", code)
                    put("detail", detail)
                }
            },
        )

        fun operationResult(s: OperationSnapshot, duplicate: Boolean = false): CallToolResult = CallToolResult(
            content = listOf(TextContent(summary(s, duplicate))),
            structuredContent = buildJsonObject { put("operation", s.toJson()) },
        )

        /** One line telling the agent what happened and what to do next. */
        fun summary(s: OperationSnapshot, duplicate: Boolean = false): String {
            val prefix = if (duplicate) "Same request as existing operation ${s.id}. " else ""
            val poll = "Call get with id=${s.id} and wait_seconds=25 until it is completed, failed or cancelled."
            val next = s.continuation?.let {
                " Open this continuation link in the same browser session where you found the request: $it"
            } ?: ""
            val body = when (s.status) {
                OpStatus.INTERACTION_REQUIRED -> "Operation ${s.id} is pending and needs the user. Send the user this link and ask them to open it: " +
                    "${s.interaction?.uri} (expires ${s.interaction?.expiresAt}). Do not open the link yourself. Then ${poll.replaceFirstChar { it.lowercase() }}"
                OpStatus.PENDING, OpStatus.PROCESSING -> "Operation ${s.id} is pending. $poll"
                OpStatus.COMPLETED -> when (s.kind) {
                    OpKind.ACQUIRE -> "Operation ${s.id} completed: the credential is stored in the wallet."
                    else -> "Operation ${s.id} completed: the verifier accepted the presentation."
                } + next
                OpStatus.FAILED -> "Operation ${s.id} failed: ${s.error?.code}. ${s.error?.detail ?: ""}".trim() + nextStep(s.error?.code) + next
                OpStatus.CANCELLED -> "Operation ${s.id} was cancelled."
            }
            return prefix + body
        }

        private fun nextStep(code: String?): String = when (code) {
            ErrorCodes.OUTCOME_UNKNOWN -> " Do not submit this request again without asking the user."
            ErrorCodes.NO_MATCHING_CREDENTIAL -> " A suitable credential is needed first."
            ErrorCodes.DENIED -> " Do not submit the same request again."
            ErrorCodes.EXPIRED -> " Ask the user whether to start again; a fresh request or offer may be needed."
            else -> ""
        }

        fun stringSchema(description: String): JsonElement = buildJsonObject {
            put("type", "string")
            put("description", description)
        }
    }
}

object McpServerFactory {

    const val INSTRUCTIONS = """This is your own credential wallet, the agent's wallet (A2WP -01 over MCP). It holds your credentials and keys and decides what to disclose; you never see credentials or keys. Some actions need approval from your owner (the user).
- When you get a credential offer link (openid-credential-offer://...) or a site asks you to prove something with an openid4vp://... link, call submit with the link exactly as you received it. Never open such links yourself.
- submit returns an operation. Call get with its id (wait_seconds up to 25) until it is completed, failed or cancelled.
- If a pending operation has an interaction, send interaction.uri to the user and ask them to open it. Never open it yourself, and never ask the user for PINs or codes; the wallet page collects them.
- If the operation has a continuation, open it in the same browser session where you found the request. That is how the site continues.
- After outcome-unknown, do not submit the same request again without asking the user."""

    fun create(tools: WalletTools, agent: AgentContext): Server {
        val server = Server(
            Implementation(name = "a2wp-kt", version = "0.2.0", title = "A2WP wallet"),
            ServerOptions(capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false))),
            INSTRUCTIONS,
        )
        server.addTool(
            name = "submit",
            title = "Submit a credential offer or presentation request",
            description = "Hand the wallet a credential offer link or a presentation request link exactly as you received it " +
                "(openid-credential-offer://..., openid4vp://... or https). The wallet works out which it is, checks who is asking, " +
                "applies the policy set by your owner, may ask your owner to approve, and acts. Returns an operation at once; follow it with get.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put("request", WalletTools.stringSchema("The offer or request link, unchanged."))
                },
                required = listOf("request"),
            ),
            toolAnnotations = ToolAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true, openWorldHint = true),
        ) { req -> tools.submit(agent, req.arguments) }

        server.addTool(
            name = "get",
            title = "Get an operation",
            description = "Get the current state of an operation. With wait_seconds (0-25), waits up to that long for it to change.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put("id", WalletTools.stringSchema("The operation id returned by submit."))
                    putJsonObject("wait_seconds") {
                        put("type", "integer")
                        put("minimum", 0)
                        put("maximum", WalletTools.MAX_WAIT_SECONDS)
                        put("description", "Seconds to wait for a change (long poll). Default 0.")
                    }
                },
                required = listOf("id"),
            ),
            toolAnnotations = ToolAnnotations(readOnlyHint = true, idempotentHint = true, openWorldHint = false),
        ) { req -> tools.get(agent, req.arguments) }

        server.addTool(
            name = "cancel",
            title = "Cancel an operation",
            description = "Cancel an operation. Only possible before the wallet has redeemed the offer or sent the presentation.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put("id", WalletTools.stringSchema("The operation id to cancel."))
                },
                required = listOf("id"),
            ),
            toolAnnotations = ToolAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true, openWorldHint = false),
        ) { req -> tools.cancel(agent, req.arguments) }
        return server
    }
}
