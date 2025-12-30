package io.customer.android

object Dependencies {
    const val androidGradlePlugin =
        "com.android.tools.build:gradle:${Versions.ANDROID_GRADLE_PLUGIN}"
    const val androidxTestJunit = "androidx.test.ext:junit:${Versions.ANDROIDX_TEST_JUNIT}"
    const val androidxTestRunner = "androidx.test:runner:${Versions.ANDROIDX_TEST_RUNNER}"
    const val androidxTestRules = "androidx.test:rules:${Versions.ANDROIDX_TEST_RULES}"

    const val coroutinesAndroid =
        "org.jetbrains.kotlinx:kotlinx-coroutines-android:${Versions.COROUTINES}"
    const val androidxCoreKtx = "androidx.core:core-ktx:${Versions.ANDROIDX_KTX}"
    const val androidxProcessLifecycle = "androidx.lifecycle:lifecycle-process:${Versions.ANDROIDX_LIFECYCLE_PROCESS}"
    const val androidxAnnotations =
        "androidx.annotation:annotation:${Versions.ANDROIDX_ANNOTATIONS}"
    const val coroutinesCore =
        "org.jetbrains.kotlinx:kotlinx-coroutines-core:${Versions.COROUTINES}"
    const val androidxAppCompat = "androidx.appcompat:appcompat:${Versions.ANDROIDX_APPCOMPAT}"
    const val coroutinesTest =
        "org.jetbrains.kotlinx:kotlinx-coroutines-test:${Versions.COROUTINES}"
    const val espressoCore = "androidx.test.espresso:espresso-core:${Versions.ESPRESSO}"
    const val espressoIntents = "androidx.test.espresso:espresso-intents:${Versions.ESPRESSO}"
    const val dokka = "org.jetbrains.dokka:dokka-gradle-plugin:${Versions.DOKKA}"
    const val firebaseMessaging =
        "com.google.firebase:firebase-messaging:${Versions.FIREBASE_MESSAGING}"
    const val googlePlayServicesBase =
        "com.google.android.gms:play-services-base:${Versions.GOOGLE_PLAY_SERVICES_BASE}"
    const val googleServicesPlugin =
        "com.google.gms:google-services:${Versions.GOOGLE_SERVICES_PLUGIN}"
    const val gradleNexusPublishPlugin =
        "io.github.gradle-nexus:publish-plugin:${Versions.GRADLE_NEXUS_PUBLISH_PLUGIN}"
    const val junit4 = "junit:junit"
    const val junitBom = "org.junit:junit-bom:${Versions.JUNIT_BOM}"
    const val junitJupiter = "org.junit.jupiter:junit-jupiter"
    const val junitVintageEngine = "org.junit.vintage:junit-vintage-engine"
    const val kluent = "org.amshove.kluent:kluent-android:${Versions.KLUENT}"
    const val kotlinBinaryValidator =
        "org.jetbrains.kotlinx:binary-compatibility-validator:${Versions.KOTLIN_BINARY_VALIDATOR}"
    const val kotlinxSerializationJson = "org.jetbrains.kotlinx:kotlinx-serialization-json:${Versions.KOTLINX_SERIALIZATION_JSON}"
    const val kluentJava = "org.amshove.kluent:kluent:${Versions.KLUENT}"
    const val kotlinGradlePlugin = "org.jetbrains.kotlin:kotlin-gradle-plugin:${Versions.KOTLIN}"
    const val kotlinSerialization = "org.jetbrains.kotlin:kotlin-serialization:${Versions.KOTLIN}"
    const val mockK = "io.mockk:mockk:${Versions.MOCKK}"
    const val mockKAndroid = "io.mockk:mockk-android:${Versions.MOCKK}"
    const val robolectric = "org.robolectric:robolectric:${Versions.ROBOLECTRIC}"
    const val reduxKotlin = "org.reduxkotlin:redux-kotlin-threadsafe-jvm:${Versions.REDUX_KOTLIN}"
    const val retrofit = "com.squareup.retrofit2:retrofit:${Versions.RETROFIT}"
    const val okhttpMockWebserver = "com.squareup.okhttp3:mockwebserver:${Versions.OKHTTP}"
    const val okhttpLoggingInterceptor =
        "com.squareup.okhttp3:logging-interceptor:${Versions.OKHTTP}"
    const val okhttpSse = "com.squareup.okhttp3:okhttp-sse:${Versions.OKHTTP}"
    const val segment = "com.segment.analytics.kotlin:android:${Versions.SEGMENT}"
    const val workManager = "androidx.work:work-runtime-ktx:${Versions.WORK_MANAGER}"
    const val workManagerTesting = "androidx.work:work-testing:${Versions.WORK_MANAGER}"

    // Compose dependencies
    const val composeBom = "androidx.compose:compose-bom:${Versions.COMPOSE_BOM}"
    const val composeCompilerGradlePlugin = "org.jetbrains.kotlin:compose-compiler-gradle-plugin:${Versions.COMPOSE_COMPILER}"
    const val composeUi = "androidx.compose.ui:ui"
    const val composeUiGraphics = "androidx.compose.ui:ui-graphics"
    const val composeFoundation = "androidx.compose.foundation:foundation"
    const val composeMaterial = "androidx.compose.material:material"
    const val composeRuntime = "androidx.compose.runtime:runtime"
}
