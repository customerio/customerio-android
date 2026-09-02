package io.customer.android.sample.java_layout.diagnostics

import android.app.Application
import android.os.Process
import android.os.SystemClock
import android.util.Log
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.core.util.CioLogLevel
import java.io.File

/**
 * Field-drive diagnostics sink.
 */
object DiagnosticLog {
    /**
     * Bumped only when a field is removed, renamed, or changes meaning. Adding an optional field
     * is not a bump — parsers ignore unknown fields.
     */
    const val SCHEMA_VERSION = 1

    /**
     * Same delimiter the SDK's geofence tail uses. The app's own records follow the same contract
     * so one parser reads the whole file — inline `key=value` in the prose would be invisible to it.
     */
    private const val DELIMITER = " || "

    /** Mirrors the SDK's own Logcat tag, which is `internal` and so cannot be referenced here. */
    private const val LOGCAT_TAG = "[CIO]"

    private val lock = Any()
    private var writer: DiagnosticLogWriter? = null
    private var deviceState: DiagnosticDeviceState? = null

    private var seq = 0L
    private var started = false

    /** Guards against a record emitted from inside the sink itself recursing forever. */
    private var isEmitting = false

    private val pid = Process.myPid()
    private var startElapsedMs = 0L

    /**
     * Install the sink. Call this as the **first** statement of `Application.onCreate`.
     */
    @JvmStatic
    fun start(application: Application) {
        synchronized(lock) {
            if (started) return
            started = true
            startElapsedMs = SystemClock.elapsedRealtime()

            val fileWriter = DiagnosticLogWriter(directory(application))
            writer = fileWriter
            fileWriter.open(DiagnosticEnvelope.fileHeader(application))

            deviceState = DiagnosticDeviceState(application).also { state ->
                state.start { reason ->
                    emit(
                        Source.APP,
                        "Diagnostics",
                        CioLogLevel.DEBUG,
                        "Device state changed${DELIMITER}ev=device.state io=obs changed=$reason"
                    )
                }
            }
        }

        // The SDK's diagnostic tail is enabled by the io.customer.geofence.diagnostics manifest
        // entry, not from here — there is no API for it.

        // Outside the lock: the SDK may log synchronously from inside these calls, and `emit`
        // takes the same lock.
        val logger = SDKComponent.logger
        // The SDK filters by level *before* the dispatcher runs, so a sink installed while the
        // level sits at its default sees nothing. Forced here and again in the config builder,
        // because `CustomerIO.initialize` re-applies the configured level over this one.
        logger.logLevel = CioLogLevel.DEBUG
        logger.setLogDispatcher { level, message -> dispatch(level, message) }

        emit(
            Source.APP,
            "Diagnostics",
            CioLogLevel.INFO,
            "Diagnostic session started$DELIMITER" +
                "ev=session.start io=obs schema=$SCHEMA_VERSION dir=$DIRECTORY_NAME"
        )
    }

    /**
     * Receives every record the SDK emits. Forwards to Logcat **first**: the SDK dispatches as
     * `logDispatcher?.invoke(...) ?: <logcat>`, so a dispatcher that does not forward silently
     * empties Logcat for everyone else using this app.
     */
    private fun dispatch(level: CioLogLevel, message: String) {
        when (level) {
            CioLogLevel.NONE -> {}
            CioLogLevel.ERROR -> Log.e(LOGCAT_TAG, message)
            CioLogLevel.INFO -> Log.i(LOGCAT_TAG, message)
            CioLogLevel.DEBUG -> Log.d(LOGCAT_TAG, message)
        }

        // The SDK has already prefixed `[Tag] `. Lift it into its own field so files can be
        // filtered by subsystem, and leave the rest of the string untouched.
        val (tag, body) = splitTag(message)
        emit(Source.SDK, tag, level, body)
    }

    /** Write an app-side record, for anything the SDK does not say itself. */
    @JvmStatic
    @JvmOverloads
    fun note(message: String, tag: String = "Diagnostics", level: CioLogLevel = CioLogLevel.DEBUG) {
        emit(Source.APP, tag, level, message)
    }

    private fun emit(source: Source, tag: String?, level: CioLogLevel, message: String) {
        synchronized(lock) {
            val target = writer ?: return
            if (!started || isEmitting) return
            isEmitting = true
            try {
                seq += 1
                target.append(
                    DiagnosticEnvelope.record(
                        seq = seq,
                        pid = pid,
                        upMillis = SystemClock.elapsedRealtime() - startElapsedMs,
                        source = source,
                        tag = tag,
                        level = level,
                        message = message,
                        deviceState = deviceState?.snapshotJson() ?: "{}"
                    )
                )
            } finally {
                isEmitting = false
            }
        }
    }

    /**
     * `getExternalFilesDir` rather than internal storage: no permission is required, the directory
     * survives `adb pull` without `run-as`, and it is visible over MTP — three independent ways to
     * get a drive off the phone instead of one.
     */
    @JvmStatic
    fun directory(application: Application): File =
        File(application.getExternalFilesDir(null) ?: application.filesDir, DIRECTORY_NAME)

    /** Files currently on disk, newest first. */
    @JvmStatic
    fun sessionFiles(): List<File> = synchronized(lock) { writer?.files() ?: emptyList() }

    /**
     * Splits `[Geofence] message` into `"Geofence"` and `"message"`. A message with no prefix keeps
     * its whole text and reports no tag.
     */
    internal fun splitTag(message: String): Pair<String?, String> {
        if (!message.startsWith("[")) return null to message
        val close = message.indexOf(']')
        if (close <= 1) return null to message
        val tag = message.substring(1, close)
        val rest = message.substring(close + 1).removePrefix(" ")
        return tag to rest
    }

    /** Records produced by the SDK, by the sample app, or by a reference app on this schema. */
    /** Who produced the record: the SDK's logger, or the sample app itself. */
    enum class Source(val wire: String) {
        SDK("sdk"),
        APP("app")
    }

    internal const val DIRECTORY_NAME = "cio-diagnostics"
}
