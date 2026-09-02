package io.customer.android.sample.java_layout.diagnostics

import android.app.Activity
import android.app.Application
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock

/**
 * The `dev` block attached to every record.
 */
internal class DiagnosticDeviceState(private val application: Application) {
    private data class Snapshot(
        /** `null` until a battery broadcast has been seen; never a sentinel. */
        val batteryLevel: Float? = null,
        val charging: Boolean = false,
        val powerSave: Boolean = false,
        val idle: Boolean = false,
        val thermal: String = "unknown",
        val network: String = "unknown",
        val foreground: Boolean = false
    )

    private val lock = Any()
    private var snapshot = Snapshot()
    private var onChange: ((String) -> Unit)? = null
    private var startedActivities = 0

    @Volatile
    private var bucketCache = "unknown"

    @Volatile
    private var bucketReadAt = 0L

    private val powerManager: PowerManager? =
        application.getSystemService(Context.POWER_SERVICE) as? PowerManager
    private val connectivityManager: ConnectivityManager? =
        application.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    fun start(onChange: (String) -> Unit) {
        synchronized(lock) { this.onChange = onChange }

        readInitialState()
        registerSystemReceivers()
        registerNetworkCallback()
        registerLifecycleCallbacks()
        registerThermalListener()
    }

    /**
     * Safe to call from any thread: reads only the cached values, never a system service that
     * might block. SDK log records arrive on whatever thread the SDK happened to be using.
     */
    fun snapshotJson(): String {
        val current = synchronized(lock) { snapshot }
        return buildString(160) {
            append('{')
            append("\"batt\":").append(
                current.batteryLevel?.let { String.format(java.util.Locale.US, "%.2f", it) } ?: "null"
            )
            append(",\"charging\":").append(current.charging)
            append(",\"powerSave\":").append(current.powerSave)
            append(",\"idle\":").append(current.idle)
            append(",\"bucket\":").append(DiagnosticEnvelope.json(standbyBucket()))
            append(",\"thermal\":").append(DiagnosticEnvelope.json(current.thermal))
            append(",\"net\":").append(DiagnosticEnvelope.json(current.network))
            append(",\"fg\":").append(current.foreground)
            append('}')
        }
    }

    // MARK: - Registration

    private fun registerSystemReceivers() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_BATTERY_CHANGED -> update("batt") { current ->
                        val level = intent.getIntExtra("level", -1)
                        val scale = intent.getIntExtra("scale", -1)
                        val status = intent.getIntExtra("status", -1)
                        current.copy(
                            batteryLevel = if (level >= 0 && scale > 0) level.toFloat() / scale else null,
                            charging = status == android.os.BatteryManager.BATTERY_STATUS_CHARGING ||
                                status == android.os.BatteryManager.BATTERY_STATUS_FULL
                        )
                    }
                    PowerManager.ACTION_POWER_SAVE_MODE_CHANGED ->
                        update("powerSave") { it.copy(powerSave = powerManager?.isPowerSaveMode == true) }
                    PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED ->
                        update("idle") { it.copy(idle = isDeviceIdle()) }
                }
            }
        }

        val filter = IntentFilter().apply {
            // Sticky broadcast: registering delivers the current value immediately, without
            // costing a wakeup or a poll.
            addAction(Intent.ACTION_BATTERY_CHANGED)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
            }
        }

        // Screen and Doze broadcasts are protected system actions, so this stays exported=false;
        // `RECEIVER_NOT_EXPORTED` is required from API 34 and harmless to state earlier.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            application.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            application.registerReceiver(receiver, filter)
        }
    }

    private fun registerNetworkCallback() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        runCatching {
            connectivityManager?.registerDefaultNetworkCallback(
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) = refreshNetwork()
                    override fun onLost(network: Network) = update("net") { it.copy(network = "none") }
                    override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                        update("net") { it.copy(network = describe(capabilities)) }
                    }
                }
            )
        }
    }

    /**
     * Foreground state from activity callbacks rather than `ProcessLifecycleOwner`, so the sink
     * needs no extra dependency and behaves the same on every supported API level.
     */
    private fun registerLifecycleCallbacks() {
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                startedActivities += 1
                update("fg") { it.copy(foreground = startedActivities > 0) }
            }

            override fun onActivityStopped(activity: Activity) {
                startedActivities = (startedActivities - 1).coerceAtLeast(0)
                update("fg") { it.copy(foreground = startedActivities > 0) }
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    private fun registerThermalListener() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        runCatching {
            powerManager?.addThermalStatusListener { status ->
                update("thermal") { it.copy(thermal = describeThermal(status)) }
            }
        }
    }

    // MARK: - Reading

    private fun readInitialState() {
        // ACTION_BATTERY_CHANGED is sticky, so a null receiver returns the current value straight
        // away. Without this the first records of every session carry batt=-1 until the first real
        // 1% step arrives, which on a short background wake may be never.
        val battery = runCatching {
            application.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull()
        val level = battery?.getIntExtra("level", -1) ?: -1
        val scale = battery?.getIntExtra("scale", -1) ?: -1
        val status = battery?.getIntExtra("status", -1) ?: -1

        synchronized(lock) {
            snapshot = snapshot.copy(
                batteryLevel = if (level >= 0 && scale > 0) level.toFloat() / scale else null,
                charging = status == android.os.BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == android.os.BatteryManager.BATTERY_STATUS_FULL,
                powerSave = powerManager?.isPowerSaveMode == true,
                idle = isDeviceIdle(),
                thermal = currentThermal(),
                network = currentNetwork()
            )
        }
    }

    private fun refreshNetwork() = update("net") { it.copy(network = currentNetwork()) }

    private fun isDeviceIdle(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && powerManager?.isDeviceIdleMode == true

    private fun currentThermal(): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return "unsupported"
        return runCatching { describeThermal(powerManager?.currentThermalStatus ?: -1) }.getOrElse { "unknown" }
    }

    private fun currentNetwork(): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return "unknown"
        val manager = connectivityManager ?: return "unknown"
        return runCatching {
            val active = manager.activeNetwork ?: return "none"
            val capabilities = manager.getNetworkCapabilities(active) ?: return "none"
            describe(capabilities)
        }.getOrElse { "unknown" }
    }

    /**
     * Which app-standby bucket the OS has us in.
     */
    private fun standbyBucket(): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return "unsupported"
        val now = SystemClock.elapsedRealtime()
        if (bucketReadAt != 0L && now - bucketReadAt < BUCKET_TTL_MS) return bucketCache
        val value = readStandbyBucket()
        bucketCache = value
        bucketReadAt = now
        return value
    }

    private fun readStandbyBucket(): String {
        return runCatching {
            val manager = application.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            when (manager?.appStandbyBucket) {
                UsageStatsManager.STANDBY_BUCKET_ACTIVE -> "active"
                UsageStatsManager.STANDBY_BUCKET_WORKING_SET -> "working_set"
                UsageStatsManager.STANDBY_BUCKET_FREQUENT -> "frequent"
                UsageStatsManager.STANDBY_BUCKET_RARE -> "rare"
                45 -> "restricted" // STANDBY_BUCKET_RESTRICTED, API 30 — named literally for API 21 compile
                else -> "unknown"
            }
        }.getOrElse { "unknown" }
    }

    // MARK: - Descriptions

    private fun describe(capabilities: NetworkCapabilities): String = when {
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "wired"
        else -> "other"
    }

    private fun describeThermal(status: Int): String = when (status) {
        PowerManager.THERMAL_STATUS_NONE -> "nominal"
        PowerManager.THERMAL_STATUS_LIGHT -> "light"
        PowerManager.THERMAL_STATUS_MODERATE -> "moderate"
        PowerManager.THERMAL_STATUS_SEVERE -> "severe"
        PowerManager.THERMAL_STATUS_CRITICAL -> "critical"
        PowerManager.THERMAL_STATUS_EMERGENCY -> "emergency"
        PowerManager.THERMAL_STATUS_SHUTDOWN -> "shutdown"
        else -> "unknown"
    }

    /**
     * Writes a `device.state` record only when a value actually moved. Several broadcasts describe
     * the same transition, and recording each one would fill the file with rows saying nothing
     * changed — battery in particular fires on every 1% step.
     */
    private fun update(reason: String, mutate: (Snapshot) -> Snapshot) {
        val callback: ((String) -> Unit)?
        val changed: Boolean
        synchronized(lock) {
            val before = snapshot
            snapshot = mutate(before)
            changed = snapshot != before
            callback = onChange
        }
        if (changed) callback?.invoke(reason)
    }

    private companion object {
        const val BUCKET_TTL_MS = 60_000L
    }
}
