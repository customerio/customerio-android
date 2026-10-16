# AGENTS.md

This file provides guidance to AI coding agents when working with code in this repository.

## Agent context layer (`.agents/`)
- Before changing behavior, run `.agents/tool/bin/tether context <path-or-module>`: the spec, invariants, guides and checks for that area.
- Commit, push and PR rules are enforced by hooks; `.agents/tool/bin/tether rules` lists them (`tether check <action>` if your agent has no hooks).
- Changing behavior a spec covers: update the spec in the same change, or add a `Spec-Reviewed: <spec-id>` trailer if behavior is unchanged.
- Never edit `.agents/generated/`; run `.agents/tool/bin/tether generate`.
- Topic guides (architecture, testing, Kotlin practices, sample apps, release) live in `.agents/knowledge/guides/`; `tether context <path>` shows the ones for the code you touch.

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
- Install local Maven artifacts: `./gradlew publishToMavenLocal`

## Code Style
- Package naming: `io.customer.sdk.*` for public APIs, `io.customer.MODULE.*` for module-specific code
- Always use dependency injection via `SDKComponent` (core) and module DI extensions (e.g. `messagingpush/.../di/DiGraphMessagingPush.kt`)
- Document public APIs with KDoc comments

## Prohibited Actions
- DO NOT modify any generated code or `.api` files manually
- DO NOT expose internal modules or APIs to SDK users
- DO NOT modify root `build.gradle` or module `build.gradle` files unless specifically asked
- DO NOT commit configuration files with actual credentials
- DO NOT include files with credentials in generated code
- DO NOT use Android features unavailable in API 21+ unless compatibility checks are included
- DO NOT bypass the DI system with direct instantiation of internal classes

## Platform
- Min SDK 21, compile/target SDK 36, Kotlin 2.1.21, Compose BOM 2025.10.00. Check new dependencies against `buildSrc/src/main/kotlin/io.customer/android/Versions.kt`.

## Workflow
- Feature branches off `main`, a pull request for every change, conventional commit messages.
- `lefthook install` once; run `make format` then `make lint` before committing; add or update tests with the change.

## Project Structure
- `buildSrc/` - Centralized dependency and version management
- `core/` - Central SDK infrastructure (DI, EventBus, shared utilities)
- `base/` - Base classes and shared functionality
- `datapipelines/` - Primary tracking and analytics functionality
- `messagingpush/` - Push notification handling (FCM)
- `messaginginapp/` - In-app messaging functionality
- `messaginginapp-compose/` - Jetpack Compose support for in-app messaging
- `messaginginbox/` - Inbox messaging
- `location/` - Location tracking tied to app foreground/background
- `geofence/` - Geofence monitoring and transition events
- `tracking-migration/` - Legacy API migration utilities
- `common-test/` - Shared testing infrastructure and utilities
- `samples/` - Example applications demonstrating SDK usage
- `docs/` - Developer documentation and guides
- `scripts/` - Build and deployment automation scripts
