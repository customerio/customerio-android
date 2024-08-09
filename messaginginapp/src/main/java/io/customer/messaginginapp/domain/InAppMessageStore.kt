package io.customer.messaginginapp.domain

import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.core.util.Logger
import org.reduxkotlin.Dispatcher
import org.reduxkotlin.Middleware
import org.reduxkotlin.Store
import org.reduxkotlin.applyMiddleware
import org.reduxkotlin.threadsafe.createThreadSafeStore

internal object InAppMessagingStore {
    val store: Store<InAppMessagingState> = createThreadSafeStore(
        reducer = inAppMessagingReducer,
        preloadedState = InAppMessagingState(),
        applyMiddleware(loggerMiddleware(SDKComponent.logger))
    )
}

fun loggerMiddleware(logger: Logger): Middleware<InAppMessagingState> = { store ->
    { next: Dispatcher ->
        { action: Any ->
            if (action is InAppMessagingAction.LogEvent) {
                logger.info(action.event)
            }
            logger.debug("state: ${store.state}")
            next(action)
        }
    }
}
