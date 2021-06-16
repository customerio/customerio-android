![min Android SDK version is 16](https://img.shields.io/badge/min%20Android%20SDK-16-green)
[![Maven Central](https://maven-badges.herokuapp.com/maven-central/io.customer.android/sdk/badge.svg)](https://maven-badges.herokuapp.com/maven-central/io.customer.android/sdk)

# Customer.io Android SDK

Official Customer.io SDK for Android

**Note: This project is a work in progress and is not yet ready for general availability. If you are interested in being an early user of this SDK for your iOS app, send us a message at `product@customer.io` saying you would like to try out our iOS SDK. We will work with you to get setup!**

# Getting started

To use the SDK in your Android project....

1. In your root level `build.gradle` file, make sure that you have `mavenCentral()` added as a repository:

```groovy
allprojects {
    repositories {
        google()        
        mavenCentral()
    }
}
```

2. Add your dependency to your app's `build.gradle` file:

```groovy
implementation 'io.customer.android:sdk:<version-here>'
```

Replace `version-here` with the the latest version: ![Maven Central](https://maven-badges.herokuapp.com/maven-central/io.customer.android/sdk/badge.svg)

# Documentation

...Not yet ready. Under development. 

# Contributing

Thank you for your interest in wanting to contribute to the project! Let's get your development environment setup so you can get developing.

To contribute to this project, follow the instructions in [our development document](docs/dev-notes/DEVELOPMENT.md) to get your development environment setup. 

> Note: We value an open, welcoming, diverse, inclusive, and healthy community for this project. All contributors are expected to follow the rules set forward in our [code of conduct](CODE_OF_CONDUCT.md). 

# License

[MIT](LICENSE)