package dev.a2wp.wallet

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

enum class OpKind(val wire: String) { ACQUIRE("acquire"), PRESENT("present") }

enum class OpStatus(val wire: String, val terminal: Boolean) {
    PENDING("pending", false),
    PROCESSING("processing", false),
    INTERACTION_REQUIRED("interaction_required", false),
    COMPLETED("completed", true),
    FAILED("failed", true),
    CANCELLED("cancelled", true),
}

/** Error codes. The first block is from the A2WP draft; the rest change what the agent should do next. */
object ErrorCodes {
    const val INVALID_REQUEST = "invalid-request"
    const val UNSUPPORTED_PROTOCOL = "unsupported-protocol"
    const val NOT_AUTHORIZED = "not-authorized"
    const val POLICY_DENIED = "policy-denied"
    const val INTERACTION_EXPIRED = "interaction-expired"
    const val OPERATION_EXPIRED = "operation-expired"
    const val EXTERNAL_PROTOCOL_ERROR = "external-protocol-error"
    const val OUTCOME_UNKNOWN = "outcome-unknown"
    const val NOT_CANCELLABLE = "not-cancellable"

    /** PoC addition: no stored credential can satisfy the request. Next step: acquire one. */
    const val NO_MATCHING_CREDENTIAL = "no-matching-credential"

    /** PoC addition: unknown operation id (or another agent's). Next step: stop polling. */
    const val NOT_FOUND = "not-found"

    /** PoC addition: too many active operations for this agent. Next step: wait for some to finish. */
    const val TOO_MANY_OPERATIONS = "too-many-operations"
}

data class OpError(val code: String, val detail: String? = null)

data class InteractionView(val id: String, val uri: String, val expiresAt: Instant)

/** Immutable view of an operation, as returned to the agent. */
data class OperationSnapshot(
    val id: String,
    val kind: OpKind,
    val status: OpStatus,
    val interaction: InteractionView?,
    val result: JsonObject?,
    val error: OpError?,
    val createdAt: Instant,
    val completedAt: Instant?,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("operation_id", id)
        put("operation", kind.wire)
        put("status", status.wire)
        if (status == OpStatus.INTERACTION_REQUIRED && interaction != null) {
            put("interaction", buildJsonObject {
                put("interaction_uri", interaction.uri)
                put("expires_at", interaction.expiresAt.toString())
            })
        }
        if (status == OpStatus.COMPLETED && result != null) put("result", result)
        if (status == OpStatus.FAILED && error != null) {
            put("error", buildJsonObject {
                put("code", error.code)
                error.detail?.let { put("detail", it) }
            })
        }
    }
}

/** What the approval page shows and collects. Built from verified data only. */
sealed interface ApprovalPrompt {
    data class Present(
        val verifierClientId: String,
        val verifierCertSubject: String?,
        val verifierConfiguredName: String?,
        val credentialVct: String,
        val claims: List<Pair<String, String>>,
        val withheldClaims: List<String>,
    ) : ApprovalPrompt

    data class Acquire(
        val issuer: String,
        val credentialConfigurations: List<String>,
        val txCodeRequired: Boolean,
        val txCodeLength: Int?,
        val txCodeNumeric: Boolean,
        val txCodeDescription: String?,
    ) : ApprovalPrompt
}

sealed interface InteractionOutcome {
    data class Approved(val txCode: String? = null) : InteractionOutcome
    data object Denied : InteractionOutcome
    data object Expired : InteractionOutcome
    data object Aborted : InteractionOutcome
}

class PendingInteraction(
    val id: String,
    val operation: Operation,
    val prompt: ApprovalPrompt,
    val expiresAt: Instant,
    val principalId: String,
) {
    internal val outcome = CompletableDeferred<InteractionOutcome>()
    val resolved: Boolean get() = outcome.isCompleted
}

/** Thrown inside a runner when the operation became terminal (cancelled or expired) underneath it. */
class OperationAborted : RuntimeException("operation aborted") {
    override fun fillInStackTrace(): Throwable = this
}

/** Thrown inside a runner to fail the operation with a given code. */
class OperationFailure(val code: String, val detail: String?) : RuntimeException(code) {
    override fun fillInStackTrace(): Throwable = this
}

class Operation internal constructor(
    val id: String,
    val kind: OpKind,
    val agentId: String,
    val principalId: String,
    val dedupeKey: String?,
    val createdAt: Instant,
    val expiresAt: Instant,
) {
    private val lock = Any()
    private var status: OpStatus = OpStatus.PENDING
    private var interaction: PendingInteraction? = null
    private var interactionView: InteractionView? = null
    private var result: JsonObject? = null
    private var error: OpError? = null
    private var committed = false
    private var completedAt: Instant? = null
    internal var job: Job? = null

    private val _state = MutableStateFlow(snapshotLocked())
    val state: StateFlow<OperationSnapshot> get() = _state

    fun snapshot(): OperationSnapshot = _state.value

    val isCommitted: Boolean get() = synchronized(lock) { committed }

    private fun snapshotLocked() = OperationSnapshot(
        id, kind, status, interactionView, result, error, createdAt, completedAt,
    )

    private fun publish() {
        _state.value = snapshotLocked()
    }

    /** Applies [block] only while nonterminal. Returns false if the operation is already terminal. */
    private inline fun mutate(block: () -> Unit): Boolean = synchronized(lock) {
        if (status.terminal) return false
        block()
        publish()
        true
    }

    internal fun markProcessing(): Boolean = mutate {
        status = OpStatus.PROCESSING
        interaction = null
        interactionView = null
    }

    internal fun markInteraction(p: PendingInteraction, uri: String): Boolean = mutate {
        status = OpStatus.INTERACTION_REQUIRED
        interaction = p
        interactionView = InteractionView(p.id, uri, p.expiresAt)
    }

    /** The commit point. After this returns true, cancellation is refused and expiry reports outcome-unknown. */
    internal fun commit(): Boolean = mutate { committed = true }

    internal fun complete(r: JsonObject): Boolean = mutate {
        status = OpStatus.COMPLETED
        result = r
        interaction = null
        interactionView = null
        completedAt = Instant.now()
    }

    internal fun fail(e: OpError): Boolean = mutate {
        status = OpStatus.FAILED
        error = e
        interaction = null
        interactionView = null
        completedAt = Instant.now()
    }

    /** Expiry: before commit -> operation-expired; after commit -> outcome-unknown. */
    internal fun expire(): Boolean = mutate {
        status = OpStatus.FAILED
        error = if (committed) OpError(ErrorCodes.OUTCOME_UNKNOWN, "The operation expired after the point of no return; the external outcome is unknown.")
        else OpError(ErrorCodes.OPERATION_EXPIRED, "The operation expired before completion.")
        interaction = null
        interactionView = null
        completedAt = Instant.now()
    }

    internal sealed interface CancelResult {
        data object Cancelled : CancelResult
        data object NotCancellable : CancelResult
    }

    internal fun cancel(): CancelResult = synchronized(lock) {
        when {
            status == OpStatus.CANCELLED -> CancelResult.Cancelled
            status.terminal -> CancelResult.NotCancellable
            committed -> CancelResult.NotCancellable
            else -> {
                status = OpStatus.CANCELLED
                interaction = null
                interactionView = null
                completedAt = Instant.now()
                publish()
                CancelResult.Cancelled
            }
        }
    }

    internal fun currentInteraction(): PendingInteraction? = synchronized(lock) { interaction }
}

/** Handle given to an operation runner. All transitions refuse to act once the operation is terminal. */
class OperationContext internal constructor(
    val operation: Operation,
    private val manager: OperationManager,
) {
    fun processing() {
        if (!operation.markProcessing()) throw OperationAborted()
    }

    /** Asks for user interaction and suspends until it resolves. */
    suspend fun interact(prompt: ApprovalPrompt): InteractionOutcome = manager.interact(this, prompt)

    /** Crosses the commit point. Throws [OperationAborted] if the operation was cancelled or expired first. */
    fun commit() {
        if (!operation.commit()) throw OperationAborted()
    }

    fun complete(result: JsonObject) {
        operation.complete(result)
    }

    fun fail(code: String, detail: String?) {
        operation.fail(OpError(code, detail))
    }
}

class OperationManager(
    private val scope: CoroutineScope,
    private val publicBaseUrl: String,
    private val operationTtl: Duration = 600.seconds,
    private val interactionTtl: Duration = 300.seconds,
    private val retention: Duration = 3600.seconds,
    private val maxActivePerAgent: Int = 10,
) {
    private val log = LoggerFactory.getLogger(OperationManager::class.java)
    private val ops = ConcurrentHashMap<String, Operation>()
    private val interactions = ConcurrentHashMap<String, PendingInteraction>()
    private val createLock = Any()

    sealed interface CreateResult {
        data class Created(val op: Operation) : CreateResult
        data class Existing(val op: Operation) : CreateResult
        data object TooMany : CreateResult
    }

    /**
     * Creates an operation and starts [runner]. If the same agent already has a nonterminal or completed operation
     * with the same [dedupeKey] (the request or offer URL), that operation is returned instead, so a retried tool
     * call cannot disclose or redeem twice.
     */
    fun create(
        agent: AgentContext,
        kind: OpKind,
        dedupeKey: String?,
        runner: suspend (OperationContext) -> Unit,
    ): CreateResult {
        val op: Operation
        synchronized(createLock) {
            sweep()
            if (dedupeKey != null) {
                val existing = ops.values.firstOrNull {
                    it.agentId == agent.agentId && it.kind == kind && it.dedupeKey == dedupeKey &&
                        it.snapshot().status.let { s -> !s.terminal || s == OpStatus.COMPLETED }
                }
                if (existing != null) return CreateResult.Existing(existing)
            }
            val active = ops.values.count { it.agentId == agent.agentId && !it.snapshot().status.terminal }
            if (active >= maxActivePerAgent) return CreateResult.TooMany
            val now = Instant.now()
            op = Operation(
                id = "op_" + randomId(),
                kind = kind,
                agentId = agent.agentId,
                principalId = agent.principalId,
                dedupeKey = dedupeKey,
                createdAt = now,
                expiresAt = now.plusMillis(operationTtl.inWholeMilliseconds),
            )
            ops[op.id] = op
        }
        val ctx = OperationContext(op, this)
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                runner(ctx)
                if (!op.snapshot().status.terminal) {
                    op.fail(OpError(ErrorCodes.EXTERNAL_PROTOCOL_ERROR, "The wallet stopped without a result."))
                }
            } catch (_: OperationAborted) {
                // terminal already
            } catch (f: OperationFailure) {
                op.fail(OpError(f.code, f.detail))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                log.warn("operation {} failed: {}", op.id, e.javaClass.simpleName)
                op.fail(
                    if (op.isCommitted) OpError(ErrorCodes.OUTCOME_UNKNOWN, "The wallet failed after the point of no return.")
                    else OpError(ErrorCodes.EXTERNAL_PROTOCOL_ERROR, "Internal failure (${e.javaClass.simpleName}).")
                )
            } finally {
                op.currentInteraction()?.outcome?.complete(InteractionOutcome.Aborted)
                logTerminal(op)
            }
        }
        op.job = job
        // Expiry watchdog.
        scope.launch {
            delay(operationTtl)
            if (op.expire()) {
                log.info("operation {} expired (committed={})", op.id, op.isCommitted)
                op.currentInteractionResolve(InteractionOutcome.Expired)
                job.cancel()
            }
        }
        log.info("operation {} created: {} for agent {}", op.id, kind.wire, agent.agentId)
        job.start()
        return CreateResult.Created(op)
    }

    private fun Operation.currentInteractionResolve(o: InteractionOutcome) {
        interactions.values.filter { it.operation === this }.forEach { it.outcome.complete(o) }
    }

    private fun logTerminal(op: Operation) {
        val s = op.snapshot()
        log.info("operation {} -> {}{}", op.id, s.status.wire, s.error?.let { " (${it.code})" } ?: "")
    }

    /** Looks up an operation owned by [agent]. Other agents' operations are indistinguishable from unknown ids. */
    fun get(agent: AgentContext, id: String): Operation? =
        ops[id]?.takeIf { it.agentId == agent.agentId }

    /** Long-poll: waits up to [wait] for the status to change from the current one. */
    suspend fun await(op: Operation, wait: Duration): OperationSnapshot {
        val initial = op.snapshot()
        if (initial.status.terminal || wait <= Duration.ZERO) return initial
        return withTimeoutOrNull(wait) {
            op.state.first { it.status != initial.status || it.interaction?.id != initial.interaction?.id }
        } ?: op.snapshot()
    }

    sealed interface CancelOutcome {
        data class Cancelled(val snapshot: OperationSnapshot) : CancelOutcome
        data class NotCancellable(val snapshot: OperationSnapshot) : CancelOutcome
    }

    fun cancel(op: Operation): CancelOutcome =
        when (op.cancel()) {
            Operation.CancelResult.Cancelled -> {
                op.currentInteractionResolve(InteractionOutcome.Aborted)
                op.job?.cancel()
                CancelOutcome.Cancelled(op.snapshot())
            }
            Operation.CancelResult.NotCancellable -> CancelOutcome.NotCancellable(op.snapshot())
        }

    internal suspend fun interact(ctx: OperationContext, prompt: ApprovalPrompt): InteractionOutcome {
        val op = ctx.operation
        val now = Instant.now()
        val exp = minOf(now.plusMillis(interactionTtl.inWholeMilliseconds), op.expiresAt)
        val p = PendingInteraction("ia_" + randomId(), op, prompt, exp, op.principalId)
        interactions[p.id] = p
        try {
            if (!op.markInteraction(p, "${publicBaseUrl.trimEnd('/')}/approve/${p.id}")) throw OperationAborted()
            val waitMs = java.time.Duration.between(Instant.now(), exp).toMillis().coerceAtLeast(0)
            val outcome = withTimeoutOrNull(waitMs) { p.outcome.await() } ?: InteractionOutcome.Expired
            p.outcome.complete(outcome)
            return when (outcome) {
                is InteractionOutcome.Approved -> {
                    ctx.processing()
                    outcome
                }
                InteractionOutcome.Denied -> throw OperationFailure(ErrorCodes.POLICY_DENIED, "The user declined.")
                InteractionOutcome.Expired ->
                    if (op.snapshot().status.terminal) throw OperationAborted()
                    else throw OperationFailure(ErrorCodes.INTERACTION_EXPIRED, "The user did not respond in time.")
                InteractionOutcome.Aborted -> throw OperationAborted()
            }
        } finally {
            // Keep the record briefly so the page can say "already handled"; removed by sweep.
        }
    }

    /** For the approval page. */
    fun interaction(id: String): PendingInteraction? = interactions[id]

    /** Resolves an interaction from the approval page. Returns false if it was already resolved or is gone. */
    fun resolveInteraction(id: String, outcome: InteractionOutcome): Boolean {
        val p = interactions[id] ?: return false
        if (p.operation.currentInteraction() !== p) return false
        if (Instant.now().isAfter(p.expiresAt)) return false
        return p.outcome.complete(outcome)
    }

    private fun sweep() {
        val cutoff = Instant.now().minusMillis(retention.inWholeMilliseconds)
        ops.values.removeIf { o -> o.snapshot().let { it.status.terminal && (it.completedAt ?: it.createdAt).isBefore(cutoff) } }
        interactions.values.removeIf { it.resolved && it.expiresAt.isBefore(Instant.now().minusSeconds(600)) }
    }

    companion object {
        private val rng = SecureRandom()
        fun randomId(bytes: Int = 16): String {
            val b = ByteArray(bytes)
            rng.nextBytes(b)
            return Base64.getUrlEncoder().withoutPadding().encodeToString(b)
        }
    }
}
