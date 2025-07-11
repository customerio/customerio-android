package io.customer.messagingpush.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Bundle
import io.customer.commontest.config.TestConfig
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

    private val notificationChannelCreator = NotificationChannelCreator(mockAndroidVersionChecker)

    override fun setup(testConfig: TestConfig) {
        super.setup(testConfig)
        every { mockAndroidVersionChecker.isOreoOrHigher() } returns true
    }

    @Test
    fun createNotificationChannelIfNeededAndReturnChannelId_givenNullMetadata_shouldReturnPackageNameAsChannelId() {
        val expectedChannelId = contextMock.packageName

        val actualChannelId = notificationChannelCreator.createNotificationChannelIfNeededAndReturnChannelId(
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

        val actualChannelId = notificationChannelCreator.createNotificationChannelIfNeededAndReturnChannelId(
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

        val actualChannelId = notificationChannelCreator.createNotificationChannelIfNeededAndReturnChannelId(
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

        verify { mockNotificationManager.createNotificationChannel(capture(channelSlot)) }
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

        verify { mockNotificationManager.createNotificationChannel(capture(channelSlot)) }
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

        verify { mockNotificationManager.createNotificationChannel(capture(channelSlot)) }
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

        verify { mockNotificationManager.createNotificationChannel(capture(channelSlot)) }
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

        verify(exactly = 1) { mockNotificationManager.createNotificationChannel(any()) }
    }

    @Test
    fun createNotificationChannelIfNeededAndReturnChannelId_givenChannelDoesNotExist_shouldCreateChannel() {
        val channelId = contextMock.packageName
        every { mockNotificationManager.getNotificationChannel(channelId) } returns null

        val actualChannelId = notificationChannelCreator.createNotificationChannelIfNeededAndReturnChannelId(
            context = contextMock,
            applicationName = "Test App",
            appMetaData = mockMetadata,
            notificationManager = mockNotificationManager
        )

        actualChannelId shouldBeEqualTo channelId
        verify { mockNotificationManager.getNotificationChannel(channelId) }
        verify { mockNotificationManager.createNotificationChannel(any()) }
    }

    @Test
    fun createNotificationChannelIfNeededAndReturnChannelId_givenChannelExistsWithDifferentName_shouldRecreateChannel() {
        val channelId = contextMock.packageName
        val oldChannelName = "Old Channel Name"

        val mockExistingChannel = mockk<NotificationChannel>()
        every { mockExistingChannel.name } returns oldChannelName
        every { mockNotificationManager.getNotificationChannel(channelId) } returns mockExistingChannel

        val actualChannelId = notificationChannelCreator.createNotificationChannelIfNeededAndReturnChannelId(
            context = contextMock,
            applicationName = "Test App",
            appMetaData = mockMetadata,
            notificationManager = mockNotificationManager
        )

        actualChannelId shouldBeEqualTo channelId
        verify { mockNotificationManager.getNotificationChannel(channelId) }
        verify { mockNotificationManager.createNotificationChannel(any()) }
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

        val actualChannelId = notificationChannelCreator.createNotificationChannelIfNeededAndReturnChannelId(
            context = contextMock,
            applicationName = "Test App",
            appMetaData = mockMetadata,
            notificationManager = mockNotificationManager
        )

        actualChannelId shouldBeEqualTo channelId
        verify { mockNotificationManager.getNotificationChannel(channelId) }
        verify(exactly = 0) { mockNotificationManager.createNotificationChannel(any()) }
    }
}
