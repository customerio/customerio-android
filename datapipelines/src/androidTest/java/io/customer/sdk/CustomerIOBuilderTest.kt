package io.customer.sdk

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.customer.commontest.BaseTest
import io.customer.commontest.module.CustomerIOGenericModule
import io.customer.sdk.extensions.random
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@RunWith(AndroidJUnit4::class)
class CustomerIOBuilderTest : BaseTest() {

    @Test
    fun build_givenModule_expectInitializeModule() {
        val givenModule: CustomerIOGenericModule = mock<CustomerIOGenericModule>().apply {
            whenever(this.moduleName).thenReturn(String.random)
        }

        createCustomerIOBuilder()
            .addCustomerIOModule(givenModule)
            .build()

        verify(givenModule).initialize()
    }

    @Test
    fun build_givenMultipleModules_expectInitializeAllModules() {
        val givenModule1: CustomerIOGenericModule = mock<CustomerIOGenericModule>().apply {
            whenever(this.moduleName).thenReturn(String.random)
        }
        val givenModule2: CustomerIOGenericModule = mock<CustomerIOGenericModule>().apply {
            whenever(this.moduleName).thenReturn(String.random)
        }

        createCustomerIOBuilder()
            .addCustomerIOModule(givenModule1)
            .addCustomerIOModule(givenModule2)
            .build()

        verify(givenModule1).initialize()
        verify(givenModule2).initialize()
    }

    @Test
    fun build_givenMultipleModulesOfSameType_expectOnlyInitializeOneModuleInstance() {
        val givenModule1: CustomerIOGenericModule = mock<CustomerIOGenericModule>().apply {
            whenever(this.moduleName).thenReturn("shared-module-name")
        }
        val givenModule2: CustomerIOGenericModule = mock<CustomerIOGenericModule>().apply {
            whenever(this.moduleName).thenReturn("shared-module-name")
        }

        createCustomerIOBuilder()
            .addCustomerIOModule(givenModule1)
            .addCustomerIOModule(givenModule2)
            .build()

        verify(givenModule1, never()).initialize()
        verify(givenModule2).initialize()
    }

    private fun createCustomerIOBuilder(): CustomerIOBuilder = CustomerIOBuilder(
        applicationContext = application,
        cdpApiKey = String.random
    )
}
