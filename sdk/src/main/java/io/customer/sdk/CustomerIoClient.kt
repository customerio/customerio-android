package io.customer.sdk

import io.customer.base.comunication.Action
import io.customer.base.data.Result
import io.customer.base.data.Success
import io.customer.sdk.api.CustomerIoApi
import io.customer.sdk.data.model.IdentityAttributeMap
import io.customer.sdk.repository.IdentityRepository
import io.customer.sdk.repository.PreferenceRepository
import io.customer.sdk.repository.TrackingRepository

/**
 * CustomerIoClient is client class to hold all repositories and act as a bridge between
 * repositories and `CustomerIo` class
 */
internal class CustomerIoClient(
    private val identityRepository: IdentityRepository,
    private val preferenceRepository: PreferenceRepository,
    private val trackingRepository: TrackingRepository,
) : CustomerIoApi {

    override fun identify(identifier: String, attributes: IdentityAttributeMap): Action<Unit> {
        return object : Action<Unit> {
            val action by lazy { identityRepository.identify(identifier, attributes) }
            override fun execute(): Result<Unit> {
                val result = action.execute()
                if (result is Success) {
                    preferenceRepository.saveIdentifier(identifier = identifier)
                }
                return result
            }

            override fun enqueue(callback: Action.Callback<Unit>) {
                action.enqueue {
                    if (it is Success) {
                        preferenceRepository.saveIdentifier(identifier = identifier)
                    }
                    callback.onResult(it)
                }
            }

            override fun cancel() {
                action.cancel()
            }
        }
    }

    override fun track(name: String, attributes: Map<String, Any>): Action<Unit> {
        val identifier = preferenceRepository.getIdentifier()
        return trackingRepository.track(identifier, name, attributes)
    }
}
