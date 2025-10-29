package io.customer.android

object Versions {
    // Android SDK versions
    const val COMPILE_SDK = 36
    const val TARGET_SDK = 36
    const val MIN_SDK = 21

    // When updating AGP version, make sure to also update workflow: gradle-compatibility-builds
    // and script: update-gradle-compatibility as needed.
    internal const val ANDROID_GRADLE_PLUGIN = "8.9.3"
    internal const val ANDROIDX_TEST_JUNIT = "1.3.0"
    internal const val ANDROIDX_TEST_RUNNER = "1.7.0"
    internal const val ANDROIDX_TEST_RULES = "1.7.0"
    internal const val ANDROIDX_APPCOMPAT = "1.7.1"
    internal const val ANDROIDX_KTX = "1.17.0"
    internal const val ANDROIDX_LIFECYCLE_PROCESS = "2.9.4"
    internal const val ANDROIDX_ANNOTATIONS = "1.9.1"

    internal const val COROUTINES = "1.10.2"
    internal const val DOKKA = "1.9.20"
    internal const val JUNIT_BOM = "5.9.3"
    internal const val ESPRESSO = "3.7.0"
    internal const val FIREBASE_MESSAGING = "24.1.2"
    internal const val GRADLE_NEXUS_PUBLISH_PLUGIN = "2.0.0"
    internal const val GOOGLE_PLAY_SERVICES_BASE = "18.7.2"
    internal const val GOOGLE_SERVICES_PLUGIN = "4.4.2"
    internal const val KLUENT = "1.73"
    internal const val KOTLIN_BINARY_VALIDATOR = "0.16.3"
    internal const val KOTLINX_SERIALIZATION_JSON = "1.8.1"
    internal const val KOTLIN = "2.1.21"
    internal const val MOCKK = "1.13.13"
    internal const val SEGMENT = "1.19.2"
    internal const val REDUX_KOTLIN = "0.6.0"
    internal const val ROBOLECTRIC = "4.16"
    internal const val OKHTTP = "4.12.0"
    internal const val RETROFIT = "2.11.0"

    // Compose (using latest stable BOM compatible with Kotlin 2.1.21)
    internal const val COMPOSE_BOM = "2025.10.00"
    internal const val COMPOSE_COMPILER = "2.1.21"
    internal const val WORK_MANAGER = "2.10.5"
}
