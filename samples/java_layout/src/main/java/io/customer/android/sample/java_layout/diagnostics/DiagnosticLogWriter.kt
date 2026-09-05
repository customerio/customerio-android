package io.customer.android.sample.java_layout.diagnostics

import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Appends NDJSON records to a file on disk.
 */
internal class DiagnosticLogWriter(private val directory: File) {
    private companion object {
        /** Two weeks covers a test cycle; a drive is single-digit megabytes. */
        const val MAX_FILES = 14
        const val MAX_TOTAL_BYTES = 100L * 1024 * 1024

        const val FILE_PREFIX = "cio-diag-"
        const val FILE_SUFFIX = ".ndjson"

        /**
         * One existence check every this many records — roughly 0.4% overhead, against silently
         * writing a whole drive into a file somebody deleted from under us.
         */
        const val EXISTENCE_CHECK_INTERVAL = 256
    }

    private val lock = Any()
    private val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    private var stream: FileOutputStream? = null
    private var currentFile: File? = null

    /**
     * Rebuilt on every open, not stored as a string. The header describes the file it heads: a
     * process that outlives a day rolls to a new file, and re-writing the original string there
     * stamped it with the old session's timestamp — and, on iOS, the old timezone.
     */
    private var headerProvider: (() -> String)? = null

    /**
     * Wall-clock instant at which today's file stops being today's file. Compared against on every
     * record, so rotation costs an inequality rather than a date format.
     */
    private var rolloverAt = 0L

    /**
     * Zone the open file was named and its [rolloverAt] computed in. Both are derived from a
     * single snapshot, so a file can never be named for one zone and roll over on another's
     * midnight. Compared on every append: reading the zone fresh each time is not enough on its
     * own, because [rolloverAt] is a precomputed instant and nothing else would notice it had
     * become the wrong one. Driving east across a zone would otherwise keep appending to the
     * previous day's file until midnight in the zone the file was opened in.
     */
    private var openZoneId: String? = null
    private var writesSinceExistenceCheck = 0

    fun open(headerProvider: () -> String) {
        synchronized(lock) {
            this.headerProvider = headerProvider
            openCurrentFile(System.currentTimeMillis())
        }
    }

    /**
     * Rotation is **per day, not per launch.**
     */
    private fun openCurrentFile(now: Long) {
        closeCurrentFile()

        if (!directory.exists() && !directory.mkdirs()) return

        // Same stale-zone trap as the envelope: startOfNextDay works off the current zone, so a
        // formatter pinned at construction would name the file for the zone the process started in.
        // One snapshot for the name, the boundary, and the recorded id — reading the zone three
        // times could straddle a change and pair a name with another zone's midnight.
        val zone = TimeZone.getDefault()
        dayFormat.timeZone = zone
        val file = File(directory, "$FILE_PREFIX${dayFormat.format(Date(now))}$FILE_SUFFIX")
        val isNew = !file.exists()

        stream = runCatching { FileOutputStream(file, /* append = */ true) }.getOrNull()
        if (stream == null) return

        currentFile = file
        rolloverAt = startOfNextDay(now, zone)
        openZoneId = zone.id
        writesSinceExistenceCheck = 0

        // Retention only when a file was actually created. Keeping it off the failure paths
        // matters: they return before rolloverAt is set, so every later append re-enters this
        // method.
        if (isNew) prune()

        // The header repeats on every open, not just on a new file. A same-day relaunch reuses
        // the file but resets elapsedRealtime and may carry a different bootCount or build, so
        // without this every record after the first process is correlated to the wrong boot.
        val header = headerProvider?.invoke().orEmpty()
        if (header.isNotEmpty()) write(header)
    }

    private fun closeCurrentFile() {
        runCatching { stream?.close() }
        stream = null
    }

    fun append(line: String) {
        synchronized(lock) {
            val now = System.currentTimeMillis()
            if (now >= rolloverAt || TimeZone.getDefault().id != openZoneId) {
                openCurrentFile(now)
            } else if (needsExistenceCheck() && currentFile?.exists() != true) {
                // Deleted from under us — someone clearing files over MTP, `adb shell rm`, or the
                // app's storage being cleared. The stream stays writable and the bytes go nowhere,
                // so without this the rest of the drive is lost in silence.
                openCurrentFile(now)
            }
            write(line)
        }
    }

    private fun needsExistenceCheck(): Boolean {
        writesSinceExistenceCheck += 1
        if (writesSinceExistenceCheck < EXISTENCE_CHECK_INTERVAL) return false
        writesSinceExistenceCheck = 0
        return true
    }

    /**
     * One write and flush per record, so a record that has returned is already in the file and
     * survives the process being killed the instant afterwards — which is the normal way a
     * background wake ends.
     */
    private fun write(line: String) {
        val target = stream ?: return
        runCatching {
            target.write((line + "\n").toByteArray(Charsets.UTF_8))
            target.flush()
        }
    }

    /** Files on disk, newest first. */
    fun files(): List<File> =
        (directory.listFiles { file -> file.name.startsWith(FILE_PREFIX) } ?: emptyArray())
            // Names are `cio-diag-YYYY-MM-DD`, so lexical order is chronological order and no
            // filesystem timestamp has to be trusted.
            .sortedByDescending { it.name }

    /**
     * Enforces the retention policy by evicting the **oldest** files.
     */
    private fun prune() {
        var kept = 0
        var total = 0L
        // Once the budget is spent everything older goes. Without the latch a large file could be
        // deleted and then a smaller, *older* one still fit and survive it — retention preferring
        // older data over newer, which is backwards.
        var budgetSpent = false
        for (file in files()) {
            val withinCount = kept < MAX_FILES
            val withinSize = total + file.length() <= MAX_TOTAL_BYTES
            // The newest file is always kept, however large — it may be the drive nobody has
            // pulled off the device yet.
            if (!budgetSpent && (kept == 0 || (withinCount && withinSize))) {
                kept += 1
                total += file.length()
            } else {
                budgetSpent = true
                // Never unlink the file this writer holds open. `prune()` runs from
                // `openCurrentFile` right after creating today's file, which is normally newest and
                // so always kept — but a future-dated `cio-diag-` file (clock set forward, a
                // capture copied back over MTP) takes that slot and can push the open one over
                // budget. Deleting it would leave writes going to an unlinked inode until the
                // existence check reopens, losing up to EXISTENCE_CHECK_INTERVAL records in
                // silence. The latch is still set, so retention cannot prefer older data.
                if (file != currentFile) file.delete()
            }
        }
    }

    private fun startOfNextDay(now: Long, zone: TimeZone): Long = Calendar.getInstance(zone).apply {
        timeInMillis = now
        add(Calendar.DAY_OF_YEAR, 1)
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}
