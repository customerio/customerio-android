# Sample Apps

This section contains sample apps demonstrating how to integrate and use Customer.io SDK in Android projects. The sample apps provided are:

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
<img width="480" alt="Screenshot 2024-08-14 at 3 35 43 PM" src="https://github.com/user-attachments/assets/3d7230c9-0492-49ac-9252-1dcb5a4bfa8c">

### SDK Dependency

The sample apps currently use SDK as local module dependency (e.g., `project(":datapipelines")`). If you want to use a specific version of the SDK instead, there are two options:

1. **Checkout a Specific Tag**: You can check out repository at the desired tag corresponding to SDK version.
2. **Specify in local.properties**: Alternatively, you can specify the SDK version in `samples/local.properties` file by adding the following line:

    ```plaintext
    sdkVersion=4.1.0
    ```

---

**These samples are designed to give a quick start with integrating and using the SDK in Android projects. For more details on the SDK's features and capabilities, please refer to [complete SDK documentation](https://customer.io/docs/sdk/android/).**
