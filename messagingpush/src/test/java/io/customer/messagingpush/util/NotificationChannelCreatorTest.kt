package io.customer.messagingpush.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Bundle
import io.customer.commontest.config.TestConfig
import io.customer.messagingpush.logger.PushNotificationLogger
import io.customer.messagingpush.testutils.core.IntegrationTest
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
internal class NotificationChannelCreatorTest : IntegrationTest() {
    private val mockNotificationManager = mockk<NotificationManager>(relaxed = true)
    private val mockMetadata = Bundle()
    private val mockAndroidVersionChecker = mockk<AndroidVersionChecker>()
    private val mockLogger = mockk<PushNotificationLogger>(relaxed = true)

    private val notificationChannelCreator =
        NotificationChannelCreator(mockAndroidVersionChecker, mockLogger)

    override fun setup(testConfig: TestConfig) {
        super.setup(testConfig)
        every { mockAndroidVersionChecker.isOreoOrHigher() } returns true
    }

    @Test
    fun createNotificationChannelIfNeededAndReturnChannelId_givenNullMetadata_shouldReturnPackageNameAsChannelId() {
        val expectedChannelId = contextMock.packageName

        val actualChannelId =
            notificationChannelCreator.createNotificationChannelIfNeededAndReturnChannelId(
                context = contextMock,
                applicationName = "Test App",
                appMetaData = null,
                notificationManager = mockNotificationManager
            )

        actualChannelId shouldBeEqualTo expectedChannelId
    }

    @Test
    fun createNotificationChannelIfNeededAndReturnChannelId_givenMetadataWithoutChannelId_shouldReturnPackageNameAsChannelId() {
        val expectedChannelId = contextMock.packageName

        val actualChannelId =
            notificationChannelCreator.createNotificationChannelIfNeededAndReturnChannelId(
                context = contextMock,
                applicationName = "Test App",
                appMetaData = mockMetadata,
                notificationManager = mockNotificationManager
            )

        actualChannelId shouldBeEqualTo expectedChannelId
    }

    @Test
    fun createNotificationChannelIfNeededAndReturnChannelId_givenMetadataWithChannelId_shouldReturnCustomChannelId() {
        val expectedChannelId = "custom_channel_id"
        mockMetadata.putString(
            NotificationChannelCreator.METADATA_NOTIFICATION_CHANNEL_ID,
            expectedChannelId
        )

        val actualChannelId =
            notificationChannelCreator.createNotificationChannelIfNeededAndReturnChannelId(
                context = contextMock,
                applicationName = "Test App",
                appMetaData = mockMetadata,
                notificationManager = mockNotificationManager
            )

        actualChannelId shouldBeEqualTo expectedChannelId
    }

    @Test
    fun createNotificationChannelIfNeededAndReturnChannelId_givenMetadataWithoutChannelName_shouldCreateChannelWithDefaultName() {
        val applicationName = "Test App"
        val expectedChannelName = "$applicationName Notifications"
        val channelSlot = slot<NotificationChannel>()

        notificationChannelCreator.createNotificationChannelIfNeededAndReturnChannelId(
            context = contextMock,
            applicationName = applicationName,
            appMetaData = mockMetadata,
            notificationManager = mockNotificationManager
        )

        val channelId = contextMock.packageName
        verify { mockNotificationManager.createNotificationChannel(capture(channelSlot)) }
        verify {
            mockLogger.logCreatingNotificationChannel(
                channelId = channelId,
                channelName = expectedChannelName,
                importance = NotificationManager.IMPORTANCE_DEFAULT
            )
        }
        channelSlot.captured.name shouldBeEqualTo expectedChannelName
    }

    @Test
    fun createNotificationChannelIfNeededAndReturnChannelId_givenMetadataWithChannelName_shouldCreateChannelWithCustomName() {
        val expectedChannelName = "Custom Channel Name"
        mockMetadata.putString(
            NotificationChannelCreator.METADATA_NOTIFICATION_CHANNEL_NAME,
            expectedChannelName
        )
        val channelSlot = slot<NotificationChannel>()

        notificationChannelCreator.createNotificationChannelIfNeededAndReturnChannelId(
            context = contextMock,
            applicationName = "Test App",
            appMetaData = mockMetadata,
            notificationManager = mockNotificationManager
        )

        val channelId = contextMock.packageName
        verify { mockNotificationManager.createNotificationChannel(capture(channelSlot)) }
        verify {
            mockLogger.logCreatingNotificationChannel(
                channelId = channelId,
                channelName = expectedChannelName,
                importance = NotificationManager.IMPORTANCE_DEFAULT
            )
        }
        channelSlot.captured.name shouldBeEqualTo expectedChannelName
    }

    @Test
    fun createNotificationChannelIfNeededAndReturnChannelId_givenMetadataWithoutImportance_shouldCreateChannelWithDefaultImportance() {
        val expectedImportance = NotificationManager.IMPORTANCE_DEFAULT
        val channelSlot = slot<NotificationChannel>()

        notificationChannelCreator.createNotificationChannelIfNeededAndReturnChannelId(
            context = contextMock,
            applicationName = "Test App",
            appMetaData = mockMetadata,
            notificationManager = mockNotificationManager
        )

        val channelId = contextMock.packageName
        verify { mockNotificationManager.createNotificationChannel(capture(channelSlot)) }
        verify {
            mockLogger.logCreatingNotificationChannel(
                channelId = channelId,
                channelName = "Test App Notifications",
                importance = expectedImportance
            )
        }
        channelSlot.captured.importance shouldBeEqualTo expectedImportance
    }

    @Test
    fun createNotificationChannelIfNeededAndReturnChannelId_givenMetadataWithImportance_shouldCreateChannelWithCustomImportance() {
        val expectedImportance = NotificationManager.IMPORTANCE_HIGH
        mockMetadata.putInt(
            NotificationChannelCreator.METADATA_NOTIFICATION_CHANNEL_IMPORTANCE,
            expectedImportance
        )
        val channelSlot = slot<NotificationChannel>()

        notificationChannelCreator.createNotificationChannelIfNeededAndReturnChannelId(
            context = contextMock,
            applicationName = "Test App",
            appMetaData = mockMetadata,
            notificationManager = mockNotificationManager
        )

        val channelId = contextMock.packageName
        verify { mockNotificationManager.createNotificationChannel(capture(channelSlot)) }
        verify {
            mockLogger.logCreatingNotificationChannel(
                channelId = channelId,
                channelName = "Test App Notifications",
                importance = expectedImportance
            )
        }
        channelSlot.captured.importance shouldBeEqualTo expectedImportance
    }

    @Test
    fun createNotificationChannelIfNeededAndReturnChannelId_givenPreOreoDevice_shouldNotCreateChannel() {
        every { mockAndroidVersionChecker.isOreoOrHigher() } returns false

        notificationChannelCreator.createNotificationChannelIfNeededAndReturnChannelId(
            context = contextMock,
            applicationName = "Test App",
            appMetaData = mockMetadata,
            notificationManager = mockNotificationManager
        )

        verify(exactly = 0) { mockNotificationManager.createNotificationChannel(any()) }
    }

    @Test
    fun createNotificationChannelIfNeededAndReturnChannelId_givenOreoDevice_shouldCreateChannel() {
        notificationChannelCreator.createNotificationChannelIfNeededAndReturnChannelId(
            context = contextMock,
            applicationName = "Test App",
            appMetaData = mockMetadata,
            notificationManager = mockNotificationManager
        )

        val channelId = contextMock.packageName
        verify(exactly = 1) { mockNotificationManager.createNotificationChannel(any()) }
        verify {
            mockLogger.logCreatingNotificationChannel(
                channelId = channelId,
                channelName = "Test App Notifications",
                importance = NotificationManager.IMPORTANCE_DEFAULT
            )
        }
    }

    @Test
    fun createNotificationChannelIfNeededAndReturnChannelId_givenChannelDoesNotExist_shouldCreateChannel() {
        val channelId = contextMock.packageName
        every { mockNotificationManager.getNotificationChannel(channelId) } returns null

        val actualChannelId =
            notificationChannelCreator.createNotificationChannelIfNeededAndReturnChannelId(
                context = contextMock,
                applicationName = "Test App",
                appMetaData = mockMetadata,
                notificationManager = mockNotificationManager
            )

        actualChannelId shouldBeEqualTo channelId
        verify { mockNotificationManager.getNotificationChannel(channelId) }
        verify { mockNotificationManager.createNotificationChannel(any()) }
        verify {
            mockLogger.logCreatingNotificationChannel(
                channelId = channelId,
                channelName = "Test App Notifications",
                importance = NotificationManager.IMPORTANCE_DEFAULT
            )
        }
    }

    @Test
    fun createNotificationChannelIfNeededAndReturnChannelId_givenChannelExistsWithDifferentName_shouldRecreateChannel() {
        val channelId = contextMock.packageName
        val oldChannelName = "Old Channel Name"

        val mockExistingChannel = mockk<NotificationChannel>()
        every { mockExistingChannel.name } returns oldChannelName
        every { mockNotificationManager.getNotificationChannel(channelId) } returns mockExistingChannel

        val actualChannelId =
            notificationChannelCreator.createNotificationChannelIfNeededAndReturnChannelId(
                context = contextMock,
                applicationName = "Test App",
                appMetaData = mockMetadata,
                notificationManager = mockNotificationManager
            )

        actualChannelId shouldBeEqualTo channelId
        verify { mockNotificationManager.getNotificationChannel(channelId) }
        verify { mockNotificationManager.createNotificationChannel(any()) }
        verify {
            mockLogger.logCreatingNotificationChannel(
                channelId = channelId,
                channelName = "Test App Notifications",
                importance = NotificationManager.IMPORTANCE_DEFAULT
            )
        }
    }

    @Test
    fun createNotificationChannelIfNeededAndReturnChannelId_givenChannelExistsWithSameName_shouldNotRecreateChannel() {
        val channelId = contextMock.packageName
        val channelName = "Custom channel name"
        mockMetadata.putString(
            NotificationChannelCreator.METADATA_NOTIFICATION_CHANNEL_NAME,
            channelName
        )

        val mockExistingChannel = mockk<NotificationChannel>()
        every { mockExistingChannel.name } returns channelName
        every { mockNotificationManager.getNotificationChannel(channelId) } returns mockExistingChannel

        val actualChannelId =
            notificationChannelCreator.createNotificationChannelIfNeededAndReturnChannelId(
                context = contextMock,
                applicationName = "Test App",
                appMetaData = mockMetadata,
                notificationManager = mockNotificationManager
            )

        actualChannelId shouldBeEqualTo channelId
        verify { mockNotificationManager.getNotificationChannel(channelId) }
        verify(exactly = 0) { mockNotificationManager.createNotificationChannel(any()) }
        verify { mockLogger.logNotificationChannelAlreadyExists(channelId) }
    }

    @Test
    fun createNotificationChannelIfNeededAndReturnChannelId_givenMetadataWithInvalidImportance_shouldCreateChannelWithDefaultImportance() {
        // Use an invalid importance value (not one of the NotificationManager constants)
        val invalidImportance = 999
        val expectedImportance = NotificationManager.IMPORTANCE_DEFAULT
        mockMetadata.putInt(
            NotificationChannelCreator.METADATA_NOTIFICATION_CHANNEL_IMPORTANCE,
            invalidImportance
        )
        val channelSlot = slot<NotificationChannel>()

        notificationChannelCreator.createNotificationChannelIfNeededAndReturnChannelId(
            context = contextMock,
            applicationName = "Test App",
            appMetaData = mockMetadata,
            notificationManager = mockNotificationManager
        )

        val channelId = contextMock.packageName
        verify { mockNotificationManager.createNotificationChannel(capture(channelSlot)) }
        // Verify that the invalid importance level is logged
        verify { mockLogger.logInvalidNotificationChannelImportance(invalidImportance) }
        verify {
            mockLogger.logCreatingNotificationChannel(
                channelId = channelId,
                channelName = "Test App Notifications",
                importance = expectedImportance
            )
        }
        // Verify that the invalid importance was replaced with the default importance
        channelSlot.captured.importance shouldBeEqualTo expectedImportance
    }
}
