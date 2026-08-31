package io.customer.android.sample.java_layout.diagnostics

import android.annotation.SuppressLint
import android.app.Application
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import io.customer.sdk.Version
import io.customer.sdk.core.util.CioLogLevel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Builds the NDJSON lines.
 *
 * Records are assembled by hand rather than through a JSON library so fields land in the
 * documented envelope order (`v seq ts mono pid up src tag lvl msg dev`). These files are read by
 * humans scanning for the moment something went wrong at least as often as by a script, and a
 * serializer's key ordering would scatter the timestamp into the middle of the line.
 */
internal object DiagnosticEnvelope {
    /**
     * Every record carries both clocks.
     *
     * Wall clock is the only thing that correlates one device with another, or with a route file,
     * but it can step under NTP — mid-drive, by an amount nobody notices. Monotonic cannot step
     * but resets at boot and means nothing across devices. Carrying both is what lets a step be
     * *detected* afterwards rather than silently mis-measured as a timing regression.
     *
     * `elapsedRealtimeNanos` deliberately, not `nanoTime`: it keeps counting while the device is
     * in deep sleep, which is precisely the interval a backgrounded phone spends between geofence
     * wakes. (`CLOCK_MONOTONIC` on the iOS side was chosen for the same property.)
     */
    fun record(
        seq: Long,
        pid: Int,
        upMillis: Long,
        source: DiagnosticLog.Source,
        tag: String?,
        level: CioLogLevel,
        message: String,
        deviceState: String
    ): String = buildString(message.length + 256) {
        append('{')
        append("\"v\":").append(DiagnosticLog.SCHEMA_VERSION)
        append(",\"seq\":").append(seq)
        append(",\"ts\":").append(json(iso8601(System.currentTimeMillis())))
        append(",\"mono\":").append(SystemClock.elapsedRealtimeNanos())
        append(",\"pid\":").append(pid)
        append(",\"up\":").append(upMillis)
        append(",\"src\":").append(json(source.wire))
        if (tag != null) append(",\"tag\":").append(json(tag))
        append(",\"lvl\":").append(json(level.wire()))
        append(",\"msg\":").append(json(message))
        append(",\"dev\":").append(deviceState)
        append('}')
    }

    /**
     * First line of every file.
     *
     * `boot` matters: `mono` values are only comparable to each other within a single boot, so a
     * file that does not name its boot cannot be aligned with another.
     */
    @SuppressLint("HardwareIds")
    fun fileHeader(application: Application): String {
        val packageInfo = runCatching {
            application.packageManager.getPackageInfo(application.packageName, 0)
        }.getOrNull()

        return buildString(512) {
            append('{')
            append("\"v\":").append(DiagnosticLog.SCHEMA_VERSION)
            append(",\"ev\":\"file.open\"")
            append(",\"ts\":").append(json(iso8601(System.currentTimeMillis())))
            append(",\"boot\":").append(json(bootIdentifier()))
            bootCount(application)?.let { append(",\"bootCount\":").append(it) }
            append(",\"device\":{")
            append("\"model\":").append(json("${Build.MANUFACTURER} ${Build.MODEL}"))
            append(",\"os\":\"Android\"")
            append(",\"osVersion\":").append(json("${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"))
            append('}')
            append(",\"app\":{")
            append("\"id\":").append(json(application.packageName))
            append(",\"version\":").append(json(packageInfo?.versionName ?: "unknown"))
            append(",\"build\":").append(json(packageInfo?.let { versionCode(it) } ?: "unknown"))
            append('}')
            append(",\"sdk\":{\"version\":").append(json(Version.version)).append('}')
            append(",\"tz\":").append(json(TimeZone.getDefault().id))
            append('}')
        }
    }

    @Suppress("DEPRECATION")
    private fun versionCode(info: android.content.pm.PackageInfo): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode.toString()
        } else {
            info.versionCode.toString()
        }

    /**
     * A monotonically increasing count of device boots. Exact, unlike the derived timestamp below,
     * so two files from the same boot can be matched with certainty.
     */
    private fun bootCount(application: Application): Int? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return null
        return runCatching {
            Settings.Global.getInt(application.contentResolver, Settings.Global.BOOT_COUNT)
        }.getOrNull()
    }

    /**
     * Approximate wall-clock instant of boot, derived as now minus uptime.
     *
     * Derived rather than read, because Android exposes no `kern.boottime` equivalent. It shifts
     * slightly if the wall clock is adjusted mid-boot, which is why `bootCount` travels alongside
     * it — this value exists to be comparable with the iOS side, that one to be exact.
     */
    private fun bootIdentifier(): String =
        iso8601(System.currentTimeMillis() - SystemClock.elapsedRealtime())

    // MARK: - Formatting

    private val formatter = ThreadLocal.withInitial {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.US)
    }

    /**
     * ISO 8601 with milliseconds and a local UTC offset.
     *
     * The offset is written by hand rather than with the `XXX` pattern letter, which Android's
     * `SimpleDateFormat` only understands from API 24 — this app supports 21. The offset itself is
     * deliberate: a drive log is read next to a route recorded in local time.
     */
    fun iso8601(millis: Long): String {
        val base = formatter.get()!!.format(Date(millis))
        val offsetMinutes = TimeZone.getDefault().getOffset(millis) / 60000
        val sign = if (offsetMinutes < 0) '-' else '+'
        val absolute = kotlin.math.abs(offsetMinutes)
        return "%s%c%02d:%02d".format(Locale.US, base, sign, absolute / 60, absolute % 60)
    }

    private fun CioLogLevel.wire(): String = when (this) {
        CioLogLevel.NONE -> "none"
        CioLogLevel.ERROR -> "error"
        CioLogLevel.INFO -> "info"
        CioLogLevel.DEBUG -> "debug"
    }

    /** Encodes a string as a complete JSON string literal, quotes included. */
    fun json(value: String): String = buildString(value.length + 2) {
        append('"')
        for (character in value) {
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                else ->
                    // Control characters have no literal form and would produce a file no parser
                    // will read. Everything above U+001F is legal inside a JSON string as-is.
                    if (character < ' ') {
                        append("\\u%04x".format(Locale.US, character.code))
                    } else {
                        append(character)
                    }
            }
        }
        append('"')
    }
}
