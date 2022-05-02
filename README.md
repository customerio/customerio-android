<p align="center">
  <a href="https://customer.io">
    <img src="https://user-images.githubusercontent.com/6409227/144680509-907ee093-d7ad-4a9c-b0a5-f640eeb060cd.png" height="60">
  </a>
  <p align="center">Power automated communication that people like to receive.</p>
</p>

![min Android SDK version is 21](https://img.shields.io/badge/min%20Android%20SDK-21-green)
[![Maven Central](https://maven-badges.herokuapp.com/maven-central/io.customer.android/tracking/badge.svg)](https://maven-badges.herokuapp.com/maven-central/io.customer.android/tracking)
[![Contributor Covenant](https://img.shields.io/badge/Contributor%20Covenant-2.0-4baaaa.svg)](code_of_conduct.md) 
[![codecov](https://codecov.io/gh/customerio/customerio-android/branch/develop/graph/badge.svg?token=PYV1XQTKGO)](https://codecov.io/gh/customerio/customerio-android)

# Customer.io Android SDK

This is the official Customer.io SDK for Android.

# Getting started

The SDK supports both Kotlin and Java.

To get started, you need to install and initialize the relevant [SDK packages](#available-sdks) in your project. We've separated our SDK into packages to minimize our impact on your app's size. You should only install the packages that you need for your project. 

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

### Available SDKs

We separated our SDK into packages to minimize our impact on your app's size. You should only install the packages that you need for your project. 

| Package | Required? | Description |
| :-- | :---: | :--- |
| `tracking` | Yes | [`identify`](https://customer.io/docs/sdk/android/identify/) people/devices and [send events](https://customer.io/docs/sdk/android/track-events/) (to trigger campaigns, track metrics, etc). |
| `messaging-push-fcm` | No | [Push](https://customer.io/docs/sdk/android/push/) and [rich push](https://customer.io/docs/sdk/android/rich-push/) notifications using Google Firebase Cloud Messaging (FCM). |

```groovy
implementation 'io.customer.android:tracking:<version-here>'
implementation 'io.customer.android:messaging-push-fcm:<version-here>'
```

Replace `version-here` with the the latest version: ![Maven Central](https://maven-badges.herokuapp.com/maven-central/io.customer.android/tracking/badge.svg)


## Initialize the SDK

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
