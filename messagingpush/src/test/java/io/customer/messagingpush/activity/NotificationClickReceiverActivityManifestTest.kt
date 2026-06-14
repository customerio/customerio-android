package io.customer.messagingpush.activity

import android.content.ComponentName
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.customer.messagingpush.testutils.core.IntegrationTest
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Guards the security hardening that makes [NotificationClickReceiverActivity] non-exported.
 *
 * The activity is only ever launched internally via the notification's PendingIntent (an
 * explicit-component intent dispatched with the host app's identity). It has no intent-filter
 * and must never be startable by other apps, so it must stay exported="false" in the merged
 * manifest. This test fails if the flag is ever reverted to exported="true".
 *
 * Note: reads the real Robolectric merged manifest via [ApplicationProvider], not the mocked
 * context/packageManager provided by the base class.
 */
@RunWith(RobolectricTestRunner::class)
class NotificationClickReceiverActivityManifestTest : IntegrationTest() {

    @Test
    fun activity_givenMergedManifest_expectExportedFalse() {
        val realContext = ApplicationProvider.getApplicationContext<Context>()
        val component = ComponentName(realContext, NotificationClickReceiverActivity::class.java)

        val activityInfo = realContext.packageManager.getActivityInfo(component, 0)

        activityInfo.exported shouldBeEqualTo false
    }
}
