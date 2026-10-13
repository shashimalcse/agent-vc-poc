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
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.time.Duration.Companion.milliseconds

/**
 * A2WP as MCP tools. Pure functions of (agent, arguments) -> CallToolResult, so they are testable without HTTP.
 * Nothing secret is ever placed in a result: only operation state, handles, vct, issuer and verifier names.
 */
class WalletTools(
    private val ops: OperationManager,
    private val acquire: suspend (OperationContext, AgentContext, ParsedOffer) -> Unit,
    private val present: suspend (OperationContext, AgentContext, ParsedPresentationRequest) -> Unit,
    private val maxInputChars: Int = 16_384,
) {
    suspend fun acquireCredential(agent: AgentContext, args: JsonObject?): CallToolResult = guarded {
        val offer = RequestParsing.parseOffer(stringArg(args, "offer"), maxInputChars)
        created(ops.create(agent, OpKind.ACQUIRE, offer.dedupeKey) { ctx -> acquire(ctx, agent, offer) })
    }

    suspend fun presentCredential(agent: AgentContext, args: JsonObject?): CallToolResult = guarded {
        val req = RequestParsing.parsePresentationRequest(stringArg(args, "request"), maxInputChars)
        created(ops.create(agent, OpKind.PRESENT, req.dedupeKey) { ctx -> present(ctx, agent, req) })
    }

    suspend fun getOperation(agent: AgentContext, args: JsonObject?): CallToolResult = guarded {
        val id = stringArg(args, "operation_id")
        val wait = args?.get("wait_seconds")?.let { el ->
            val d = (el as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
                ?: throw RequestError(ErrorCodes.INVALID_REQUEST, "wait_seconds must be a number from 0 to 25.")
            if (d < 0 || d > MAX_WAIT_SECONDS) throw RequestError(ErrorCodes.INVALID_REQUEST, "wait_seconds must be a number from 0 to 25.")
            d
        } ?: 0.0
        val op = ops.get(agent, id) ?: throw RequestError(ErrorCodes.NOT_FOUND, "No such operation.")
        operationResult(ops.await(op, (wait * 1000).toLong().milliseconds))
    }

    suspend fun cancelOperation(agent: AgentContext, args: JsonObject?): CallToolResult = guarded {
        val id = stringArg(args, "operation_id")
        val op = ops.get(agent, id) ?: throw RequestError(ErrorCodes.NOT_FOUND, "No such operation.")
        when (val r = ops.cancel(op)) {
            is OperationManager.CancelOutcome.Cancelled -> operationResult(r.snapshot)
            is OperationManager.CancelOutcome.NotCancellable -> CallToolResult(
                content = listOf(TextContent("Operation ${op.id} cannot be cancelled (status ${r.snapshot.status.wire}); it has passed the point of no return or already finished.")),
                isError = true,
                structuredContent = buildJsonObject {
                    put("operation", r.snapshot.toJson())
                    putJsonObject("error") {
                        put("code", ErrorCodes.NOT_CANCELLABLE)
                        put("detail", "The operation has passed its commit point or is already terminal.")
                    }
                },
            )
        }
    }

    private fun created(r: OperationManager.CreateResult): CallToolResult = when (r) {
        is OperationManager.CreateResult.Created -> operationResult(r.op.snapshot())
        is OperationManager.CreateResult.Existing -> operationResult(r.op.snapshot(), duplicate = true)
        OperationManager.CreateResult.TooMany ->
            throw RequestError(ErrorCodes.TOO_MANY_OPERATIONS, "Too many active operations; wait for some to finish.")
    }

    private inline fun guarded(block: () -> CallToolResult): CallToolResult =
        try {
            block()
        } catch (e: RequestError) {
            errorResult(e.code, e.detail)
        }

    companion object {
        const val MAX_WAIT_SECONDS = 25

        fun stringArg(args: JsonObject?, name: String): String {
            val v = args?.get(name)
            if (v !is JsonPrimitive || !v.isString) throw RequestError(ErrorCodes.INVALID_REQUEST, "'$name' must be a string.")
            return v.content
        }

        fun errorResult(code: String, detail: String): CallToolResult = CallToolResult(
            content = listOf(TextContent("Request rejected: $code. $detail")),
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
            val poll = "Call get_operation with operation_id=${s.id} and wait_seconds=25 to wait for the result."
            val body = when (s.status) {
                OpStatus.PENDING, OpStatus.PROCESSING -> "Operation ${s.id} (${s.kind.wire}) is ${s.status.wire}. $poll"
                OpStatus.INTERACTION_REQUIRED -> "Operation ${s.id} needs the user. Send the user this link and ask them to open it: " +
                    "${s.interaction?.uri} (expires ${s.interaction?.expiresAt}). Do not open the link yourself. Then ${poll.replaceFirstChar { it.lowercase() }}"
                OpStatus.COMPLETED -> when (s.kind) {
                    OpKind.ACQUIRE -> {
                        val creds = s.result?.get("credentials")?.jsonArray.orEmpty().map { it.jsonObject }
                        "Operation ${s.id} completed: stored ${creds.size} credential(s): " +
                            creds.joinToString("; ") { "${it.str("vct")} from ${it.str("issuer")} (${it.str("credential_handle")})" } + "."
                    }
                    OpKind.PRESENT -> "Operation ${s.id} completed: the presentation was accepted by ${s.result?.str("verifier") ?: "the verifier"}."
                }
                OpStatus.FAILED -> "Operation ${s.id} failed: ${s.error?.code}. ${s.error?.detail ?: ""}".trim() + nextStep(s.error?.code)
                OpStatus.CANCELLED -> "Operation ${s.id} was cancelled."
            }
            return prefix + body
        }

        private fun nextStep(code: String?): String = when (code) {
            ErrorCodes.OUTCOME_UNKNOWN -> " Do not retry automatically; ask the user to check with the other party."
            ErrorCodes.NO_MATCHING_CREDENTIAL -> " Acquire a suitable credential first."
            ErrorCodes.POLICY_DENIED -> " Do not retry; this is not allowed."
            ErrorCodes.INTERACTION_EXPIRED, ErrorCodes.OPERATION_EXPIRED -> " Ask the user whether to start again (a fresh request or offer may be needed)."
            else -> ""
        }

        private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.contentOrNull

        fun stringSchema(name: String, description: String): JsonElement = buildJsonObject {
            put("type", "string")
            put("description", description)
        }
    }
}

object McpServerFactory {

    const val INSTRUCTIONS = """This is your own credential wallet, the agent's wallet (A2WP over MCP). It holds your credentials and keys and decides what to disclose; you never see credentials or keys. Some actions need approval from your owner (the user).
- When the user gives you a credential offer link (openid-credential-offer://...), call acquire_credential with the link unchanged.
- When a site asks you to prove something with an openid4vp://... link (e.g. a "Verify your agent" link), call present_credential with the link unchanged.
- Both return an operation. Poll it with get_operation (wait_seconds up to 25) until it is completed, failed or cancelled.
- If the status is interaction_required, send interaction.interaction_uri to the user and ask them to open it. Never open it yourself, and never ask the user for PINs or codes; the wallet page collects them.
- Do not repeat a failed present_credential with outcome-unknown without asking the user."""

    fun create(tools: WalletTools, agent: AgentContext): Server {
        val server = Server(
            Implementation(name = "a2wp-kt", version = "0.1.0", title = "A2WP wallet"),
            ServerOptions(capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false))),
            INSTRUCTIONS,
        )
        server.addTool(
            name = "acquire_credential",
            title = "Acquire a credential",
            description = "Ask the wallet to obtain and store a credential from an OpenID4VCI credential offer. " +
                "Pass the offer link exactly as received (openid-credential-offer://... or an https credential_offer_uri). " +
                "Returns an operation at once; poll it with get_operation.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put("offer", WalletTools.stringSchema("offer", "The credential offer URL, unchanged."))
                },
                required = listOf("offer"),
            ),
            toolAnnotations = ToolAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true, openWorldHint = true),
        ) { req -> tools.acquireCredential(agent, req.arguments) }

        server.addTool(
            name = "present_credential",
            title = "Present a credential",
            description = "Ask the wallet to answer an OpenID4VP presentation request. " +
                "Pass the request link exactly as received (openid4vp://... or https). The wallet checks who is asking, " +
                "applies the policy set by your owner, may ask your owner to approve, and discloses only the allowed claims. " +
                "Returns an operation at once; poll it with get_operation.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put("request", WalletTools.stringSchema("request", "The presentation request URL, unchanged."))
                },
                required = listOf("request"),
            ),
            toolAnnotations = ToolAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true, openWorldHint = true),
        ) { req -> tools.presentCredential(agent, req.arguments) }

        server.addTool(
            name = "get_operation",
            title = "Get operation status",
            description = "Get the current state of an operation. With wait_seconds (0-25), waits up to that long for the status to change.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put("operation_id", WalletTools.stringSchema("operation_id", "The operation_id returned earlier."))
                    putJsonObject("wait_seconds") {
                        put("type", "integer")
                        put("minimum", 0)
                        put("maximum", WalletTools.MAX_WAIT_SECONDS)
                        put("description", "Seconds to wait for a status change (long poll). Default 0.")
                    }
                },
                required = listOf("operation_id"),
            ),
            toolAnnotations = ToolAnnotations(readOnlyHint = true, idempotentHint = true, openWorldHint = false),
        ) { req -> tools.getOperation(agent, req.arguments) }

        server.addTool(
            name = "cancel_operation",
            title = "Cancel an operation",
            description = "Cancel an operation. Only possible before the wallet has sent the presentation or stored the credential.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put("operation_id", WalletTools.stringSchema("operation_id", "The operation_id to cancel."))
                },
                required = listOf("operation_id"),
            ),
            toolAnnotations = ToolAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true, openWorldHint = false),
        ) { req -> tools.cancelOperation(agent, req.arguments) }
        return server
    }
}
