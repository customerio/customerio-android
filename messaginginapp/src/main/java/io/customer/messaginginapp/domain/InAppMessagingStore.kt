package io.customer.messaginginapp.domain

import org.reduxkotlin.Store
import org.reduxkotlin.applyMiddleware
import org.reduxkotlin.threadsafe.createThreadSafeStore

internal object InAppMessagingStore {

    val store: Store<InAppMessagingState> = createThreadSafeStore(
        reducer = inAppMessagingReducer,
        preloadedState = InAppMessagingState(),
        applyMiddleware(
            loggerMiddleware(),
            // needs to be first middleware to ensure that the user is set before processing any other actions
            userChangeMiddleware(),
            routeChangeMiddleware(),
            modalMessageMiddleware(),
            dismissMessageMiddleware(),
            processMessages(),
            errorLoggerMiddleware()
        )
    )
}
