# AGENTS.md

Guidance for AI coding agents and new contributors working in this repository (the Customer.io Android SDK). This is a public repository: never commit credentials, API keys, tokens, or customer data, in code, config, tests, or docs.

## Golden rules

- After completing planned code changes, ALWAYS build the affected module before moving on.
- After changing unit tests, run the changed test classes. Avoid running the whole module or SDK suite unless necessary.
- Never hand-edit generated code or `.api` files. If the public API changed intentionally, regenerate dumps with `./gradlew apiDump`.
- Never bump pinned dependency or tool versions as a drive-by (see Dependency policy).
- Do not modify root or module `build.gradle` files unless the task requires it.
- Avoid non-null assertions (`!!`) outside tests.

## Commands

- Build all modules: `./gradlew assembleDebug`
- Build one module: `./gradlew :core:assembleDebug`
- Unit test one module: `./gradlew :core:test`
- Unit test one class: `./gradlew :core:test --tests "ClassNameTest"`
- Format Kotlin: `make format` (ktlint autoformat)
- Lint Kotlin: `make lint` (installs pinned ktlint, formats, then reports remaining errors)
- Android Lint, per module (what CI runs): `./gradlew :MODULE:lintDebug`
- Regenerate public API dumps: `./gradlew apiDump` (alias: `make generate-public-api`)
- Validate public API: `make validate-public-api`
- Publish SDK artifacts to local Maven for sample/integration testing: `./gradlew publishToMavenLocal`

## Toolchain

- Kotlin 2.1.21, AGP 8.9.3, Gradle 8.14, Java 17 (zulu on CI)
- compileSdk 36, targetSdk 36, minSdk 21
- Compose BOM 2025.10.00 (messaginginapp-compose module)
- All versions live in `buildSrc/src/main/kotlin/io.customer/android/Versions.kt`

## CI: what gates your PR and how to fix it

Every check below runs on pull requests. Know them before you push.

1. **PR title lint.** The PR title must be a conventional commit message, for example `feat(inbox): ...`, `fix: ...`, `ci: ...`, `chore(geofence): ...`. The merged PR title drives semantic-release: `feat` = minor, `fix` = patch, breaking change (`!` or BREAKING CHANGE footer) = major; `ci`, `chore`, `docs`, `test`, `refactor` produce no release.
2. **Kotlin Lint.** Runs the pinned ktlint (0.48.2, installed by `scripts/get-ktlint.sh`) in check mode. Fix locally with `make format`, then `make lint`. The version is pinned deliberately: new ktlint releases are not backward compatible. Do not upgrade it as part of an unrelated change.
3. **Android Lint.** Runs `:MODULE:lintDebug` for core, datapipelines, messagingpush, messaginginapp, and tracking-migration.
4. **API check (binary compatibility).** Runs `./gradlew apiCheck` against the committed `.api` dumps. If you intentionally changed the public API, run `./gradlew apiDump` and commit the result. Unintentional diffs mean you broke binary compatibility, fix the code instead.
5. **Unit tests.** Per-module matrix (messagingpush, messaginginapp, base, datapipelines, core, tracking-migration) running `runJacocoTestReport`, with coverage uploaded to Codecov. The upload is configured to fail the job on error, so a failed upload can fail your PR even when tests pass. Rerun the job if the failure is in the upload step, not the tests.
6. **Instrumentation tests.** Runs `connectedDebugAndroidTest` for both sample apps (kotlin_compose, java_layout) on an API 31 x86_64 emulator with a 45-minute timeout.
7. **Snapshot publish.** Every PR publishes `<branch-name>-SNAPSHOT` artifacts to the Maven Central snapshots repository and comments the version on the PR, so branch names must be valid in a Maven version string. Requires repository signing secrets, so this job cannot succeed on PRs from forks.
8. **SDK binary size report.** Builds base and head branches, compares SDK size, and comments the diff on the PR. Large unexplained size increases will draw review scrutiny.

Gotcha: the `location` module currently has unit tests but is not in the Android Lint or unit-test CI matrices. If you touch `location/`, run `./gradlew :location:test` and `./gradlew :location:lintDebug` locally; CI will not do it for you.

## Dependency policy

- All dependency versions are declared in `buildSrc/src/main/kotlin/io.customer/android/Versions.kt`.
- The Segment analytics-kotlin dependency is strictly pinned (`!!`). This is deliberate, for binary compatibility with customer apps. Do not bump it casually; treat any change to it as a reviewed, standalone task.
- minSdk 21 is a customer-facing support commitment. Some dependencies are intentionally held at versions that still support it. Check a dependency's minSdk before bumping.
- When updating the AGP version, also update the gradle compatibility workflow (see the comment in Versions.kt).

## Code style

- Kotlin, PascalCase classes, camelCase members.
- Packages: `io.customer.sdk.*` for public APIs, `io.customer.MODULE.*` for module code.
- Dependency injection everywhere: `DIGraphShared` plus module-specific DI graphs. Do not instantiate internal classes directly.
- Prefer sealed classes for typed errors; use safe calls for nullables.
- KDoc on public APIs. Do not expose internal modules or APIs to SDK users.
- Kotlin coroutines for async work; specify dispatchers explicitly; use thread-safe structures for shared state.

## Testing

- Base classes in `common-test`: `UnitTest` (JVM), `JUnit5Test`, `RobolectricTest` (Android framework on JVM), `AndroidTest` (instrumentation).
- The suite is a JUnit 4 and JUnit 5 mix (JUnit BOM 5.9.3). Match the framework the surrounding test class already uses.
- Mocking with MockK. Robolectric for Android framework behavior without a device.
- Test naming convention: `methodName_givenCondition_expectResult`, for example `identify_givenFirstIdentify_expectDeviceRegistered`.
- Follow Arrange, Act, Assert.

## Git workflow

- Branch off `main`. There is no develop branch.
- Install hooks once: `lefthook install`. Pre-commit runs `make format` and public API validation; pre-push runs the ktlint check. If CI lint fails, your hooks probably are not installed.
- Commit messages follow conventional commits. The PR title matters most (squash merge), and it is what semantic-release reads.
- Releases are fully automated with semantic-release from `main` (and `beta`/`alpha` prerelease branches) to Maven Central. There is no manual version bump; do not edit version numbers in the repo.

## Project structure

- `buildSrc/` - centralized dependency and version management
- `core/` - central SDK infrastructure (DI, EventBus, shared utilities)
- `base/` - base classes and shared functionality
- `datapipelines/` - primary tracking and analytics functionality
- `messagingpush/` - push notification handling (FCM)
- `messaginginapp/` - in-app messaging
- `messaginginapp-compose/` - Jetpack Compose support for in-app messaging
- `location/` - location and geofence functionality
- `tracking-migration/` - legacy API migration utilities
- `common-test/` - shared testing infrastructure
- `samples/` - sample apps (java_layout, kotlin_compose)
- `docs/dev-notes/` - developer documentation
- `scripts/` - build and automation scripts

## Architecture

### SDK initialization pattern

```kotlin
CustomerIOBuilder(application, "YOUR_CDP_API_KEY").apply {
    region(Region.US) // Region.EU if applicable

    addCustomerIOModule(
        ModuleMessagingInApp(
            MessagingInAppModuleConfig.Builder("YOUR_SITE_ID", Region.US).build()
        )
    )
    addCustomerIOModule(ModuleMessagingPushFCM())
    build()
}

CustomerIO.instance().identify("user-id")
CustomerIO.instance().track("event-name")
```

### Dependency injection

- Constructor-based DI; `DIGraphShared` is the central registry, module graphs extend it.
- Tests override dependencies through the DI graph for isolation.
- Avoid static dependencies and global state.

### Inter-module communication

- Event-driven via `EventBus` with type-safe sealed-class events (profile changes, device registration, etc.).
- Modules must not depend on each other directly.

## Sample apps

- `samples/java_layout` (Java, XML views) and `samples/kotlin_compose` (Kotlin, Compose).
- Both include a `google-services.json` for Firebase; replace credentials with your own workspace values in the app initialization code when testing.
- To test local SDK changes in a sample app: `./gradlew publishToMavenLocal`, then build the sample (`cd samples/kotlin_compose && ./gradlew installDebug`).
- View SDK logs: `adb logcat -s CustomerIO`.

## Release and publishing (context, not something agents do)

- semantic-release runs on merge to `main`; artifacts publish to Maven Central with GPG signing.
- Binary compatibility validation and the `.api` dumps are part of the release contract; that is why apiCheck gates PRs.
