# Hooks 

Have you encountered a scenario where the Tracking SDK needs to call code that exists in the Messaging Push SDK? If so, this document is for you. 

The `messagingpush` module has the `sdk` module as a dependency. However, you *can't* have the `sdk` list the `messagingpush` module as a dependency. This is because (1) it will cause a circular dependency and (2) we want `messagingpush` to be an optional SDK to install and if the `sdk` module has the `messagingpush` module as a dependency, all customers that use `sdk` will no longer have `messagingpush` as optional. 

Instead you need to use a technique we call *hooks*. Hooks are a way for the optional SDKs of this project to communicate together at runtime. 

## How hooks work

Hooks are like [an event bus](https://github.com/greenrobot/EventBus) if you have ever used one of those before. There is code that sends events to listeners. They have internally been called "hooks" because of our use case of optional SDKs being able to "hook" themselves together to other SDKs at runtime so they can communicate. 

Let's take the example of the optional FCM push SDK (the `messagingpush` module). A customer can choose to install this SDK or not. If a customer decides to use this SDK, they will initialize it in their app:

```kotlin
CustomerIO.Builder().build()
MessagingPush.instance() // initialize after initializing the CustomerIO (tracking) SDK. 
```

When `MessagingPush` class is initialized, it gets the singleton instance of `HooksManager` and adds the `MessagingPush` module as a provider. If the optional FCM push SDK is never installed or initialized, the SDK will never register itself as a provider. 

Now, a hook provider (see `ModuleHookProvider`, example `MessagingPushModuleHookProvider`) is a simple class that returns instances of classes that are listeners of different events. 

When a SDK decides they need to send an event to all hook listeners, they ask the singleton `HooksManager` for all hooks of a certain type and call those hooks:

```kotlin
// a new profile has been identified in the Tracking SDK
val newlyIdentifiedProfileIdentifier = "xyz"

val hooks: HooksManager = ...
hooks.profileIdentifiedHooks.forEach { 
    it.profileIdentified(newlyIdentifiedProfileIdentifier)
}
```

If the FCM push SDK cares about when a new profile has been identified in the SDK, it can return a class in `MessagingPushModuleHookProvider` that listens to the `ProfileIdentifiedHook` events.

