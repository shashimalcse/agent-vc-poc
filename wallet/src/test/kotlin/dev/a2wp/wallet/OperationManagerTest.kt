package dev.a2wp.wallet

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonObject
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class OperationManagerTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val agent = AgentContext("a1", "Agent", "p1", PolicyConfig())
    private val other = AgentContext("a2", "Other", "p1", PolicyConfig())

    @AfterTest
    fun tearDown() = scope.cancel()

    private fun manager(ttl: kotlin.time.Duration = 10.seconds, interactionTtl: kotlin.time.Duration = 5.seconds) =
        OperationManager(scope, "https://wallet.test", ttl, interactionTtl, 3600.seconds, 10)

    private fun created(r: OperationManager.CreateResult): Operation = assertIs<OperationManager.CreateResult.Created>(r).op

    private suspend fun awaitStatus(op: Operation, s: OpStatus) =
        withTimeout(5.seconds) { while (op.snapshot().status != s) delay(5) }

    @Test
    fun `runs pending to processing to completed`() = runBlocking<Unit> {
        val gate = CompletableDeferred<Unit>()
        val m = manager()
        val op = created(m.create(agent, OpKind.PRESENT, null) { ctx ->
            gate.await()
            ctx.processing()
            ctx.commit()
            ctx.complete("https://v.test/cb?response_code=rc1")
        })
        assertEquals(OpStatus.PENDING, op.snapshot().status)
        gate.complete(Unit)
        val s = m.await(op, 5.seconds).let { if (it.status.terminal) it else m.await(op, 5.seconds) }
        assertEquals(OpStatus.COMPLETED, s.status)
        assertNotNull(s.completedAt)
        assertEquals("completed", s.toJson()["status"].toString().trim('"'))
        assertEquals("https://v.test/cb?response_code=rc1", s.toJson()["continuation"].toString().trim('"'))
        assertEquals(setOf("id", "status", "continuation"), s.toJson().keys)
    }

    @Test
    fun `terminal states never change`() = runBlocking<Unit> {
        val m = manager()
        val op = created(m.create(agent, OpKind.ACQUIRE, null) { ctx -> ctx.fail(ErrorCodes.DENIED, "no") })
        awaitStatus(op, OpStatus.FAILED)
        op.complete(null)
        op.markProcessing()
        assertIs<OperationManager.CancelOutcome.NotCancellable>(m.cancel(op))
        assertEquals(OpStatus.FAILED, op.snapshot().status)
        assertEquals(ErrorCodes.DENIED, op.snapshot().error?.code)
    }

    @Test
    fun `cancel before commit stops the runner`() = runBlocking<Unit> {
        val reachedCommit = CompletableDeferred<Unit>()
        var committed = false
        val m = manager()
        val op = created(m.create(agent, OpKind.PRESENT, null) { ctx ->
            ctx.processing()
            delay(300)
            reachedCommit.complete(Unit)
            ctx.commit()
            committed = true
        })
        awaitStatus(op, OpStatus.PROCESSING)
        val r = m.cancel(op)
        assertIs<OperationManager.CancelOutcome.Cancelled>(r)
        assertEquals(OpStatus.CANCELLED, op.snapshot().status)
        delay(500)
        assertEquals(false, committed)
        assertEquals(OpStatus.CANCELLED, op.snapshot().status)
        // Repeating cancel returns the cancelled representation.
        assertIs<OperationManager.CancelOutcome.Cancelled>(m.cancel(op))
    }

    @Test
    fun `cancel after commit is refused`() = runBlocking<Unit> {
        val release = CompletableDeferred<Unit>()
        val m = manager()
        val op = created(m.create(agent, OpKind.PRESENT, null) { ctx ->
            ctx.processing()
            ctx.commit()
            release.await()
            ctx.complete()
        })
        withTimeout(5.seconds) { while (!op.isCommitted) delay(5) }
        assertIs<OperationManager.CancelOutcome.NotCancellable>(m.cancel(op))
        assertEquals(OpStatus.PROCESSING, op.snapshot().status)
        release.complete(Unit)
        awaitStatus(op, OpStatus.COMPLETED)
    }

    @Test
    fun `expiry before commit is operation-expired`() = runBlocking<Unit> {
        val m = manager(ttl = 200.milliseconds)
        val op = created(m.create(agent, OpKind.ACQUIRE, null) { ctx ->
            ctx.processing()
            delay(10_000)
        })
        awaitStatus(op, OpStatus.FAILED)
        assertEquals(ErrorCodes.EXPIRED, op.snapshot().error?.code)
    }

    @Test
    fun `expiry after commit is outcome-unknown`() = runBlocking<Unit> {
        val m = manager(ttl = 200.milliseconds)
        val op = created(m.create(agent, OpKind.PRESENT, null) { ctx ->
            ctx.processing()
            ctx.commit()
            delay(10_000)
        })
        awaitStatus(op, OpStatus.FAILED)
        assertEquals(ErrorCodes.OUTCOME_UNKNOWN, op.snapshot().error?.code)
    }

    @Test
    fun `runner exception after commit is outcome-unknown`() = runBlocking<Unit> {
        val m = manager()
        val op = created(m.create(agent, OpKind.PRESENT, null) { ctx ->
            ctx.processing()
            ctx.commit()
            error("network dropped")
        })
        awaitStatus(op, OpStatus.FAILED)
        assertEquals(ErrorCodes.OUTCOME_UNKNOWN, op.snapshot().error?.code)
    }

    private val prompt = ApprovalPrompt.Present("x509_san_dns:v.test", null, null, "vct", listOf("a" to "1"), emptyList())

    @Test
    fun `interaction approve resumes processing`() = runBlocking<Unit> {
        val m = manager()
        val op = created(m.create(agent, OpKind.PRESENT, null) { ctx ->
            ctx.processing()
            assertIs<InteractionOutcome.Approved>(ctx.interact(prompt))
            ctx.commit()
            ctx.complete()
        })
        awaitStatus(op, OpStatus.INTERACTION_REQUIRED)
        val view = assertNotNull(op.snapshot().interaction)
        assertTrue(view.uri.startsWith("https://wallet.test/approve/ia_"))
        val json = op.snapshot().toJson()
        // A2WP -01: still pending on the wire, with an interaction object.
        assertEquals("pending", json["status"].toString().trim('"'))
        assertEquals(view.uri, json["interaction"]!!.jsonObject["uri"].toString().trim('"'))
        assertTrue(m.resolveInteraction(view.id, InteractionOutcome.Approved()))
        // A second resolution is refused.
        assertEquals(false, m.resolveInteraction(view.id, InteractionOutcome.Denied))
        awaitStatus(op, OpStatus.COMPLETED)
        assertNull(op.snapshot().toJson()["interaction"])
    }

    @Test
    fun `interaction deny is policy-denied`() = runBlocking<Unit> {
        val m = manager()
        val op = created(m.create(agent, OpKind.PRESENT, null) { ctx -> ctx.interact(prompt) })
        awaitStatus(op, OpStatus.INTERACTION_REQUIRED)
        m.resolveInteraction(op.snapshot().interaction!!.id, InteractionOutcome.Denied)
        awaitStatus(op, OpStatus.FAILED)
        assertEquals(ErrorCodes.DENIED, op.snapshot().error?.code)
    }

    @Test
    fun `interaction timeout is interaction-expired`() = runBlocking<Unit> {
        val m = manager(interactionTtl = 200.milliseconds)
        val op = created(m.create(agent, OpKind.PRESENT, null) { ctx -> ctx.interact(prompt) })
        awaitStatus(op, OpStatus.FAILED)
        assertEquals(ErrorCodes.EXPIRED, op.snapshot().error?.code)
    }

    @Test
    fun `same request returns its operation unless that ended before its commit point`() = runBlocking<Unit> {
        val m = manager()
        val gate = CompletableDeferred<Unit>()
        val a = created(m.create(agent, OpKind.PRESENT, "k1") { gate.await() })
        val again = m.create(agent, OpKind.PRESENT, "k1") { error("must not run") }
        assertSame(a, assertIs<OperationManager.CreateResult.Existing>(again).op)
        // Another agent with the same request gets its own operation.
        created(m.create(other, OpKind.PRESENT, "k1") { gate.await() })
        // Failed before the commit point: a resubmission starts a new operation.
        val f = created(m.create(agent, OpKind.ACQUIRE, "k2") { ctx -> ctx.fail(ErrorCodes.PROCESSING_ERROR, null) })
        awaitStatus(f, OpStatus.FAILED)
        created(m.create(agent, OpKind.ACQUIRE, "k2") { })
        // Failed after the commit point: a resubmission returns it, so nothing is disclosed twice.
        val c = created(m.create(agent, OpKind.PRESENT, "k3") { ctx -> ctx.commit(); ctx.fail(ErrorCodes.PROCESSING_ERROR, null) })
        awaitStatus(c, OpStatus.FAILED)
        assertSame(c, assertIs<OperationManager.CreateResult.Existing>(m.create(agent, OpKind.PRESENT, "k3") { error("must not run") }).op)
        // Completed: returned.
        val d = created(m.create(agent, OpKind.PRESENT, "k4") { ctx -> ctx.commit(); ctx.complete() })
        awaitStatus(d, OpStatus.COMPLETED)
        assertSame(d, assertIs<OperationManager.CreateResult.Existing>(m.create(agent, OpKind.PRESENT, "k4") { error("must not run") }).op)
        gate.complete(Unit)
    }

    @Test
    fun `operations are scoped to their agent`() = runBlocking<Unit> {
        val m = manager()
        val op = created(m.create(agent, OpKind.PRESENT, null) { })
        assertNotNull(m.get(agent, op.id))
        assertNull(m.get(other, op.id))
    }

    @Test
    fun `active operation limit`() = runBlocking<Unit> {
        val m = OperationManager(scope, "https://wallet.test", 10.seconds, 5.seconds, 3600.seconds, 2)
        val gate = CompletableDeferred<Unit>()
        created(m.create(agent, OpKind.PRESENT, null) { gate.await() })
        created(m.create(agent, OpKind.PRESENT, null) { gate.await() })
        assertIs<OperationManager.CreateResult.TooMany>(m.create(agent, OpKind.PRESENT, null) { })
        gate.complete(Unit)
    }

    @Test
    fun `long poll returns early on change and on timeout`() = runBlocking<Unit> {
        val m = manager()
        val gate = CompletableDeferred<Unit>()
        val op = created(m.create(agent, OpKind.PRESENT, null) { ctx ->
            gate.await()
            ctx.processing()
            delay(10_000)
        })
        val t0 = System.nanoTime()
        val s = m.await(op, 200.milliseconds)
        assertTrue((System.nanoTime() - t0) / 1_000_000 >= 150)
        assertEquals(OpStatus.PENDING, s.status)
        gate.complete(Unit)
        assertEquals(OpStatus.PROCESSING, m.await(op, 5.seconds).status)
    }
}
