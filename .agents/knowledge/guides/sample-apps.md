---
covers: ['samples/**']
---
# Sample apps

The SDK includes two sample applications for testing and demonstration:

#### Java/XML Layout Sample (`samples/java_layout/`)
- Traditional Android Views with Java
- Demonstrates basic SDK integration patterns
- Shows push notification setup
- Includes various tracking scenarios
- Build and install: `./gradlew :samples:java_layout:installDebug`

#### Kotlin Compose Sample (`samples/kotlin_compose/`)
- Modern UI with Jetpack Compose and Kotlin
- Demonstrates advanced SDK features
- Shows in-app messaging with Compose integration
- Includes comprehensive SDK configuration examples
- Build and install: `./gradlew :samples:kotlin_compose:installDebug`

#### Using Sample Apps for Development
```bash
# Install local SDK artifacts first
./gradlew publishToMavenLocal

# Run Java sample app
cd samples/java_layout
./gradlew installDebug

# Run Kotlin Compose sample app  
cd samples/kotlin_compose
./gradlew installDebug

# View logs from sample apps
adb logcat -s CustomerIO
```

#### Sample App Configuration
- Both apps require `google-services.json` for Firebase integration
- Test credentials should be configured in the app's initialization code
- Sample apps automatically use local SDK artifacts when `./gradlew publishToMavenLocal` is run
- Use sample apps to test new features before writing unit tests
- Sample apps serve as integration tests for the SDK
