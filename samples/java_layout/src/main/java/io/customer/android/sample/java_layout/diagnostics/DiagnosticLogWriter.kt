package io.customer.android.sample.java_layout.diagnostics

import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

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
    private var header: String = ""

    /**
     * Wall-clock instant at which today's file stops being today's file. Compared against on every
     * record, so rotation costs an inequality rather than a date format.
     */
    private var rolloverAt = 0L
    private var writesSinceExistenceCheck = 0

    fun open(header: String) {
        synchronized(lock) {
            this.header = header
            openCurrentFile(System.currentTimeMillis())
        }
    }

    /**
     * Rotation is **per day, not per launch.**
     */
    private fun openCurrentFile(now: Long) {
        closeCurrentFile()

        if (!directory.exists() && !directory.mkdirs()) return

        val file = File(directory, "$FILE_PREFIX${dayFormat.format(Date(now))}$FILE_SUFFIX")
        val isNew = !file.exists()

        stream = runCatching { FileOutputStream(file, /* append = */ true) }.getOrNull()
        if (stream == null) return

        currentFile = file
        rolloverAt = startOfNextDay(now)
        writesSinceExistenceCheck = 0

        // Retention only when a file was actually created. Keeping it off the failure paths
        // matters: they return before rolloverAt is set, so every later append re-enters this
        // method.
        if (isNew) prune()

        // The header repeats on every open, not just on a new file. A same-day relaunch reuses
        // the file but resets elapsedRealtime and may carry a different bootCount or build, so
        // without this every record after the first process is correlated to the wrong boot.
        if (header.isNotEmpty()) write(header)
    }

    private fun closeCurrentFile() {
        runCatching { stream?.close() }
        stream = null
    }

    fun append(line: String) {
        synchronized(lock) {
            val now = System.currentTimeMillis()
            if (now >= rolloverAt) {
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
                file.delete()
            }
        }
    }

    private fun startOfNextDay(now: Long): Long = Calendar.getInstance().apply {
        timeInMillis = now
        add(Calendar.DAY_OF_YEAR, 1)
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}
