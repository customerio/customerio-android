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
internal class NotificationChannelManagerTest : IntegrationTest() {
    private lateinit var notificationChannelManager: NotificationChannelManager
    private lateinit var mockNotificationManager: NotificationManager
    private lateinit var mockMetadata: Bundle
    private lateinit var mockAndroidVersionChecker: AndroidVersionChecker
    
    override fun setup(testConfig: TestConfig) {
        super.setup(testConfig)
        
        mockAndroidVersionChecker = mockk()
        // Default to returning true for isOreoOrHigher to simulate Android O+ environment
        every { mockAndroidVersionChecker.isOreoOrHigher() } returns true
        
        notificationChannelManager = NotificationChannelManager(mockAndroidVersionChecker)
        mockNotificationManager = mockk(relaxed = true)
        mockMetadata = Bundle()
    }
    
    @Test
    fun `createNotificationChannelIfNeededAndReturnChannelId should return package name as channel ID when metadata is null`() {
        // Given
        val expectedChannelId = contextMock.packageName
        
        // When
        val actualChannelId = notificationChannelManager.createNotificationChannelIfNeededAndReturnChannelId(
            context = contextMock,
            applicationName = "Test App",
            appMetaData = null,
            notificationManager = mockNotificationManager
        )
        
        // Then
        actualChannelId shouldBeEqualTo expectedChannelId
    }
    
    @Test
    fun `createNotificationChannelIfNeededAndReturnChannelId should return package name as channel ID when metadata does not contain channel ID`() {
        // Given
        val expectedChannelId = contextMock.packageName
        
        // When
        val actualChannelId = notificationChannelManager.createNotificationChannelIfNeededAndReturnChannelId(
            context = contextMock,
            applicationName = "Test App",
            appMetaData = mockMetadata,
            notificationManager = mockNotificationManager
        )
        
        // Then
        actualChannelId shouldBeEqualTo expectedChannelId
    }
    
    @Test
    fun `createNotificationChannelIfNeededAndReturnChannelId should return custom channel ID when metadata contains channel ID`() {
        // Given
        val expectedChannelId = "custom_channel_id"
        mockMetadata.putString(
            NotificationChannelManager.METADATA_NOTIFICATION_CHANNEL_ID,
            expectedChannelId
        )
        
        // When
        val actualChannelId = notificationChannelManager.createNotificationChannelIfNeededAndReturnChannelId(
            context = contextMock,
            applicationName = "Test App",
            appMetaData = mockMetadata,
            notificationManager = mockNotificationManager
        )
        
        // Then
        actualChannelId shouldBeEqualTo expectedChannelId
    }
    
    @Test
    fun `createNotificationChannelIfNeededAndReturnChannelId should create channel with default name when metadata does not contain channel name`() {
        // Given
        val applicationName = "Test App"
        val expectedChannelName = "$applicationName Notifications"
        val channelSlot = slot<NotificationChannel>()
        
        // When
        notificationChannelManager.createNotificationChannelIfNeededAndReturnChannelId(
            context = contextMock,
            applicationName = applicationName,
            appMetaData = mockMetadata,
            notificationManager = mockNotificationManager
        )
        
        // Then
        verify { mockNotificationManager.createNotificationChannel(capture(channelSlot)) }
        channelSlot.captured.name shouldBeEqualTo expectedChannelName
    }
    
    @Test
    fun `createNotificationChannelIfNeededAndReturnChannelId should create channel with custom name when metadata contains channel name`() {
        // Given
        val expectedChannelName = "Custom Channel Name"
        mockMetadata.putString(
            NotificationChannelManager.METADATA_NOTIFICATION_CHANNEL_NAME,
            expectedChannelName
        )
        val channelSlot = slot<NotificationChannel>()
        
        // When
        notificationChannelManager.createNotificationChannelIfNeededAndReturnChannelId(
            context = contextMock,
            applicationName = "Test App",
            appMetaData = mockMetadata,
            notificationManager = mockNotificationManager
        )
        
        // Then
        verify { mockNotificationManager.createNotificationChannel(capture(channelSlot)) }
        channelSlot.captured.name shouldBeEqualTo expectedChannelName
    }
    
    @Test
    fun `createNotificationChannelIfNeededAndReturnChannelId should create channel with default importance when metadata does not contain importance`() {
        // Given
        val expectedImportance = NotificationManager.IMPORTANCE_DEFAULT
        val channelSlot = slot<NotificationChannel>()
        
        // When
        notificationChannelManager.createNotificationChannelIfNeededAndReturnChannelId(
            context = contextMock,
            applicationName = "Test App",
            appMetaData = mockMetadata,
            notificationManager = mockNotificationManager
        )
        
        // Then
        verify { mockNotificationManager.createNotificationChannel(capture(channelSlot)) }
        channelSlot.captured.importance shouldBeEqualTo expectedImportance
    }
    
    @Test
    fun `createNotificationChannelIfNeededAndReturnChannelId should create channel with custom importance when metadata contains importance`() {
        // Given
        val expectedImportance = NotificationManager.IMPORTANCE_HIGH
        mockMetadata.putInt(
            NotificationChannelManager.METADATA_NOTIFICATION_CHANNEL_IMPORTANCE,
            expectedImportance
        )
        val channelSlot = slot<NotificationChannel>()
        
        // When
        notificationChannelManager.createNotificationChannelIfNeededAndReturnChannelId(
            context = contextMock,
            applicationName = "Test App",
            appMetaData = mockMetadata,
            notificationManager = mockNotificationManager
        )
        
        // Then
        verify { mockNotificationManager.createNotificationChannel(capture(channelSlot)) }
        channelSlot.captured.importance shouldBeEqualTo expectedImportance
    }
    
    @Test
    fun `createNotificationChannelIfNeededAndReturnChannelId should not create channel on pre-Oreo devices`() {
        // Given
        every { mockAndroidVersionChecker.isOreoOrHigher() } returns false
        
        // When
        notificationChannelManager.createNotificationChannelIfNeededAndReturnChannelId(
            context = contextMock,
            applicationName = "Test App",
            appMetaData = mockMetadata,
            notificationManager = mockNotificationManager
        )
        
        // Then
        verify(exactly = 0) { mockNotificationManager.createNotificationChannel(any()) }
    }
    
    @Test
    fun `createNotificationChannelIfNeededAndReturnChannelId should create channel on Oreo devices`() {
        // Given
        
        // When
        notificationChannelManager.createNotificationChannelIfNeededAndReturnChannelId(
            context = contextMock,
            applicationName = "Test App",
            appMetaData = mockMetadata,
            notificationManager = mockNotificationManager
        )
        
        // Then
        verify(exactly = 1) { mockNotificationManager.createNotificationChannel(any()) }
    }
    
    @Test
    fun `createNotificationChannelIfNeededAndReturnChannelId should delete default channel when custom channel ID is provided`() {
        // Given
        val defaultChannelId = contextMock.packageName
        val customChannelId = "custom_channel_id"
        mockMetadata.putString(
            NotificationChannelManager.METADATA_NOTIFICATION_CHANNEL_ID,
            customChannelId
        )
        
        // Mock that the default channel exists
        val mockDefaultChannel = mockk<NotificationChannel>()
        every { mockNotificationManager.getNotificationChannel(defaultChannelId) } returns mockDefaultChannel
        
        // When
        val actualChannelId = notificationChannelManager.createNotificationChannelIfNeededAndReturnChannelId(
            context = contextMock,
            applicationName = "Test App",
            appMetaData = mockMetadata,
            notificationManager = mockNotificationManager
        )
        
        // Then
        actualChannelId shouldBeEqualTo customChannelId
        verify { mockNotificationManager.getNotificationChannel(defaultChannelId) }
        verify { mockNotificationManager.deleteNotificationChannel(defaultChannelId) }
        verify { mockNotificationManager.createNotificationChannel(any()) }
    }
    
    @Test
    fun `createNotificationChannelIfNeededAndReturnChannelId should not delete default channel when custom channel ID is same as default`() {
        // Given
        val defaultChannelId = contextMock.packageName
        mockMetadata.putString(
            NotificationChannelManager.METADATA_NOTIFICATION_CHANNEL_ID,
            defaultChannelId
        )
        
        // When
        val actualChannelId = notificationChannelManager.createNotificationChannelIfNeededAndReturnChannelId(
            context = contextMock,
            applicationName = "Test App",
            appMetaData = mockMetadata,
            notificationManager = mockNotificationManager
        )
        
        // Then
        actualChannelId shouldBeEqualTo defaultChannelId
        verify(exactly = 0) { mockNotificationManager.getNotificationChannel(any()) }
        verify(exactly = 0) { mockNotificationManager.deleteNotificationChannel(any()) }
        verify { mockNotificationManager.createNotificationChannel(any()) }
    }
    
    @Test
    fun `createNotificationChannelIfNeededAndReturnChannelId should handle exception when deleting channel`() {
        // Given
        val defaultChannelId = contextMock.packageName
        val customChannelId = "custom_channel_id"
        mockMetadata.putString(
            NotificationChannelManager.METADATA_NOTIFICATION_CHANNEL_ID,
            customChannelId
        )
        
        // Mock that the default channel exists but throws exception when deleting
        val mockDefaultChannel = mockk<NotificationChannel>()
        every { mockNotificationManager.getNotificationChannel(defaultChannelId) } returns mockDefaultChannel
        every { mockNotificationManager.deleteNotificationChannel(defaultChannelId) } throws RuntimeException("Test exception")
        
        // When - This should not throw an exception
        val actualChannelId = notificationChannelManager.createNotificationChannelIfNeededAndReturnChannelId(
            context = contextMock,
            applicationName = "Test App",
            appMetaData = mockMetadata,
            notificationManager = mockNotificationManager
        )
        
        // Then
        actualChannelId shouldBeEqualTo customChannelId
        verify { mockNotificationManager.getNotificationChannel(defaultChannelId) }
        verify { mockNotificationManager.deleteNotificationChannel(defaultChannelId) }
        verify { mockNotificationManager.createNotificationChannel(any()) }
    }
}
