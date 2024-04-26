package io.customer.android.core

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.customer.android.core.di.DiGraph
import io.customer.commontest.BaseTest
import java.lang.Thread.sleep
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.Float
import kotlin.Int
import kotlin.String
import kotlin.Unit
import kotlin.concurrent.thread
import kotlin.synchronized
import org.amshove.kluent.internal.assertEquals
import org.amshove.kluent.shouldBeEmpty
import org.amshove.kluent.shouldBeNull
import org.amshove.kluent.shouldBeTrue
import org.amshove.kluent.shouldNotBeNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DiGraphTest : BaseTest() {
    private lateinit var diGraph: DiGraph

    @Before
    fun setUp() {
        diGraph = object : DiGraph() {}
    }

    @Test
    fun newInstance_givenDependencyOverridden_expectOverriddenInstanceRetrieved() {
        val givenInstance = "OverriddenString"
        diGraph.overrideDependency(String::class.java, givenInstance)

        val actualInstance = diGraph.newInstance { "NewInstance" }

        assertEquals(givenInstance, actualInstance)
    }

    @Test
    fun getOrNull_givenDependencyOverridden_expectOverriddenInstanceRetrieved() {
        val givenInstance = "OverriddenString"
        diGraph.overrideDependency(String::class.java, givenInstance)

        val actualInstance = diGraph.getOrNull<String>()

        assertEquals(givenInstance, actualInstance)
    }

    @Test
    fun singletonInstance_givenDependencyOverridden_expectOverriddenInstanceRetrieved() {
        val givenInstance = "OverriddenString"
        diGraph.overrideDependency(String::class.java, givenInstance)

        val actualInstance = diGraph.singleton { "NewInstance" }

        assertEquals(givenInstance, actualInstance)
    }

    @Test
    fun getOrNull_givenDependencySameClassName_expectDifferentInstancesRetrieved() {
        val givenNativePairInstance = "value" to 1
        diGraph.overrideDependency(kotlin.Pair::class.java, givenNativePairInstance)
        val givenCustomPairInstance = Pair(value = 2)
        diGraph.overrideDependency(Pair::class.java, givenCustomPairInstance)

        val actualNativePairInstance = diGraph.getOrNull<kotlin.Pair<String, Int>>()
        val actualCustomPairInstance = diGraph.getOrNull<Pair>()

        assertEquals(givenNativePairInstance, actualNativePairInstance)
        assertEquals(givenCustomPairInstance, actualCustomPairInstance)
    }

    @Test
    fun singletonInstance_givenDependencySameClassName_expectDifferentInstancesRetrieved() {
        val givenNativePairInstance = diGraph.singleton { "value" to 1 }
        val givenCustomPairInstance = diGraph.singleton { Pair(value = 2) }

        val actualNativePairInstance = diGraph.getOrNull<kotlin.Pair<String, Int>>()
        val actualCustomPairInstance = diGraph.getOrNull<Pair>()

        assertEquals(givenNativePairInstance, actualNativePairInstance)
        assertEquals(givenCustomPairInstance, actualCustomPairInstance)
    }

    @Test
    fun singletonInstanceWithOverride_givenDependencySameClassName_expectDifferentInstancesRetrieved() {
        val givenNativePairInstance = "value" to 1
        diGraph.overrideDependency(kotlin.Pair::class.java, givenNativePairInstance)
        val givenCustomPairInstance = Pair(value = 2)
        diGraph.overrideDependency(Pair::class.java, givenCustomPairInstance)

        val actualNativePairInstance = diGraph.singleton { "value" to 3 }
        val actualCustomPairInstance = diGraph.singleton { Pair(value = 4) }

        assertEquals(givenNativePairInstance, actualNativePairInstance)
        assertEquals(givenCustomPairInstance, actualCustomPairInstance)
    }

    @Test
    fun newInstance_givenCreator_expectNewInstanceCreated() {
        val givenInstance = "NewInstance"

        val actualInstance = diGraph.newInstance { givenInstance }

        assertEquals(givenInstance, actualInstance)
    }

    @Test
    fun getOrNull_givenDependencyNotSet_expectNull() {
        val actualInstance = diGraph.getOrNull<String>()

        actualInstance.shouldBeNull()
    }

    @Test
    fun getOrNull_givenSingletonExists_expectSingletonRetrieved() {
        val givenInstance = "SingletonInstance"
        diGraph.singleton { givenInstance }

        val actualInstance = diGraph.getOrNull<String>()

        actualInstance.shouldNotBeNull()
        assertEquals(givenInstance, actualInstance)
    }

    @Test
    fun singleton_givenSingleThreadAccess_expectSingleInstanceInitializedOnce() {
        var timesCalled = 0
        val givenInstance = "SingletonInstance"

        val singleton = diGraph.singleton {
            timesCalled++
            givenInstance
        }
        val retrievedSingleton = diGraph.singleton { "NewSingletonInstance" }

        assertEquals(givenInstance, singleton)
        assertEquals(givenInstance, retrievedSingleton)
        assertEquals(1, timesCalled)
    }

    @Test
    fun singleton_givenConcurrentAccess_expectSingleInstanceInitializedOnce() {
        var initCount = 0
        val threadsCompletionLatch = CountDownLatch(3)
        val instances = mutableListOf<String>()
        val givenInstance = "SingletonInstance"

        val getSingleton: () -> Unit = {
            val instance = diGraph.singleton {
                initCount++
                // Sleep for 100ms to simulate some initialization time so that
                // the other threads can execute in parallel
                sleep(100)
                return@singleton givenInstance
            }
            synchronized(instances) {
                instances.add(instance)
            }
            threadsCompletionLatch.countDown()
        }

        val thread1 = thread(start = false) { getSingleton() }
        val thread2 = thread(start = false) { getSingleton() }
        val thread3 = thread(start = false) { getSingleton() }

        // Start all threads in parallel
        thread1.start()
        thread2.start()
        thread3.start()

        // Wait for threads to complete without any exceptions within the timeout
        // If there is any exception, the latch will not be decremented
        threadsCompletionLatch.await(3, TimeUnit.SECONDS)

        val firstInstance = instances.firstOrNull()
        firstInstance.shouldNotBeNull()
        instances.all { it == firstInstance }.shouldBeTrue()
        assertEquals(givenInstance, firstInstance)
        assertEquals(1, initCount)
    }

    @Test
    fun reset_givenDependenciesSet_expectDependenciesCleared() {
        diGraph.overrideDependency(String::class.java, "OverriddenString")
        diGraph.newInstance<Int> { 1 }
        diGraph.singleton<Float> { 2.0F }

        diGraph.reset()

        diGraph.overrides.shouldBeEmpty()
        diGraph.singletons.shouldBeEmpty()
        diGraph.getOrNull<String>().shouldBeNull()
        diGraph.getOrNull<Int>().shouldBeNull()
        diGraph.getOrNull<Float>().shouldBeNull()
    }
}

private data class Pair(val value: Int)
