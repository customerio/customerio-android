---
covers: ['core/**', 'base/**', '*/di/*', '*EventBus*']
---
# Architecture

### Setup
- Apps configure the SDK with `CustomerIOBuilder` (`datapipelines/src/main/kotlin/io/customer/sdk/CustomerIOBuilder.kt`) and add modules with `addCustomerIOModule`
- A real setup: `samples/java_layout/src/main/java/io/customer/android/sample/java_layout/sdk/CustomerIORepository.java`

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
