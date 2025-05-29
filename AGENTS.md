# AGENTS.md

This file provides guidance to AI coding agents when working with code in this repository.

# Customer.io Android SDK - Developer Guide

After completing planned changes to the code, ALWAYS build the code to make sure it's working, before continuing to the next step.
After making changes to Unit Tests, ALWAYS test the changed test classes. Avoid testing the whole module or the whole SDK, unless absolutely necessary.

## Commands
- Build all modules: `./gradlew assembleDebug`
- Build single module: `./gradlew :MODULE_NAME:assembleDebug` (e.g., `./gradlew :core:assembleDebug`)
- Test single module: `./gradlew :MODULE_NAME:test` (e.g., `./gradlew :core:test`)
- Test single class: `./gradlew :MODULE_NAME:test --tests "ClassName"`
- Lint: `make lint` or `./gradlew lintDebug`
- Format: `make format` (run before lint)
- Install local Maven artifacts: `make install-local-sdk`

## Code Style
- **Kotlin 1.8.21** with object-oriented and functional programming patterns
- Naming: PascalCase for classes, camelCase for properties/methods/variables
- Package naming: `io.customer.sdk.*` for public APIs, `io.customer.MODULE.*` for module-specific code
- Always use dependency injection via `DIGraphShared` and module-specific DI graphs
- Modular architecture (Core, DataPipelines, MessagingPush, MessagingInApp, etc.)
- Document public APIs with KDoc comments
- Error handling: prefer sealed classes for typed errors, use Result types when appropriate
- Avoid non-null assertions (!!) except in tests where values are guaranteed
- Use explicit null checks and safe calls (?.) for nullable types
- Keep classes small and with single responsibility
- Keep methods focused and well-named

## Prohibited Actions
- DO NOT modify any generated code or `.api` files manually
- DO NOT expose internal modules or APIs to SDK users
- DO NOT modify root `build.gradle` or module `build.gradle` files unless specifically asked
- DO NOT commit configuration files with actual credentials
- DO NOT include files with credentials in generated code
- DO NOT use Android features unavailable in API 21+ unless compatibility checks are included
- DO NOT bypass the DI system with direct instantiation of internal classes

## Android-Specific Considerations
- **Minimum SDK**: API 21 (Android 5.0)
- **Target SDK**: API 33+ (follow current Android recommendations)
- **Kotlin Version**: 1.8.21 - when adding new dependencies, ensure they're compatible with this Kotlin version
- **Compose BOM**: 2023.03.00 (compatible with compileSdk 33)
- Always check API level compatibility when using newer Android features
- Use AndroidX libraries instead of deprecated support libraries
- Handle Android lifecycle properly (Activities, Fragments, Services)
- Consider memory implications of long-running operations
- Use appropriate threading (Main thread for UI, background for network/disk)
- Follow Android security best practices (no hardcoded secrets, proper permissions)
- When adding new dependencies, verify Kotlin compatibility in `buildSrc/src/main/kotlin/io.customer/android/Versions.kt`

## Memory and Performance
- Use appropriate collection types (List vs Array, mutable vs immutable)
- Avoid memory leaks by properly managing listeners and callbacks
- Use weak references for callbacks that reference Activities/Contexts
- Consider object pooling for frequently created objects
- Profile memory usage during development
- Use ProGuard/R8 rules appropriately for release builds
- Monitor SDK binary size impact

## Threading and Concurrency
- Use Kotlin coroutines for asynchronous operations
- Prefer structured concurrency over raw threads
- Always specify appropriate Dispatchers (Main, IO, Default)
- Handle cancellation properly in coroutines
- Use thread-safe data structures when sharing state
- Document threading requirements in public APIs
- Test concurrent code thoroughly

## Testing Strategy
- Write both JVM unit tests and Android instrumentation tests
- Use `UnitTest` base class for standard unit tests
- Use `AndroidTest` base class for instrumentation tests
- Use `RobolectricTest` for Android framework testing without device
- Mock dependencies using MockK framework
- Use test doubles and stubs from `common-test` module
- Follow AAA pattern (Arrange, Act, Assert)
- Name tests with `testMethodName_whenCondition_thenExpectedResult` format

## Committing and Git Workflow
- Use `lefthook` for git hooks (install with `lefthook install`)
- Run `make format` before committing
- Run `make lint` to ensure code quality
- Follow conventional commit messages
- Use feature branches off `main` branch
- Create pull requests for all changes
- Ensure CI passes before merging

## Project Structure
- `buildSrc/` - Centralized dependency and version management
- `core/` - Central SDK infrastructure (DI, EventBus, shared utilities)
- `base/` - Base classes and shared functionality
- `datapipelines/` - Primary tracking and analytics functionality
- `messagingpush/` - Push notification handling (FCM)
- `messaginginapp/` - In-app messaging functionality
- `messaginginapp-compose/` - Jetpack Compose support for in-app messaging
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
- `DIGraphShared` serves as the central dependency registry
- Module-specific DI graphs extend the shared graph
- Test code can override dependencies for isolation
- Avoid static dependencies and global state

### Inter-Module Communication
- Event-driven architecture using `EventBus`
- Type-safe event publishing and subscription
- Events defined as sealed classes for type safety
- Modules communicate without direct dependencies
- Key events include profile changes, device registration, etc.

## Building

### Building the Entire SDK
```bash
./gradlew assembleDebug
```

### Building a Single Module
```bash
./gradlew :core:assembleDebug
./gradlew :datapipelines:assembleDebug
```

### Building for Release
```bash
./gradlew assembleRelease
```

## Testing

### Running All Tests
```bash
./gradlew test
```

### Running Tests for a Specific Module
```bash
./gradlew :core:test
./gradlew :datapipelines:test
```

### Running a Specific Test Class
```bash
./gradlew :core:test --tests "EventBusTest"
```

### Running Android Instrumentation Tests
```bash
./gradlew connectedAndroidTest
```

### Test Categories
- **Unit Tests**: Test individual components in isolation using JVM
- **Integration Tests**: Test module interactions and SDK behavior
- **Android Tests**: Test Android-specific functionality on device/emulator
- **Robolectric Tests**: Test Android components in JVM environment

## Development Workflow

### Setting up Local Development
1. Clone the repository
2. Install lefthook: `lefthook install`
3. Run initial build: `./gradlew assembleDebug`
4. Run tests: `./gradlew test`

### Making Changes
1. Create feature branch from `develop`
2. Implement changes following code style guidelines
3. Add/update tests for new functionality
4. Run `make format` and `make lint`
5. Build and test locally
6. Commit with descriptive message
7. Create pull request

### Sample Applications

The SDK includes two sample applications for testing and demonstration:

#### Java/XML Layout Sample (`samples/java_layout/`)
- Traditional Android Views with Java
- Demonstrates basic SDK integration patterns
- Shows push notification setup
- Includes various tracking scenarios
- Build and install: `cd samples/java_layout && ./gradlew installDebug`

#### Kotlin Compose Sample (`samples/kotlin_compose/`)
- Modern UI with Jetpack Compose and Kotlin
- Demonstrates advanced SDK features
- Shows in-app messaging with Compose integration
- Includes comprehensive SDK configuration examples
- Build and install: `cd samples/kotlin_compose && ./gradlew installDebug`

#### Using Sample Apps for Development
```bash
# Install local SDK artifacts first
make install-local-sdk

# Run Java sample app
cd samples/java_layout
./gradlew installDebug

# Run Kotlin Compose sample app  
cd samples/kotlin_compose
./gradlew installDebug

# View logs from sample apps
adb logcat -s CustomerIO
```

#### Sample App Configuration
- Both apps require `google-services.json` for Firebase integration
- Test credentials should be configured in the app's initialization code
- Sample apps automatically use local SDK artifacts when `make install-local-sdk` is run
- Use sample apps to test new features before writing unit tests
- Sample apps serve as integration tests for the SDK

## Debugging and Troubleshooting
- Enable verbose logging in debug builds
- Use Android Studio debugger for step-through debugging
- Check logcat for SDK-specific log messages
- Use `adb` commands for device debugging
- Monitor network traffic for API calls
- Use memory profiler for performance analysis

## Release and Publishing
- Artifacts published to Maven Central
- Version management via semantic versioning
- Alpha/Beta/Production release channels
- Automated publishing via GitHub Actions
- GPG signing required for Maven artifacts
- Binary compatibility validation enforced