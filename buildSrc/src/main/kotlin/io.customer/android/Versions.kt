package io.customer.android

object Versions {
    // Android SDK versions
    const val COMPILE_SDK = 34
    const val TARGET_SDK = 33
    const val MIN_SDK = 21

    // When updating AGP version, make sure to also update workflow: gradle-compatibility-builds
    // and script: update-gradle-compatibility as needed.
    internal const val ANDROID_GRADLE_PLUGIN = "8.6.1"
    internal const val ANDROIDX_TEST_JUNIT = "1.1.4"
    internal const val ANDROIDX_TEST_RUNNER = "1.4.0"
    internal const val ANDROIDX_TEST_RULES = "1.4.0"
    internal const val ANDROIDX_APPCOMPAT = "1.7.0"
    internal const val ANDROIDX_KTX = "1.13.1"
    internal const val ANDROIDX_LIFECYCLE_PROCESS = "2.8.7"
    internal const val ANDROIDX_ANNOTATIONS = "1.8.2"

    internal const val COROUTINES = "1.10.2"
    internal const val DOKKA = "1.9.20"
    internal const val JUNIT_BOM = "5.9.3"
    internal const val ESPRESSO = "3.4.0"
    internal const val FIREBASE_MESSAGING = "24.0.2"
    internal const val GRADLE_NEXUS_PUBLISH_PLUGIN = "2.0.0"
    internal const val GOOGLE_PLAY_SERVICES_BASE = "18.2.0"
    internal const val GOOGLE_SERVICES_PLUGIN = "4.4.2"
    const val HILT = "2.57"
    internal const val KLUENT = "1.73"
    internal const val KOTLIN_BINARY_VALIDATOR = "0.16.3"
    internal const val KOTLINX_SERIALIZATION_JSON = "1.9.0"
    internal const val KOTLIN = "2.1.21"
    internal const val MATERIAL_COMPONENTS = "1.12.0"
    internal const val MOCKITO_KOTLIN = "5.4.0"
    internal const val MOCKITO = "5.14.2"
    internal const val MOCKK = "1.13.13"
    internal const val MOSHI = "1.15.1"
    internal const val SEGMENT = "1.19.2"
    internal const val TIMBER = "5.0.1"
    internal const val REDUX_KOTLIN = "0.6.0"
    internal const val ROBOLECTRIC = "4.13"
    internal const val OKHTTP = "4.12.0"
    internal const val RETROFIT = "2.11.0"

    // Compose (using latest stable BOM compatible with Kotlin 2.1.20)
    internal const val COMPOSE_BOM = "2024.12.01"
    internal const val COMPOSE_COMPILER = "2.1.21"
}
