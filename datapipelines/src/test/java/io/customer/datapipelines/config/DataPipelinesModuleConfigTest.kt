package io.customer.datapipelines.config

import io.customer.datapipelines.testutils.core.JUnitTest
import io.customer.sdk.data.model.Region
import org.amshove.kluent.internal.assertEquals
import org.junit.jupiter.api.Test

class DataPipelinesModuleConfigTest : JUnitTest() {

    @Test
    fun test_toString_generatesCorrectRepresentation() {
        val config = DataPipelinesModuleConfig(
            cdpApiKey = "anyKey",
            region = Region.EU,
            apiHostOverride = "test.domain.io/v1",
            cdnHostOverride = "any.domain.io/v1",
            flushAt = 20,
            flushInterval = 30,
            flushPolicies = emptyList(),
            autoAddCustomerIODestination = true,
            trackApplicationLifecycleEvents = true,
            autoTrackDeviceAttributes = true,
            autoTrackActivityScreens = true,
            migrationSiteId = "anyId",
            screenViewUse = ScreenView.All
        )

        val actual = config.toString()
        assertEquals("DataPipelinesModuleConfig(cdpApiKey='[Redacted]', flushAt=20, flushInterval=30, flushPolicies=[], autoAddCustomerIODestination=true, trackApplicationLifecycleEvents=true, autoTrackDeviceAttributes=true, autoTrackActivityScreens=true, migrationSiteId=[Redacted], screenViewUse=ScreenView('all'), apiHost='test.domain.io/v1', cdnHost='any.domain.io/v1')", actual)
    }
}
