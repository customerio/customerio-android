package io.customer.messaginginapp.domain

import io.customer.sdk.core.util.Logger
import org.reduxkotlin.Dispatcher
import org.reduxkotlin.Middleware
import org.reduxkotlin.Store
import org.reduxkotlin.applyMiddleware
import org.reduxkotlin.createStore

fun createInAppMessagingStore(logger: Logger): Store<InAppMessagingState> {
    return createStore(
        reducer = inAppMessagingReducer,
        preloadedState = InAppMessagingState(),
        enhancer = applyMiddleware(loggerMiddleware(logger))
    )
}

fun loggerMiddleware(logger: Logger): Middleware<InAppMessagingState> = { store ->
    { next: Dispatcher ->
        { action: Any ->
//            if (action is InAppMessagingAction.LogEvent) {
//                logger.info("WOW Event: ${action.event}")
//            }
            logger.info("WOW Event: $action")
            logger.debug("WOW State: ${store.state}")
            next(action)
        }
    }
}
