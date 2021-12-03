![min Android SDK version is 21](https://img.shields.io/badge/min%20Android%20SDK-21-green)
[![Maven Central](https://maven-badges.herokuapp.com/maven-central/io.customer.android/tracking/badge.svg)](https://maven-badges.herokuapp.com/maven-central/io.customer.android/tracking)

# Summary

This is the official Customer.io SDK for Android.

You'll find our [complete SDK documentation at https://customer.io/docs/sdk/android](https://customer.io/docs/sdk/android/). This readme only contains basic information to help you install and initialize the SDK.

**This SDK is a work in progress!** While we're *very* excited about it, it's still in its alpha phase; it is not ready for general availability. If you want to try it out, contact [product@customer.io](mailto:product@customer.io) and we'll help set you up!

# Getting started

The SDK supports both Kotlin and Java.

To get started, install and initialize the relevant SDK packages in your project.
To minimize our SDK's impact on your app's size, we offer multiple, separate SDKs. You should only install the packages that you need for your project.

> Tip: Check out our [sample android app, Remote Habits](https://github.com/customerio/RemoteHabits-Android), for a example of how to use our SDK. 

## Install the SDK

Before you add Customer.io dependencies, update your repositories in the settings.gradle file to include `mavenCentral()`:

```groovy
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
```

Or if you're using an earlier project setup, in your root level `build.gradle` file, make sure that you have `mavenCentral()` added as a repository:

```groovy
allprojects {
    repositories {
        google()        
        mavenCentral()
    }
}
```

## Available SDKs

Here are the list of available SDKs that you can install. You can find more details on both in [our SDK documentation](/docs/sdk/android/)

```groovy
implementation 'io.customer.android:tracking:<version-here>'
implementation 'io.customer.android:messaging-push-fcm:<version-here>'
```

Replace `version-here` with the the latest version: ![Maven Central](https://maven-badges.herokuapp.com/maven-central/io.customer.android/tracking/badge.svg)

# Initialize the SDK

Before you can use the Customer.io SDK, you need to initialize it. `CustomerIO` is a singleton: once created it will be re-used, until you decide to reconfigure and recreate the instance.

```kotlin
class App : Application() {
   override fun onCreate() {
       super.onCreate()
      val customerIO = CustomerIO.Builder(
            siteId = "your-site-id",
            apiKey = "your-api-key",
            appContext = this
        ).build()


   }
}
```
The `Builder` for CustomerIO exposes configuration options for features such `region`,`timeout`.

```kotlin
        val builder = CustomerIO.Builder(
            siteId = "YOUR-SITE-ID",
            apiKey = "YOUR-API-KEY",
            appContext = this
        )
        builder.setRegion(Region.EU)
        // set the request timeout for all the API requests sent from SDK
        builder.setRequestTimeout(8000L)
        builder.build()
```

> A best practice is to initialize CustomerIO in the Application class, this way you will be able to access that instance from any part of your application using the instance() method.

# More information

See our complete SDK documentation at [https://customer.io/docs/sdk/android/](https://customer.io/docs/sdk/android/)

# Contributing

Thanks for taking an interest in our project! We welcome your contributions. Check out [our development instructions](docs/dev-notes/DEVELOPMENT.md) to get your environment set up and start contributing.

> **Note**: We value an open, welcoming, diverse, inclusive, and healthy community for this project. We expect all  contributors to follow our [code of conduct](CODE_OF_CONDUCT.md).  

# License

[MIT](LICENSE)
