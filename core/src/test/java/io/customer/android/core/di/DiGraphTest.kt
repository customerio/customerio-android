package io.customer.android.core.di

import java.lang.Thread.sleep
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.String
import kotlin.concurrent.thread
import org.amshove.kluent.internal.assertEquals
import org.amshove.kluent.shouldBeEmpty
import org.amshove.kluent.shouldBeNull
import org.amshove.kluent.shouldBeTrue
import org.amshove.kluent.shouldNotBeNull
import org.junit.After
import org.junit.Before
import org.junit.Test

class DiGraphTest {
    private lateinit var diGraph: DiGraph

    @Before
    fun setUp() {
        diGraph = object : DiGraph() {}
    }

    @After
    fun tearDown() {
        diGraph.reset()
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
    fun getOrNull_givenSingletonSet_expectDependencyRetrieved() {
        val givenInstance = "SingletonInstance"
        diGraph.singleton { givenInstance }

        val actualInstance = diGraph.getOrNull<String>()

        actualInstance.shouldNotBeNull()
        assertEquals(givenInstance, actualInstance)
    }

    @Test
    fun registerDependency_givenDependencySet_expectDependencyRetrieved() {
        val givenInstance = "RegisteredInstance"
        diGraph.registerDependency { givenInstance }

        val actualInstance = diGraph.getOrNull<String>()

        actualInstance.shouldNotBeNull()
        assertEquals(givenInstance, actualInstance)
    }

    @Test
    fun registerDependency_givenDependencySetMultipleTimes_expectDependencyInitializedOnce() {
        var timesCalled = 0
        val givenInstance = "RegisteredInstance"

        val singleton = diGraph.registerDependency {
            timesCalled++
            givenInstance
        }
        val actualInstance = diGraph.registerDependency { "NewRegisteredInstance" }

        assertEquals(givenInstance, singleton)
        assertEquals(givenInstance, actualInstance)
        assertEquals(1, timesCalled)
    }

    @Test
    fun singleton_givenSingleThreadAccess_expectSingleInstanceInitializedOnce() {
        var timesCalled = 0
        val givenInstance = "SingletonInstance"

        val singleton = diGraph.singleton {
            timesCalled++
            givenInstance
        }
        val actualInstance = diGraph.singleton { "NewSingletonInstance" }

        assertEquals(givenInstance, singleton)
        assertEquals(givenInstance, actualInstance)
        assertEquals(1, timesCalled)
    }

    @Test
    fun singleton_givenConcurrentAccess_expectSingleInstanceInitializedOnce() {
        var initCount = 0
        val threadsCompletionLatch = CountDownLatch(3)
        val instances = mutableListOf<String>()
        val givenInstanceThread1 = "SingletonInstance1"
        val givenInstanceThread2 = "SingletonInstance2"
        val givenInstanceThread3 = "SingletonInstance3"

        val getSingleton: (String, Long) -> Unit = { returnValue: String, wait: Long ->
            val instance = diGraph.singleton {
                initCount++
                // Sleep for 100ms to simulate some initialization time so that
                // the other threads can execute in parallel
                sleep(wait)
                return@singleton returnValue
            }
            synchronized(instances) {
                instances.add(instance)
            }
            threadsCompletionLatch.countDown()
        }

        val thread1 = thread(start = false) {
            // Wait less time for the first thread so we get the instance from
            // this thread consistently
            getSingleton(givenInstanceThread1, 100)
        }
        val thread2 = thread(start = false) {
            getSingleton(givenInstanceThread2, 500)
        }
        val thread3 = thread(start = false) {
            getSingleton(givenInstanceThread3, 500)
        }

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
        assertEquals(givenInstanceThread1, firstInstance)
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

// Custom data class to test dependency injection with the same class name.
private data class Pair(val value: Int)
