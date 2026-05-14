package io.customer.sdk.data.store

import io.customer.commontest.core.RobolectricTest
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class GlobalPreferenceStoreImplTest : RobolectricTest() {

    private fun createStore(): GlobalPreferenceStore = GlobalPreferenceStoreImpl(applicationMock)

    @Test
    fun getDeviceToken_givenNoTokenSaved_expectNull() {
        val store = createStore()

        store.getDeviceToken().shouldBeNull()
    }

    @Test
    fun getDeviceToken_givenTokenSaved_expectSavedToken() {
        val store = createStore()
        val givenToken = "test-device-token"

        store.saveDeviceToken(givenToken)

        store.getDeviceToken() shouldBeEqualTo givenToken
    }

    @Test
    fun getInstallationId_givenNoIdSaved_expectNull() {
        val store = createStore()

        store.getInstallationId().shouldBeNull()
    }

    @Test
    fun getInstallationId_givenIdSaved_expectSavedId() {
        val store = createStore()
        val givenId = "00000000-0000-0000-0000-000000000001"

        store.saveInstallationId(givenId)

        store.getInstallationId() shouldBeEqualTo givenId
    }

    @Test
    fun getInstallationId_givenStoreRecreated_expectValuePersisted() {
        val initialStore = createStore()
        val givenId = "00000000-0000-0000-0000-000000000002"
        initialStore.saveInstallationId(givenId)

        val newStore = createStore()

        newStore.getInstallationId() shouldBeEqualTo givenId
    }

    @Test
    fun saveInstallationId_givenValueOverwritten_expectLatestValueReturned() {
        val store = createStore()
        val firstId = "00000000-0000-0000-0000-000000000003"
        val secondId = "00000000-0000-0000-0000-000000000004"

        store.saveInstallationId(firstId)
        store.saveInstallationId(secondId)

        store.getInstallationId() shouldBeEqualTo secondId
    }

    @Test
    fun getInstallationId_givenClearAllInvoked_expectIdPreserved() {
        val store = createStore()
        val givenId = "00000000-0000-0000-0000-000000000005"
        val givenDeviceToken = "device-token-to-wipe"
        store.saveInstallationId(givenId)
        store.saveDeviceToken(givenDeviceToken)

        store.clearAll()

        store.getInstallationId() shouldBeEqualTo givenId
        store.getDeviceToken().shouldBeNull()
    }
}
