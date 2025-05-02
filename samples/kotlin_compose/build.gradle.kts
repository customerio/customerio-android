plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.kapt")
    id("dagger.hilt.android.plugin")
    id("com.google.gms.google-services")
}

apply {
    // Include Customer.io SDK dependencies and common gradle properties for sample apps
    ext.set("appConfigKeyPrefix", "kotlinCompose_")
    from("$rootDir/samples/sample-app.gradle")
}

android {
    namespace = "io.customer.android.sample.kotlin_compose"
    compileSdk = 34

    defaultConfig {
        applicationId = "io.customer.android.sample.kotlin_compose"
        minSdk = 24
        targetSdk = 33
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
    buildFeatures {
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.4.7"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    jvmToolchain(17)
}

val hiltVersion = io.customer.android.Versions.HILT
val composeVersion = "1.4.1"
val coroutinesVersion = "1.5.2"
val roomVersion = "2.4.2"

dependencies {
    // Compose compiler requires an updated version of Kotlin
    implementation(platform("org.jetbrains.kotlin:kotlin-bom:1.8.20"))

    // Compose dependencies
    implementation("androidx.core:core-ktx:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.1")
    implementation("androidx.activity:activity-compose:1.7.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("com.google.android.material:material:1.12.0")

    implementation(platform("androidx.compose:compose-bom:2023.10.01"))

    // DI
    kapt("com.google.dagger:hilt-android-compiler:$hiltVersion")
    implementation("com.google.dagger:hilt-android:$hiltVersion")

    // Navigation
    implementation("androidx.navigation:navigation-compose:2.6.0-rc01")
    implementation("androidx.hilt:hilt-navigation-compose:1.0.0")

    // Persistence
    implementation("androidx.room:room-ktx:$roomVersion")
    kapt("androidx.room:room-compiler:$roomVersion")

    implementation("androidx.datastore:datastore-core:1.0.0")
    implementation("androidx.datastore:datastore-preferences:1.0.0")

    // Leak detection
    debugImplementation("com.squareup.leakcanary:leakcanary-android:2.11")
    androidTestImplementation("com.squareup.leakcanary:leakcanary-android-instrumentation:2.11")

    androidTestImplementation("com.google.dagger:hilt-android-testing:2.39.1")
    kaptAndroidTest("com.google.dagger:hilt-android-compiler:2.44.2")

    // Testing dependencies
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4:$composeVersion")
    debugImplementation("androidx.compose.ui:ui-test-manifest:$composeVersion")
}

