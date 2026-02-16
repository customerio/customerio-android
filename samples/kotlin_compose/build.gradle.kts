import io.customer.android.Dependencies

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.kapt")
    id("com.google.gms.google-services")
    id("org.jetbrains.kotlin.plugin.compose")
}

apply {
    // Include Customer.io SDK dependencies and common gradle properties for sample apps
    ext.set("appConfigKeyPrefix", "kotlinCompose_")
    from("$rootDir/samples/sample-app.gradle")
}

android {
    namespace = "io.customer.android.sample.kotlin_compose"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.customer.android.sample.kotlin_compose"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner =
            "io.customer.android.sample.kotlin_compose.TestApplicationRunner"

        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro"
            )
        }
    }
    // buildFeatures and composeOptions are configured in sample-app.gradle

}

kotlin {
    jvmToolchain(17)
}

// Dependencies versions
val datastoreVersion = "1.1.7"
val leakCanaryVersion = "2.14"
val lifecycleVersion = "2.9.4"
val navigationVersion = "2.9.5"
val roomVersion = "2.8.2"

dependencies {
    // Compose dependencies are included from sample-app.gradle
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:$lifecycleVersion")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:$lifecycleVersion")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("com.google.android.material:material:1.13.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.1")
    implementation(Dependencies.androidxAppCompat)

    // Navigation
    implementation("androidx.navigation:navigation-fragment-ktx:$navigationVersion")
    implementation("androidx.navigation:navigation-ui-ktx:$navigationVersion")
    implementation("androidx.navigation:navigation-compose:$navigationVersion")

    // Persistence - using KAPT since we removed KSP
    implementation("androidx.room:room-ktx:$roomVersion")
    kapt("androidx.room:room-compiler:$roomVersion")

    // DataStore
    implementation("androidx.datastore:datastore-core:$datastoreVersion")
    implementation("androidx.datastore:datastore-preferences:$datastoreVersion")

    // Leak detection
    debugImplementation("com.squareup.leakcanary:leakcanary-android:$leakCanaryVersion")
    androidTestImplementation("com.squareup.leakcanary:leakcanary-android-instrumentation:$leakCanaryVersion")

    // Testing dependencies
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation(Dependencies.androidxTestJunit)
    androidTestImplementation(Dependencies.espressoCore)
    androidTestImplementation(Dependencies.androidxTestRunner)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4:${project.ext["COMPOSE_VERSION"]}")
    debugImplementation("androidx.compose.ui:ui-test-manifest:${project.ext["COMPOSE_VERSION"]}")
}

