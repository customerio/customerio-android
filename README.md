![min Android SDK version is 21](https://img.shields.io/badge/min%20Android%20SDK-21-green)
[![Maven Central](https://maven-badges.herokuapp.com/maven-central/io.customer.android/tracking/badge.svg)](https://maven-badges.herokuapp.com/maven-central/io.customer.android/tracking)

# Customer.io Android SDK

Official Customer.io SDK for Android

**This is a work in progress!** While we're *very* excited about it, it's still in its alpha phase; it is not ready for general availability. If you want to try it out, contact [product@customer.io](mailto:product@customer.io) and we'll help set you up!

# Getting started

The SDK supports both Kotlin and Java

To get started, you need to install and initialize the relevant SDK packages in your project. 
To minimize our SDK's impact on your app's size, we offer multiple, separate artifacts. You should only install the artifact that you need for your project. 

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

Or if you're using an older project setup, in your root level `build.gradle` file, make sure that you have `mavenCentral()` added as a repository:

```groovy
allprojects {
    repositories {
        google()        
        mavenCentral()
    }
}
```

### Available Artifiacts

2. Here are the list of available dependencies that you can install. You can find more details on both in the later sections.

```groovy
implementation 'io.customer.android:tracking:<version-here>'
implementation 'io.customer.android:messaging-push-fcm:<version-here>'
```

Replace `version-here` with the the latest version: ![Maven Central](https://maven-badges.herokuapp.com/maven-central/io.customer.android/tracking/badge.svg)

# Initialize the SDK

Before you can use the Customer.io SDK, you need to initialize it. `CustomerIO` is a singleton: you'll create it once and re-use it across your application.

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
The Builder for CustomerIO exposes configuration options for features such `region`,`timeout`.

> A best practice is to initialize CustomerIO in the Application class, this way you will be able to access that instance from any part of your application using the instance() method.

# Tracking

### Dependency
Tracking artifact is needed in order to use tracking features
```groovy
implementation 'io.customer.android:tracking:<version-here>'
```
## Identify a customer

When you identify a customer, you:
1. Add or update the customer's profile in your workspace.
2. Save the customer's information on the device. Future calls to the SDK are linked to the last-identified customer. For example, after you identify a person, any events that you track are automatically associated with that person.

You can only identify one customer at a time. The SDK "remembers" the most recently-identified customer.
If you identify customer "A", and then call the identify function for customer "B", the SDK "forgets" customer "A" and assumes that customer "B" is the current app user. 


```kotlin
        CustomerIO.instance()
            .identify(
                identifier = "989388339",
                attributes = mapOf("first_name" to "firstName")
            ).enqueue { result ->
                when (result) {
                    is ErrorResult -> {
                        // Handle error
                    }
                    is Success -> {
                        // Handle success
                    }
                }
            }
```

## Track a custom event

Once you've identified a customer, you can use the `track` method to capture customer activity and send it Customer.io, optionally supplying event data with your request

```kotlin
CustomerIO.instance().track(
                name = "logged_in",
                attributes = mapOf("ip" to "127.0.0.1")
            ).enqueue { result ->
                when (result) {
                    is ErrorResult -> {
                        // Handle error
                    }
                    is Success -> {
                        // Handle success
                    }
                }
            }
```

`attributes` accepts the following data types:
- Primitives (int, float, char...) and their boxed counterparts (Integer, Float, Character...).
- Arrays, Collections, Lists, Sets, and Maps
- Strings
- Enums

## Stop identifying a customer
In your app you may need to stop identifying a profile in the Customer.io SDK. There are 2 ways to do that:

1. Call the `identify()` function which will stop identifying the previously identified profile (if there was one) and remember this new profile.
2. Use `clearIdentify()` to stop identifying the previously identified profile (if there was one). 

```kotlin
CustomerIO.instance().clearIdentify()
```

# Push notifications 
Want to send push notification messages to your customer's devices? Great!

The Customer.io SDK supports sending push notifications via FCM for now. 

## Getting Started FCM 

### Dependency
Push messaging artifact is needed in order to use push notification features
```groovy
implementation 'io.customer.android:messaging-push-fcm:<version-here>'
```

The artifact adds a `FirebaseMessagingService` to the app manifest automatically, so you don't have to do any extra setup to handle incoming push messages.

In case, your application implements its own `FirebaseMessagingService`, make sure on  `onMessageReceived` and `onNewToken` method calls you additionally call CustomerIOFirebaseMessagingService.onMessageReceived and CustomerIOFirebaseMessagingService.onNewToken, respectively:

```kotlin
class FirebaseMessagingService : FirebaseMessagingService() {

 override fun onMessageReceived(message: RemoteMessage) {
        val handled = CustomerIOFirebaseMessagingService.onMessageReceived(this, message)
        if (handled) {
            logger.breadcrumb(this, "Push notification has been handled", null)
        }
 }
 
 override fun onNewToken(token: String) {
     CustomerIOFirebaseMessagingService.onNewToken(token) { result ->
                 when (result) {
                     is ErrorResult -> {
                         // Handle error
                     }
                     is Success -> {
                         // Handle success
                     }
                 }
             }
     }
}
```

## Tracking push metrics
When handling push messages from Customer.io, you may want to have your app report back device-side metrics when people interact with your messages. Customer.io supports three device-side metrics: `delivered`, `opened`, and `converted`. You can find more information about these metrics [in our push developer guide](https://customer.io/docs/push-developer-guide).

```kotlin
CustomerIO.instance().trackMetric(
            deliveryID = deliveryId,
            deviceToken = deliveryToken,
            event = MetricEvent.delivered
        ).enqueue { result ->
            when (result) {
                is ErrorResult -> {
                    // Handle error
                }
                is Success -> {
                    // Handle success
                }
            }
        }
```

If you already configured Push messaging artifact, then our SDK will automatically track `opened` and `delivered` events by default for push notifications originating from Customer.io.

# Callbacks/Error handelling 
CustomerIO provides an `Action` interface for almost all of its method. You can use it to make method calls execute synchronously or asynchronously. Method returns `Result<T>` object, which futher contains a `Success<T>` or `ErrorResult<T>` object. 
In order to get any error details, `ErrorResult` provides and `ErrorDetail` object. 

```kotlin
class ErrorDetail(
    val message: String? = null,
    val statusCode: StatusCode = StatusCode.Unknown,
    val cause: Throwable = Throwable()
) 
```


# Contributing

Thanks for taking an interest in our project! We welcome your contributions. Check out [our development instructions](docs/dev-notes/DEVELOPMENT.md) to get your environment set up and start contributing.

> **Note**: We value an open, welcoming, diverse, inclusive, and healthy community for this project. We expect all  contributors to follow our [code of conduct](CODE_OF_CONDUCT.md).  

# License

[MIT](LICENSE)
