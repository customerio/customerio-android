package io.customer.datapipelines.plugins

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.os.Build
import android.os.Bundle
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.segment.analytics.kotlin.core.Analytics
import com.segment.analytics.kotlin.core.Storage
import com.segment.analytics.kotlin.core.platform.Plugin
import io.customer.sdk.util.EventNames
import java.util.Collections
import java.util.WeakHashMap
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Plugin that automatically tracks application lifecycle events: Application Installed, Application Updated,
 * Application Opened, Application Foregrounded and Application Backgrounded.
 *
 * Install/update detection runs while the plugin is set up during SDK initialization, so it doesn't depend on
 * seeing the first Activity being created. Apps that initialize the SDK after their first Activity already exists
 * (e.g. React Native and Flutter apps) still get these events on first launch, ahead of any event they track
 * after initialization.
 *
 * The app is in foreground while at least one Activity is started. Activities started before the SDK was
 * initialized are counted the first time any of their callbacks is received.
 */
internal class AutomaticApplicationLifecycleTrackingPlugin @VisibleForTesting constructor(
    private val application: Application,
    appVersionProvider: () -> AppVersion,
    private val isAppInForeground: () -> Boolean
) : Plugin, Application.ActivityLifecycleCallbacks {

    constructor(application: Application) : this(
        application = application,
        appVersionProvider = { application.getAppVersion() },
        isAppInForeground = ::isProcessInForeground
    )

    override val type: Plugin.Type = Plugin.Type.Utility
    override lateinit var analytics: Analytics

    private val appVersion: AppVersion by lazy(appVersionProvider)

    // Guards the state below, which is updated from SDK initialization and from Activity callbacks on main thread
    private val lock = Any()

    // Held weakly so the plugin never keeps a stopped or leaked Activity alive
    private val startedActivities: MutableSet<Activity> = Collections.newSetFromMap(WeakHashMap())
    private var isInForeground = false
    private var hasTrackedFirstOpen = false

    override fun setup(analytics: Analytics) {
        super.setup(analytics)
        synchronized(lock) {
            // Registered first so no transition is missed; callbacks wait for the lock until the initial state is set
            application.registerActivityLifecycleCallbacks(this)
            trackInstallOrUpdate()
            if (isAppInForeground()) {
                moveToForeground()
            }
        }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

    override fun onActivityStarted(activity: Activity) = onActivityVisible(activity)

    override fun onActivityResumed(activity: Activity) = onActivityVisible(activity)

    // A paused Activity is still started. Counting it here covers Activities started before SDK initialization.
    override fun onActivityPaused(activity: Activity) = onActivityVisible(activity)

    override fun onActivityStopped(activity: Activity) = synchronized(lock) {
        startedActivities.remove(activity)
        // A configuration change stops and recreates the Activity, and the new instance starts right after
        if (isInForeground && startedActivities.isEmpty() && !activity.isChangingConfigurations) {
            isInForeground = false
            analytics.track(EventNames.APPLICATION_BACKGROUNDED)
        }
    }

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    override fun onActivityDestroyed(activity: Activity) {
        synchronized(lock) { startedActivities.remove(activity) }
    }

    private fun onActivityVisible(activity: Activity) = synchronized(lock) {
        startedActivities.add(activity)
        if (!isInForeground) {
            moveToForeground()
        }
    }

    private fun moveToForeground() {
        isInForeground = true
        val isFirstOpen = !hasTrackedFirstOpen
        hasTrackedFirstOpen = true
        analytics.track(
            APPLICATION_OPENED,
            buildJsonObject {
                if (isFirstOpen) {
                    put(VERSION_KEY, appVersion.version)
                    put(BUILD_KEY, appVersion.build)
                }
                put("from_background", !isFirstOpen)
            }
        )
        analytics.track(APPLICATION_FOREGROUNDED)
    }

    private fun trackInstallOrUpdate() {
        val storage = analytics.storage
        val previousVersion = storage.read(Storage.Constants.AppVersion)
        val previousBuild = storage.read(Storage.Constants.AppBuild) ?: storage.read(Storage.Constants.LegacyAppBuild)

        if (previousBuild == null) {
            analytics.track(
                APPLICATION_INSTALLED,
                buildJsonObject {
                    put(VERSION_KEY, appVersion.version)
                    put(BUILD_KEY, appVersion.build)
                }
            )
        } else if (previousBuild != appVersion.build) {
            analytics.track(
                APPLICATION_UPDATED,
                buildJsonObject {
                    put(VERSION_KEY, appVersion.version)
                    put(BUILD_KEY, appVersion.build)
                    put("previous_$VERSION_KEY", previousVersion)
                    put("previous_$BUILD_KEY", previousBuild)
                }
            )
        }

        // Same keys Segment's lifecycle tracking used, so existing installs keep their recorded version
        with(analytics) {
            analyticsScope.launch(analyticsDispatcher) {
                appVersion.version?.let { storage.write(Storage.Constants.AppVersion, it) }
                storage.write(Storage.Constants.AppBuild, appVersion.build)
            }
        }
    }

    companion object {
        private const val APPLICATION_INSTALLED = "Application Installed"
        private const val APPLICATION_UPDATED = "Application Updated"
        private const val APPLICATION_OPENED = "Application Opened"
        private const val APPLICATION_FOREGROUNDED = "Application Foregrounded"
        private const val VERSION_KEY = "version"
        private const val BUILD_KEY = "build"
    }
}

/** Version name and version code of the host app. */
internal data class AppVersion(val version: String?, val build: String)

private fun Application.getAppVersion(): AppVersion {
    val packageInfo = packageManager.getPackageInfo(packageName, 0)
    val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        packageInfo.longVersionCode
    } else {
        @Suppress("DEPRECATION")
        packageInfo.versionCode.toLong()
    }
    return AppVersion(version = packageInfo.versionName, build = versionCode.toString())
}

private fun isProcessInForeground(): Boolean {
    // Reading the state is a plain field read, so this is safe from the thread initializing the SDK.
    // It stays INITIALIZED only when the process lifecycle isn't running in this process
    // (a secondary process, or an app that removed the androidx.startup initializer).
    val processState = ProcessLifecycleOwner.get().lifecycle.currentState
    if (processState != Lifecycle.State.INITIALIZED) {
        return processState.isAtLeast(Lifecycle.State.STARTED)
    }
    val processInfo = ActivityManager.RunningAppProcessInfo()
    ActivityManager.getMyMemoryState(processInfo)
    return processInfo.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE
}
