# AGENTS.md

This file provides guidance to AI coding agents when working with code in this repository.

# Customer.io Android SDK - Developer Guide

After completing planned changes to the code, ALWAYS build the code to make sure it's working, before continuing to the next step.
After making changes to Unit Tests, ALWAYS test the changed test classes. Avoid testing the whole module or the whole SDK, unless absolutely necessary.

## Commands
- Build all modules: `./gradlew assembleDebug`
- Build single module: `./gradlew :MODULE_NAME:assembleDebug` (e.g., `./gradlew :core:assembleDebug`)
- Build release: `./gradlew assembleRelease`
- Test all modules: `./gradlew test`
- Test single module: `./gradlew :MODULE_NAME:test` (e.g., `./gradlew :core:test`)
- Test single class: `./gradlew :MODULE_NAME:test --tests "ClassName"`
- Instrumentation tests: `./gradlew connectedAndroidTest`
- Lint: run both `make lint` (ktlint) and `./gradlew lintDebug` (Android lint). CI runs them as separate jobs in `.github/workflows/lint.yml`.
- Format: `make format` (run before lint)
- Install local Maven artifacts: `./gradlew publishToMavenLocal`
- Regenerate `.api` files: `make generate-public-api`

## Code Style
- Kotlin with object-oriented and functional programming patterns
- Package naming: `io.customer.sdk.*` for public APIs, `io.customer.MODULE.*` for module-specific code
- Always use dependency injection through the `DiGraph` types in `core/src/main/kotlin/io/customer/sdk/core/di/` (`SDKComponent`, `AndroidSDKComponent`)
- Modular architecture (Core, DataPipelines, MessagingPush, MessagingInApp, etc.)
- Document public APIs with KDoc comments
- Error handling: prefer sealed classes for typed errors, use Result types when appropriate
- Avoid non-null assertions (!!) except in tests where values are guaranteed
- Use explicit null checks and safe calls (?.) for nullable types

## Prohibited Actions
- DO NOT modify any generated code or `.api` files manually
- DO NOT expose internal modules or APIs to SDK users
- DO NOT modify root `build.gradle` or module `build.gradle` files unless specifically asked
- DO NOT commit configuration files with actual credentials
- DO NOT include files with credentials in generated code
- DO NOT use Android features unavailable at the minimum SDK (`MIN_SDK` in `buildSrc/src/main/kotlin/io.customer/android/Versions.kt`) unless compatibility checks are included
- DO NOT bypass the DI system with direct instantiation of internal classes

## Android-Specific Considerations
- Minimum, compile, and target SDK levels and library versions (Kotlin, Compose) are pinned in `buildSrc/src/main/kotlin/io.customer/android/Versions.kt`. When adding new dependencies, verify Kotlin compatibility there.
- Use AndroidX libraries instead of deprecated support libraries

## Threading and Concurrency
- Use Kotlin coroutines for asynchronous operations
- Prefer structured concurrency over raw threads
- Always specify appropriate Dispatchers (Main, IO, Default)

## Testing Strategy
- Write both JVM unit tests and Android instrumentation tests
- Use `UnitTest` base class for standard unit tests
- Use `AndroidTest` base class for instrumentation tests
- Use `RobolectricTest` for Android framework testing without device
- Mock dependencies using MockK framework
- Use test doubles and stubs from `common-test` module
- Follow AAA pattern (Arrange, Act, Assert)
- Name tests with `methodName_givenCondition_expectResult` format

## Committing and Git Workflow
- Use `lefthook` for git hooks (install with `lefthook install`)
- Run `make format` before committing
- Run `make lint` to ensure code quality
- Follow conventional commit messages
- Use feature branches off `main` branch
- Create pull requests for all changes
- Ensure CI passes before merging
- Setup and workflow details: `docs/dev-notes/DEVELOPMENT.md` and `docs/dev-notes/GIT-WORKFLOW.md`

## Project Structure
- `buildSrc/` - Centralized dependency and version management
- `core/` - Central SDK infrastructure (DI, EventBus, shared utilities)
- `base/` - Base classes and shared functionality
- `datapipelines/` - Primary tracking and analytics functionality
- `messagingpush/` - Push notification handling (FCM)
- `messaginginapp/` - In-app messaging functionality
- `messaginginapp-compose/` - Jetpack Compose support for in-app messaging
- `messaginginbox/` - Message inbox module
- `location/` - Location module
- `geofence/` - Geofence module
- `tracking-migration/` - Legacy API migration utilities
- `common-test/` - Shared testing infrastructure and utilities
- `samples/` - Example applications demonstrating SDK usage
- `docs/` - Developer documentation and guides
- `scripts/` - Build and deployment automation scripts

## Architecture Overview

### Module Initialization Pattern
```kotlin
// Configure the SDK with builder pattern
CustomerIOBuilder(application, <CDP_API_KEY>).apply {
    // If you're in the EU, set Region.EU. Default is Region.US and optional.
    region(Region.US)

    // Optional: Enable in-app messaging by adding siteId and Region
    addCustomerIOModule(
        ModuleMessagingInApp(
            MessagingInAppModuleConfig.Builder(<SITE_ID>).build()
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
- `SDKComponent` serves as the central dependency registry
- Test code can override dependencies for isolation
- Avoid static dependencies and global state

### Inter-Module Communication
- Event-driven architecture using `EventBus`
- Type-safe event publishing and subscription
- Events defined as sealed classes for type safety
- Modules communicate without direct dependencies
- Key events include profile changes, device registration, etc.

## Testing

### Test Categories
- **Unit Tests**: Test individual components in isolation using JVM
- **Integration Tests**: Test module interactions and SDK behavior
- **Android Tests**: Test Android-specific functionality on device/emulator
- **Robolectric Tests**: Test Android components in JVM environment

## Sample Applications

#### Java/XML Layout Sample (`samples/java_layout/`)
- Traditional Android Views with Java
- Demonstrates basic SDK integration patterns
- Shows push notification setup
- Includes various tracking scenarios
- Build and install: `./gradlew :samples:java_layout:installTokenDebug` (`installFidDebug` registers with the Firebase Installation ID)

#### Kotlin Compose Sample (`samples/kotlin_compose/`)
- Modern UI with Jetpack Compose and Kotlin
- Demonstrates advanced SDK features
- Shows in-app messaging with Compose integration
- Includes comprehensive SDK configuration examples
- Build and install: `./gradlew :samples:kotlin_compose:installDebug`

#### Sample App Configuration
- Both apps require `google-services.json` for Firebase integration
- Set test credentials (`cdpApiKey`, `siteId`) in the gitignored `samples/local.properties`. The sample build reads them into `BuildConfig` (`samples/sample-app.gradle`).
- Sample apps depend on the SDK modules in this repo by default. To build against a Maven artifact instead, set `sdkVersion` in `samples/local.properties` or pass `-PcioSDKVersion=<version>`. For a local artifact, run `./gradlew publishToMavenLocal` first.
- View sample app logs: `adb logcat -s CustomerIO`
- Use sample apps to test new features before writing unit tests
- Sample apps serve as integration tests for the SDK

## Release and Publishing
- Artifacts published to Maven Central
- Version management via semantic versioning
- Alpha/Beta/Production release channels
- Automated publishing via GitHub Actions
- GPG signing required for Maven artifacts
- Binary compatibility validation enforced
