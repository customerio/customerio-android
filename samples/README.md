# Sample Apps

This directory contains sample apps demonstrating how to integrate and use Customer.io SDK in Android projects. The sample apps provided are:

- **[java_layout](java_layout)**: Java-based app using XML layouts
- **[kotlin_compose](kotlin_compose)**: Kotlin-based app using Jetpack Compose

## Getting Started

### Setting Up API Keys

To compile and run these sample apps, you'll need to set up `cdpApiKey` and `siteId` in `samples/local.properties` file. Follow these steps:

1. In `samples` directory, create a file named `local.properties`
2. Add the following lines to the file, replacing `cdpApiKeyValue` and `siteIdValue` with your actual keys:

    ```plaintext
    cdpApiKey=cdpApiKeyValue
    siteId=siteIdValue
    ```

### Running the Apps

1. Open `customerio-android` directory in Android Studio.
2. Select the desired app configuration (`samples.java_layout` or `samples.kotlin_compose`) from run configuration dropdown in Android Studio.
3. Click the `Run` button to build and run the app on an emulator or connected device.

_**These samples are designed to give you a quick start with integrating and using the SDK in your own projects. For more details on the SDK's features and capabilities, please refer to [complete SDK documentation](https://customer.io/docs/sdk/android/).**_
