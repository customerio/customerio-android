plugins {
    `kotlin-dsl`
}

repositories {
    mavenCentral()
    google()
}

dependencies {
    // Don't add AGP here: buildSrc's classpath is the parent of every build script's, so it would
    // override Versions.ANDROID_GRADLE_PLUGIN (and the compatibility builds that rewrite it).
    implementation("com.google.code.gson:gson:2.10.1")
}
