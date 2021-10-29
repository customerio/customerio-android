package io.customer.android

object Dependencies {
    const val androidGradlePlugin =
        "com.android.tools.build:gradle:${Versions.ANDROID_GRADLE_PLUGIN}"
    const val androidJunit5GradlePlugin =
        "de.mannodermaus.gradle.plugins:android-junit5:${Versions.ANDROID_JUNIT5_GRADLE_PLUGIN}"
    const val androidxTestJunit = "androidx.test.ext:junit:${Versions.ANDROIDX_TEST_JUNIT}"
    const val coroutinesAndroid =
        "org.jetbrains.kotlinx:kotlinx-coroutines-android:${Versions.COROUTINES}"
    const val androidxCoreKtx = "androidx.core:core-ktx:${Versions.ANDROIDX_KTX}"
    const val androidxAnnotations =
        "androidx.annotation:annotation:${Versions.ANDROIDX_ANNOTATIONS}"
    const val coroutinesCore =
        "org.jetbrains.kotlinx:kotlinx-coroutines-core:${Versions.COROUTINES}"
    const val materialComponents =
        "com.google.android.material:material:${Versions.MATERIAL_COMPONENTS}"
    const val androidxAppCompat = "androidx.appcompat:appcompat:${Versions.ANDROIDX_APPCOMPAT}"
    const val coroutinesTest =
        "org.jetbrains.kotlinx:kotlinx-coroutines-test:${Versions.COROUTINES}"
    const val espressoCore = "androidx.test.espresso:espresso-core:${Versions.ESPRESSO}"
    const val dokka = "org.jetbrains.dokka:dokka-gradle-plugin:${Versions.DOKKA}"
    const val firebaseMessaging =
        "com.google.firebase:firebase-messaging:${Versions.FIREBASE_MESSAGING}"
    const val googlePlayServicesBase =
        "com.google.android.gms:play-services-base:${Versions.GOOGLE_PLAY_SERVICES_BASE}"
    const val gradleNexusPublishPlugin =
        "io.github.gradle-nexus:publish-plugin:${Versions.GRADLE_NEXUS_PUBLISH_PLUGIN}"
    const val gradleVersionsPlugin =
        "com.github.ben-manes:gradle-versions-plugin:${Versions.GRADLE_VERSIONS_PLUGIN}"
    const val junit4 = "junit:junit:${Versions.JUNIT4}"
    const val kotlinGradlePlugin = "org.jetbrains.kotlin:kotlin-gradle-plugin:${Versions.KOTLIN}"
    const val timber = "com.jakewharton.timber:timber:${Versions.TIMBER}"
    const val moshi = "com.squareup.moshi:moshi:${Versions.MOSHI}"
    const val moshiCodeGen = "com.squareup.moshi:moshi-kotlin-codegen:${Versions.MOSHI}"
    const val robolectric = "org.robolectric:robolectric:${Versions.ROBOLECTRIC}"
    const val retrofit = "com.squareup.retrofit2:retrofit:${Versions.RETROFIT}"
    const val retrofitMoshiConverter = "com.squareup.retrofit2:converter-moshi:${Versions.RETROFIT}"
    const val okhttp = "com.squareup.okhttp3:okhttp:${Versions.OKHTTP}"
    const val okhttpLoggingInterceptor =
        "com.squareup.okhttp3:logging-interceptor:${Versions.OKHTTP}"

}
