# Getting Started

The Customer.io Android SDK helps you identify, track, and send messages to your app users.

## Key Classes

### Initialization

- `CustomerIOBuilder` - Configure and initialize the SDK
- `CustomerIO` - Main SDK interface for tracking and identification

### Configuration

- `DataPipelinesModuleConfig` - Configure the Data Pipelines module
- `MessagingInAppModuleConfig` - Configure in-app messaging
- `MessagingPushModuleConfig` - Configure push notifications

### Tracking

- `CustomerIO.identify()` - Identify customer profiles
- `CustomerIO.track()` - Track custom events
- `CustomerIO.screen()` - Track screen views

## Module Overview

### Core Module
Provides the foundation for the SDK including dependency injection, event bus, and shared utilities.

### Data Pipelines Module
The primary module for customer data collection and event tracking. Integrates with Customer.io's CDP.

### Messaging Push Module
Handles push notifications through Firebase Cloud Messaging (FCM).

### Messaging In-App Module
Displays in-app messages to users based on campaigns configured in Customer.io.

## Common Use Cases

See the [Quick Start](https://docs.customer.io/integrations/sdk/android/) for more information about the SDK and practical examples.

### Initialize the SDK
```kotlin
CustomerIOBuilder(context, "YOUR_CDP_API_KEY")
    .region(Region.US)
    .addCustomerIOModule(ModuleMessagingPushFCM())
    .build()
```

### Identify a Customer
```kotlin
CustomerIO.instance().identify(
    identifier = "user@example.com",
    traits = mapOf("firstName" to "John", "plan" to "premium")
)
```

### Track Events
```kotlin
CustomerIO.instance().track(
    name = "Product Viewed",
    attributes = mapOf("productId" to "123", "price" to 29.99)
)
```

