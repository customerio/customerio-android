package io.customer.android.sample.java_layout.diagnostics

import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Appends NDJSON records to a file on disk.
 *
 * NDJSON rather than a JSON array specifically because it is append-safe and truncation-tolerant:
 * a process killed mid-write loses one line, not the file. Losing the file is losing the drive.
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
            prune()
            openCurrentFile(System.currentTimeMillis())
        }
    }

    /**
     * Rotation is **per day, not per launch.**
     *
     * A phone woken repeatedly in the background relaunches the process many times over a drive,
     * and per-launch rotation would shatter one drive across a dozen files that then have to be
     * reassembled in the right order. Process deaths stay perfectly visible as `session.start`
     * records *inside* the file.
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

        if (isNew && header.isNotEmpty()) write(header)
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
     *
     * Deliberately no `fd.sync()`: that would only add durability across a kernel panic or power
     * loss, at the cost of a flash write per log line for hours at a time.
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
     *
     * Never clears the directory on launch. A drive that ended with the phone dying, or an engineer
     * who forgot to pull the file before the next test, must still find yesterday's data where they
     * left it.
     */
    private fun prune() {
        var kept = 0
        var total = 0L
        for (file in files()) {
            val withinCount = kept < MAX_FILES
            val withinSize = total + file.length() <= MAX_TOTAL_BYTES
            // The newest file is always kept, however large — it may be the drive nobody has
            // pulled off the device yet.
            if (kept == 0 || (withinCount && withinSize)) {
                kept += 1
                total += file.length()
            } else {
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
