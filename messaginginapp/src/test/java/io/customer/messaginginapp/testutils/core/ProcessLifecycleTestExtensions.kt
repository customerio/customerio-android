package io.customer.messaginginapp.testutils.core

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import io.customer.commontest.config.TestConfig
import io.customer.commontest.config.testConfigurationDefault
import io.mockk.every
import io.mockk.mockk

/** A process lifecycle that is in [state], for tests that need a foregrounded or backgrounded app. */
fun processLifecycleOwnerIn(state: Lifecycle.State): LifecycleOwner {
    val lifecycle = mockk<Lifecycle>(relaxed = true)
    every { lifecycle.currentState } returns state
    val owner = mockk<LifecycleOwner>(relaxed = true)
    every { owner.lifecycle } returns lifecycle
    return owner
}

/** Test configuration that makes the SDK see a foregrounded app (a started UI). */
fun foregroundedProcessConfig(): TestConfig = testConfigurationDefault {
    diGraph {
        sdk {
            overrideDependency<LifecycleOwner>(processLifecycleOwnerIn(Lifecycle.State.STARTED))
        }
    }
}
