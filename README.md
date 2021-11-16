![min Android SDK version is 21](https://img.shields.io/badge/min%20Android%20SDK-21-green)
[![Maven Central](https://maven-badges.herokuapp.com/maven-central/io.customer.android/tracking/badge.svg)](https://maven-badges.herokuapp.com/maven-central/io.customer.android/tracking)

# Customer.io Android SDK

Official Customer.io SDK for Android

**This is a work in progress!** While we're *very* excited about it, it's still in its alpha phase; it is not ready for general availability. If you want to try it out, contact [product@customer.io](mailto:product@customer.io) and we'll help set you up!

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

### Available SDKs

2. Here are the list of available SDKs that you can install. You can find more details on both in the later sections.

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

# Tracking

### Dependency
The Tracking SDK is needed in order to use the following tracking features.
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

> Tip: See [Callbacks/Error handling](#Callbacks/Error-handling) to learn more about handling of callbacks and errors.

## Track a custom event

Once you've identified a customer, you can use the `track` method to capture customer activity and send it Customer.io, optionally supplying event data with your request.

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

The Customer.io SDK supports sending push notifications via FCM.

## Getting Started FCM 

### Dependency
The Push Messaging SDK is needed in order to use push notification features.
```groovy
implementation 'io.customer.android:messaging-push-fcm:<version-here>'
```

The package adds a `FirebaseMessagingService` to the app manifest automatically, so you don't have to do any extra setup to handle incoming push messages.

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
     // whenever a token update is available, register the updated token
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

> **Note**: It is recommended to [retrieve the current registration token](https://firebase.google.com/docs/cloud-messaging/android/client#retrieve-the-current-registration-token) and register it using `CustomerIO.instance().registerDeviceToken(token)` right after you identify the customer, so the identified customer has latest token associated with it.

Currently push notifications launched via CustomerIO SDK are posted to our default channel `[App Name] Notifications` but in future we plan to bring customised channels/categories so that users can subscribe and unsubscribe to content as necessary.

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

## Rich Push
Interested in doing more with your push notification? Showing an image? Opening a deep link when a push is touched? That's what we call *rich push* notifications. Let's get into how to send them.

> Note: At this time, the Customer.io SDK only works with deep links and images.

To use rich push, you can use the following template

```json
{
  "message": {
    "data": {
      "link": "remote-habits://deep?message=hello&message2=world",
      "image": "https://external-content.duckduckgo.com/iu/?u=https%3A%2F%2Fpawsindoorssouthend.com%2Fwp-content%2Fuploads%2F2020%2F04%2Ftips-for-choosing-doggy-day-care.jpg",
      "title": "Hey",
      "body": "there you"
    }
  }
}
```
> Tip: [See our doc](https://customer.io/docs/push-custom-payloads/#getting-started-with-custom-payloads) if you are unsure how to use custom payloads for sending push notifications.

With both of the templates:
* Modify the `link` to the deep link URL that you want to open when the push notification is touched.
* Modify the `image` to the URL of an image you want to display in the push notification. It's important that the image URL starts with `https://` and *not* `http://` or the image may not be displayed.

## Deep links
After you have followed the setup instructions for [setting up rich push notifications](#rich-push) you can enable deep links in rich push notifications.

There are two ways of handling deep links in your application.

1. Using the [intent filters](https://developer.android.com/training/app-links/deep-linking) in your `AndroidManifest.xml` file

```xml
            <intent-filter android:label="deep_linking_filter">
                <action android:name="android.intent.action.VIEW" />
                <category android:name="android.intent.category.DEFAULT" />
                <category android:name="android.intent.category.BROWSABLE" />
                <!-- Accepts URIs that begin with "remote-habits://deep” -->
                <data
                    android:host="deep"
                    android:scheme="remote-habits" />
            </intent-filter>
```

> Tip: In case if no intent filters are found, CustomerIO SDK will open the web browser for this link.

2. Using `CustomerIOUrlHandler` (a url handler feature provided by CustomerIO SDK). To use this attach a listener using `setCustomerIOUrlHandler` method of `CustomerIO.Builder`. This should ideally be done in the entry point of the application, which is usually the `Application` class.

```kotlin
class MainApplication : Application(), CustomerIOUrlHandler {

    override fun onCreate() {
        super.onCreate()
        val builder = CustomerIO.Builder(
            siteId = "YOUR-SITE-ID",
            apiKey = "YOUR-API-KEY",
            appContext = this
        )
        builder.setCustomerIOUrlHandler(this)
        builder.build()
    }
    override fun handleCustomerIOUrl(uri: Uri): Boolean {
        // return true in case you plan to handle the deep link yourself, false if you want CustomerIO SDK to do it for you
        TODO("Pass the link to your Deep link managers")
    }
}
```

> Tip: When the push notification with deep link is clicked, the SDK first calls the urlHandler specified in your `CustomerIO.Builder` object. If the handler is not set or returns `false`, only then SDK will open browser.

> **Note**: If both methods are used, the second one takes precedence in execution.

# Callbacks/Error handling
CustomerIO provides an `Action` interface for almost all of its methods. You can use it to make method calls execute synchronously or asynchronously. Method returns `Result<T>` object, which futher contains a `Success<T>` or `ErrorResult<T>` object.
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
