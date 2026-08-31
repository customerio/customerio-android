package io.customer.android.sample.java_layout.diagnostics

import android.app.Application
import android.os.Process
import android.os.SystemClock
import android.util.Log
import io.customer.base.internal.InternalCustomerIOApi
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.core.util.CioDiagnostics
import io.customer.sdk.core.util.CioLogLevel
import java.io.File

/**
 * Field-drive diagnostics sink.
 *
 * Installs a dispatcher on the Customer.io logger and mirrors every record the SDK emits into an
 * NDJSON file on disk, together with a device-state snapshot.
 *
 * Geofence field drives run for hours with the app in the background and no debugger attached.
 * Logcat is a ring buffer that a long drive overruns, so today a completed drive leaves nothing
 * reliable to analyse afterwards. This is the sink that fixes that.
 *
 * **Nothing here parses the SDK's message.** The device writes the raw string, including any
 * ` || key=value` tail, into `msg`. A host-side script splits the tail and fills in `ev` and
 * `data`. One parser, living off-device, means no Kotlin/Swift pair to drift apart, and a parser
 * bug can be fixed and re-run over files already captured instead of having destroyed what it
 * misread.
 *
 * Deliberately schema-identical to the iOS sink in `Apps/APN-UIKit/APN UIKit/Diagnostics`: the
 * same tooling reads both, and a per-platform dialect would double every analysis script.
 */
object DiagnosticLog {
    /**
     * Bumped only when a field is removed, renamed, or changes meaning. Adding an optional field
     * is not a bump — parsers ignore unknown fields.
     */
    const val SCHEMA_VERSION = 1

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
     *
     * A cold background wake — the geofence case that matters most — reaches SDK code within
     * milliseconds of process start, and on Android every such wake runs `Application.onCreate`
     * first. Install this later, from an activity or after SDK initialization, and the wake you
     * most wanted to observe is already over.
     */
    @JvmStatic
    @OptIn(InternalCustomerIOApi::class)
    fun start(application: Application) {
        synchronized(lock) {
            if (started) return
            started = true
            startElapsedMs = SystemClock.elapsedRealtime()

            val fileWriter = DiagnosticLogWriter(directory(application))
            writer = fileWriter
            fileWriter.open(DiagnosticEnvelope.fileHeader(application))

            deviceState = DiagnosticDeviceState(application).also { state ->
                state.start { reason -> emit(Source.APP, "Diagnostics", CioLogLevel.DEBUG, "device.state changed=$reason") }
            }
        }

        // The SDK's machine-readable diagnostic tail. Off by default and reachable only behind an
        // opt-in annotation; enabled here because this app exists to produce field data and the
        // harness parses that tail. A customer app never reaches this — see CioDiagnostics for why
        // the default must stay false.
        CioDiagnostics.enabled = true

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
            "session.start schema=$SCHEMA_VERSION dir=$DIRECTORY_NAME"
        )
    }

    /**
     * Receives every record the SDK emits. Forwards to Logcat **first**: the SDK dispatches as
     * `logDispatcher?.invoke(...) ?: <logcat>`, so a dispatcher that does not forward silently
     * empties Logcat for everyone else using this app.
     *
     * Known limitation, recorded rather than solved: the SDK passes only level and message to the
     * dispatcher, never the `Throwable`, so stack traces on error records cannot reach the file.
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
    enum class Source(val wire: String) {
        SDK("sdk"),
        APP("app"),
        REF("ref")
    }

    internal const val DIRECTORY_NAME = "cio-diagnostics"
}
