package customerio.android

object Dependencies {
    const val androidGradlePlugin =
        "com.android.tools.build:gradle:${Versions.ANDROID_GRADLE_PLUGIN}"
    const val androidJunit5GradlePlugin =
        "de.mannodermaus.gradle.plugins:android-junit5:${Versions.ANDROID_JUNIT5_GRADLE_PLUGIN}"
    const val androidxTestJunit = "androidx.test.ext:junit:${Versions.ANDROIDX_TEST_JUNIT}"
    const val coroutinesAndroid =
        "org.jetbrains.kotlinx:kotlinx-coroutines-android:${Versions.COROUTINES}"
    const val androidxCoreKtx = "androidx.core:core-ktx:${Versions.ANDROIDX_KTX}"
    const val coroutinesCore =
        "org.jetbrains.kotlinx:kotlinx-coroutines-core:${Versions.COROUTINES}"
    const val androidxAppCompat = "androidx.appcompat:appcompat:${Versions.ANDROIDX_APPCOMPAT}"
    const val coroutinesTest =
        "org.jetbrains.kotlinx:kotlinx-coroutines-test:${Versions.COROUTINES}"
    const val espressoCore = "androidx.test.espresso:espresso-core:${Versions.ESPRESSO}"
    const val dokka = "org.jetbrains.dokka:dokka-gradle-plugin:${Versions.DOKKA}"
    const val gradleNexusPublishPlugin =
        "io.github.gradle-nexus:publish-plugin:${Versions.GRADLE_NEXUS_PUBLISH_PLUGIN}"
    const val gradleVersionsPlugin =
        "com.github.ben-manes:gradle-versions-plugin:${Versions.GRADLE_VERSIONS_PLUGIN}"
    const val junit4 = "junit:junit:${Versions.JUNIT4}"
    const val kotlinGradlePlugin = "org.jetbrains.kotlin:kotlin-gradle-plugin:${Versions.KOTLIN}"
    const val kotlinReflect = "org.jetbrains.kotlin:kotlin-reflect:${Versions.KOTLIN}"
    const val timber = "com.jakewharton.timber:timber:${Versions.TIMBER}"

}
