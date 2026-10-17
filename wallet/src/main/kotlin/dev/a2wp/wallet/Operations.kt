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

/** What the wallet worked out the request is. Internal only: A2WP -01 does not put it on the wire. */
enum class OpKind(val wire: String) { ACQUIRE("issuance"), PRESENT("presentation") }

/** Internal states. On the wire (A2WP -01) the three nonterminal ones are all `pending`. */
enum class OpStatus(val wire: String, val terminal: Boolean) {
    PENDING("pending", false),
    PROCESSING("pending", false),
    INTERACTION_REQUIRED("pending", false),
    COMPLETED("completed", true),
    FAILED("failed", true),
    CANCELLED("cancelled", true),
}

/** The error codes of A2WP -01 (draft-senarath-a2wp-01, Section 5). */
object ErrorCodes {
    const val UNSUPPORTED_REQUEST = "unsupported-request"
    const val DENIED = "denied"
    const val NO_MATCHING_CREDENTIAL = "no-matching-credential"
    const val EXPIRED = "expired"
    const val PROCESSING_ERROR = "processing-error"
    const val OUTCOME_UNKNOWN = "outcome-unknown"
    const val NOT_FOUND = "not-found"
    const val NOT_CANCELLABLE = "not-cancellable"
}

data class OpError(val code: String, val detail: String? = null)

data class InteractionView(val id: String, val uri: String, val expiresAt: Instant)

/** Immutable view of an operation. [toJson] is the A2WP -01 operation object. */
data class OperationSnapshot(
    val id: String,
    /** Null when the wallet could not tell what the request is. */
    val kind: OpKind?,
    val status: OpStatus,
    val interaction: InteractionView?,
    /** The counterparty's continuation (for OpenID4VP, the Response Endpoint's redirect_uri), unchanged. */
    val continuation: String?,
    val error: OpError?,
    val createdAt: Instant,
    val completedAt: Instant?,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("id", id)
        put("status", status.wire)
        if (status == OpStatus.INTERACTION_REQUIRED && interaction != null) {
            put("interaction", buildJsonObject {
                put("uri", interaction.uri)
                put("expires_at", interaction.expiresAt.toString())
            })
        }
        if ((status == OpStatus.COMPLETED || status == OpStatus.FAILED) && continuation != null) put("continuation", continuation)
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

/** Thrown inside a runner to fail the operation with a given code, and the counterparty's continuation if any. */
class OperationFailure(val code: String, val detail: String?, val continuation: String? = null) : RuntimeException(code) {
    override fun fillInStackTrace(): Throwable = this
}

class Operation internal constructor(
    val id: String,
    val kind: OpKind?,
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
    private var continuation: String? = null
    private var error: OpError? = null
    private var committed = false
    private var completedAt: Instant? = null
    internal var job: Job? = null

    private val _state = MutableStateFlow(snapshotLocked())
    val state: StateFlow<OperationSnapshot> get() = _state

    fun snapshot(): OperationSnapshot = _state.value

    val isCommitted: Boolean get() = synchronized(lock) { committed }

    private fun snapshotLocked() = OperationSnapshot(
        id, kind, status, interactionView, continuation, error, createdAt, completedAt,
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

    internal fun complete(c: String?): Boolean = mutate {
        status = OpStatus.COMPLETED
        continuation = c
        interaction = null
        interactionView = null
        completedAt = Instant.now()
    }

    internal fun fail(e: OpError, c: String? = null): Boolean = mutate {
        status = OpStatus.FAILED
        error = e
        continuation = c
        interaction = null
        interactionView = null
        completedAt = Instant.now()
    }

    /** Expiry: before commit -> operation-expired; after commit -> outcome-unknown. */
    internal fun expire(): Boolean = mutate {
        status = OpStatus.FAILED
        error = if (committed) OpError(ErrorCodes.OUTCOME_UNKNOWN, "The operation expired after the point of no return; the external outcome is unknown.")
        else OpError(ErrorCodes.EXPIRED, "The operation reached the wallet's time limit before completion.")
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

    fun complete(continuation: String? = null) {
        operation.complete(continuation)
    }

    fun fail(code: String, detail: String?, continuation: String? = null) {
        operation.fail(OpError(code, detail), continuation)
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
     * Creates an operation and starts [runner]. A2WP -01 repeated submissions: if the same agent submitted the same
     * request string before, its most recent operation for it is returned, unless that operation ended before its
     * commit point. So a retried call cannot disclose or redeem twice, but a cancelled or expired one can be retried.
     */
    fun create(
        agent: AgentContext,
        kind: OpKind?,
        dedupeKey: String?,
        runner: suspend (OperationContext) -> Unit,
    ): CreateResult {
        val op: Operation
        synchronized(createLock) {
            sweep()
            if (dedupeKey != null) {
                val latest = ops.values
                    .filter { it.agentId == agent.agentId && it.dedupeKey == dedupeKey }
                    .maxByOrNull { it.createdAt }
                if (latest != null && (!latest.snapshot().status.terminal || latest.isCommitted)) {
                    return CreateResult.Existing(latest)
                }
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
                    op.fail(OpError(ErrorCodes.PROCESSING_ERROR, "The wallet stopped without a result."))
                }
            } catch (_: OperationAborted) {
                // terminal already
            } catch (f: OperationFailure) {
                op.fail(OpError(f.code, f.detail), f.continuation)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                log.warn("operation {} failed: {}", op.id, e.javaClass.simpleName)
                op.fail(
                    if (op.isCommitted) OpError(ErrorCodes.OUTCOME_UNKNOWN, "The wallet failed after the point of no return.")
                    else OpError(ErrorCodes.PROCESSING_ERROR, "Internal failure (${e.javaClass.simpleName}).")
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
        log.info("operation {} created: {} for agent {}", op.id, kind?.wire ?: "unrecognized", agent.agentId)
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
                InteractionOutcome.Denied -> throw OperationFailure(ErrorCodes.DENIED, "The user declined.")
                InteractionOutcome.Expired ->
                    if (op.snapshot().status.terminal) throw OperationAborted()
                    else throw OperationFailure(ErrorCodes.EXPIRED, "The interaction expired before the user responded.")
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
