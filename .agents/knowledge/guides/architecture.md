---
covers: ['core/**', 'base/**', '*/di/*', '*EventBus*']
---
# Architecture

### Module Initialization Pattern
```kotlin
// Configure the SDK with builder pattern
CustomerIOBuilder(application, <CDP_API_KEY>).apply {
    // If you're in the EU, set Region.EU. Default is Region.US and optional.
    region(Region.US)

    // Optional: Enable in-app messaging by adding siteId and Region
    addCustomerIOModule(
        ModuleMessagingInApp(
            // If you're in the EU, set Region.EU
            MessagingInAppModuleConfig.Builder(<SITE_ID>, Region.US).build()
    )
    )

    // Optional: Enable support for push notifications
    addCustomerIOModule(ModuleMessagingPushFCM())

    // Completes setup and initializes the SDK
    build()
}

// Use the SDK
CustomerIO.instance().identify("user-id")
CustomerIO.instance().track("event-name")
```

### Dependency Injection
- All modules use constructor-based dependency injection
- `SDKComponent` (`core/src/main/kotlin/io/customer/sdk/core/di/SDKComponent.kt`) is the central dependency registry
- Modules add dependencies as extension properties on `SDKComponent` / `AndroidSDKComponent` (see each module's `di/` package)
- Test code can override dependencies for isolation
- Avoid static dependencies and global state

### Inter-Module Communication
- Event-driven architecture using `EventBus`
- Type-safe event publishing and subscription
- Events defined as sealed classes for type safety
- Modules communicate without direct dependencies
- Key events include profile changes, device registration, etc.
