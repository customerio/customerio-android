package io.customer.android

object Versions {
    // Android SDK versions
    const val COMPILE_SDK = 34
    const val TARGET_SDK = 33
    const val MIN_SDK = 21

    // When updating AGP version, make sure to also update workflow: gradle-compatibility-builds
    // and script: update-gradle-compatibility as needed.
    internal const val ANDROID_GRADLE_PLUGIN = "8.3.1"
    internal const val ANDROIDX_TEST_JUNIT = "1.1.4"
    internal const val ANDROIDX_TEST_RUNNER = "1.4.0"
    internal const val ANDROIDX_TEST_RULES = "1.4.0"
    internal const val ANDROIDX_APPCOMPAT = "1.3.1"
    internal const val ANDROIDX_KTX = "1.6.0"
    internal const val ANDROIDX_LIFECYCLE_PROCESS = "2.6.1"
    internal const val ANDROIDX_ANNOTATIONS = "1.3.0"
    internal const val APK_SCALE = "0.1.7"

    internal const val COROUTINES = "1.7.3"
    internal const val DOKKA = "1.8.20"
    internal const val JUNIT_BOM = "5.9.3"
    internal const val ESPRESSO = "3.4.0"
    internal const val FIREBASE_MESSAGING = "23.1.0"
    internal const val GRADLE_NEXUS_PUBLISH_PLUGIN = "1.3.0"
    internal const val GOOGLE_PLAY_SERVICES_BASE = "17.6.0"
    internal const val GOOGLE_SERVICES_PLUGIN = "4.3.15"
    const val HILT = "2.44.2"
    internal const val KLUENT = "1.72"
    internal const val KOTLIN_BINARY_VALIDATOR = "0.14.0"
    internal const val KOTLINX_SERIALIZATION_JSON = "1.5.1"
    internal const val KOTLIN = "1.8.21"
    internal const val MATERIAL_COMPONENTS = "1.4.0"
    internal const val MOCKITO_KOTLIN = "4.0.0"
    internal const val MOCKITO = "4.8.1"
    internal const val MOCKK = "1.12.2"
    internal const val MOSHI = "1.14.0"
    internal const val SEGMENT = "1.19.1"
    internal const val TIMBER = "5.0.0"
    internal const val REDUX_KOTLIN = "0.6.0"
    internal const val ROBOLECTRIC = "4.9"
    internal const val OKHTTP = "4.11.0"
    internal const val RETROFIT = "2.9.0"

    // Compose versions
    // Note: Using 2023.03.00 as it's the latest version compatible with compileSdk 33.
    // Later versions (2023.06.00+) require compileSdk 34 due to dependencies like androidx.emoji2:emoji2:1.4.0.
    // If compileSdk is updated to 34+, this can be upgraded to a newer version.
    internal const val COMPOSE_BOM = "2023.03.00"
    internal const val COMPOSE_COMPILER = "1.4.7"
}
