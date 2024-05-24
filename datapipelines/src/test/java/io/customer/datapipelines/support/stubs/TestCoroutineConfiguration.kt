package io.customer.datapipelines.support.stubs

import com.segment.analytics.kotlin.core.CoroutineConfiguration
import io.customer.datapipelines.support.utils.spyStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import sovran.kotlin.Store

/**
 * Test implementation of [CoroutineConfiguration] that uses [TestDispatcher] and
 * [TestScope] for testing.
 */
@Suppress("MemberVisibilityCanBePrivate")
@OptIn(ExperimentalCoroutinesApi::class)
class TestCoroutineConfiguration(
    val testDispatcher: TestDispatcher = UnconfinedTestDispatcher(),
    val testScope: TestScope = TestScope(testDispatcher)
) : CoroutineConfiguration {
    override val store: Store = spyStore(testScope, testDispatcher)
    override val analyticsScope: CoroutineScope get() = testScope
    override val analyticsDispatcher: CoroutineDispatcher get() = testDispatcher
    override val networkIODispatcher: CoroutineDispatcher get() = testDispatcher
    override val fileIODispatcher: CoroutineDispatcher get() = testDispatcher
}
