package io.customer.geofence

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

/**
 * Feeds [journal] in call order, one write at a time, on [scope]: a record queued before
 * [invalidateAndClear] never lands after its clear, and a clear never erases a later record. Never
 * logs, calls no customer code and does no journal I/O on the caller's thread, so it is safe under
 * any lock. [scope] must dispatch rather than run work inline.
 *
 * Nothing cancels the geofence scope, so the writer does not wait for work: it exits once the queue
 * drains, and the next call starts another.
 *
 * Bound, with an [ownerProvider] returning the signed-in session's user-state generation or null for
 * none: each record carries the owner read where it was made, a change of owner retires every token
 * handed out before it, records are dropped while nobody is signed in, and [prepareOwner] erases what
 * the current owner does not own. The provider is read on the caller's thread, so it must be a cheap
 * read that takes at most a leaf lock and never logs.
 */
internal class DwellDiagnosticRecorder(
    private val journal: DwellDiagnosticJournal,
    private val enabled: () -> Boolean,
    private val scope: CoroutineScope,
    private val ownerProvider: (() -> Long?)? = null
) {
    private sealed interface Op {
        /** [owner] is null only when unbound. */
        class Append(val record: DwellDiagnosticRecord, val token: Long, val owner: DwellDiagnosticOwner?) : Op
        data object Clear : Op
        data object Prepare : Op
    }

    private val ops = ConcurrentLinkedQueue<Op>()

    /**
     * Ops queued and not yet run or dropped. Only its step up from zero starts a writer, so one
     * runs at a time.
     */
    private val queued = AtomicInteger()
    private val generation = AtomicLong()
    private val pendingRecords = AtomicInteger()

    /** From a clear's enqueue until it has run, so repeated resets queue only one. */
    private val clearPending = AtomicBoolean()

    /** From a prepare's enqueue until it reads the owner, so a burst of changes queues only one. */
    private val preparePending = AtomicBoolean()

    /** The owner last read, null before the first read; only a bound recorder sets it. */
    private val observedOwner = AtomicReference<DwellDiagnosticOwner?>(null)

    /**
     * Capture before any await whose answer is recorded, and pass it to [record]. Bound, the owner is
     * read while the returned token is current, and [record] reads it again first, so a token never
     * outlives the owner it was handed out under.
     */
    fun currentToken(): Long {
        // Off, nothing is recorded, so no owner is read.
        if (!isEnabled()) return generation.get()
        var token: Long
        do {
            token = generation.get()
            observeOwner()
        } while (generation.get() != token)
        return token
    }

    /**
     * Dropped while disabled or nobody is signed in, when [token] predates an [invalidateAndClear] or a
     * change of owner, or when the queue is full.
     */
    fun record(record: DwellDiagnosticRecord, token: Long = currentToken()) {
        if (!isEnabled()) return
        // Before the comparison: a change of owner seen here retires [token].
        val owner = observeOwner()
        if (token != generation.get() || owner == DwellDiagnosticOwner.None) return
        if (pendingRecords.incrementAndGet() > MAX_PENDING_RECORDS) {
            pendingRecords.decrementAndGet()
            return
        }
        enqueue(Op.Append(record, token, owner))
    }

    /**
     * Queues erasing what the owner does not own, read when it runs, after what is already queued; a
     * no-op unbound. Called at startup, so history kept past a session change by a process that died
     * before its clear ran, or kept with nobody signed in, goes without waiting for a record.
     */
    fun prepareOwner() {
        if (ownerProvider != null && preparePending.compareAndSet(false, true)) enqueue(Op.Prepare)
    }

    /** The journal as the current owner may read it: history it does not own is erased first. */
    suspend fun snapshot(): List<DwellDiagnosticRecord>? = journal.snapshot(currentOwner())

    /**
     * Retires every token handed out so far before returning, then erases the journal after what is
     * already queued. Runs while disabled too, so an opt-out erases history. A clear still pending
     * already follows every record queued before it, and those queued after it carry retired tokens.
     */
    fun invalidateAndClear() {
        generation.incrementAndGet()
        if (clearPending.compareAndSet(false, true)) enqueue(Op.Clear)
    }

    private fun isEnabled(): Boolean = try {
        enabled()
    } catch (e: Exception) {
        false
    }

    /** Null when unbound. An owner that cannot be read is nobody's, so nothing is kept for it. */
    private fun currentOwner(): DwellDiagnosticOwner? {
        val provider = ownerProvider ?: return null
        val generation = try {
            provider()
        } catch (e: Exception) {
            null
        }
        return if (generation == null) DwellDiagnosticOwner.None else DwellDiagnosticOwner.Session(generation)
    }

    /** Null when unbound. A change of owner retires every token handed out and queues a prepare. */
    private fun observeOwner(): DwellDiagnosticOwner? {
        val owner = currentOwner() ?: return null
        while (true) {
            val seen = observedOwner.get()
            if (seen == owner) return owner
            if (observedOwner.compareAndSet(seen, owner)) break
        }
        generation.incrementAndGet()
        prepareOwner()
        return owner
    }

    private fun enqueue(op: Op) {
        // Queued before it is counted, so a writer that sees the count always finds the op.
        ops.offer(op)
        if (queued.getAndIncrement() == 0) startWriter()
    }

    private fun startWriter() {
        // Undispatched only to be inside the try before a cancellation can land, so a scope
        // cancelled before the first dispatch still reaches the finally. The yield then leaves the
        // caller's thread before any journal work.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            var holding = false
            var drained = false
            try {
                yield()
                do {
                    holding = true
                    ops.poll()?.let { perform(it) }
                    holding = false
                } while (queued.decrementAndGet() != 0)
                drained = true
            } finally {
                if (!drained) abandon(countHeld = holding)
            }
        }
    }

    private suspend fun perform(op: Op) {
        when (op) {
            // Re-checked here: a reset or a change of owner can land between record's check and its enqueue.
            is Op.Append -> {
                pendingRecords.decrementAndGet()
                if (op.token == generation.get() && (op.owner == null || op.owner == currentOwner())) {
                    journal.append(op.record, op.owner)
                }
            }
            Op.Clear -> {
                journal.clear()
                clearPending.set(false)
            }
            Op.Prepare -> {
                // Reset before the owner is read, so a change seen after the read queues another.
                preparePending.set(false)
                prepareNow()
            }
        }
    }

    private suspend fun prepareNow() {
        currentOwner()?.let { journal.prepareOwner(it) }
    }

    /**
     * The writer stopped early (its scope was cancelled), so nothing will run what is queued: drop
     * it, still erasing for a pending clear or prepare, and leave the count at zero so a later call starts a
     * writer of its own. [countHeld] is whether the op being performed was taken but not counted off.
     */
    private suspend fun abandon(countHeld: Boolean) {
        var more = !countHeld || queued.decrementAndGet() != 0
        while (more) {
            if (ops.poll() is Op.Append) pendingRecords.decrementAndGet()
            more = queued.decrementAndGet() != 0
        }
        // A pending clear erases everything a pending prepare would, so it runs in its place.
        val clear = clearPending.compareAndSet(true, false)
        val prepare = preparePending.compareAndSet(true, false)
        if (clear || prepare) {
            withContext(NonCancellable) {
                // Off the caller's thread: a scope cancelled before launch lands here undispatched.
                yield()
                if (clear) journal.clear() else prepareNow()
            }
        }
    }

    private companion object {
        const val MAX_PENDING_RECORDS = 128
    }
}
