# AI Dependency Analysis Configuration

This document contains the configuration and logic for AI-powered dependency analysis in the Customer.io Android SDK. This configuration can be used by multiple AI agents (Claude, OpenAI, etc.) for consistent dependency analysis.

## 🎯 **Customer.io SDK Context**

### **Core Components**
- **Push notification service** (Firebase integration)
- **In-app messaging system** with Compose UI and Redux state management
- **Event tracking and analytics** via Segment Analytics
- **Background processing and queuing**
- **Network requests** for API communication (Retrofit + OkHttp)
- **Data persistence and caching**
- **Sample apps**: Kotlin Compose and Java layouts

### **Key Architecture Files**
- `buildSrc/src/main/kotlin/io.customer/android/Versions.kt` - Version constants
- `build.gradle` files - Build configuration
- `src/` directories - Source code modules
- `samples/` directory - Sample applications

## 📦 **Dependency Mapping Configuration**

### **Core Dependencies**

```json
{
  "KOTLIN": {
    "name": "Kotlin",
    "group": "org.jetbrains.kotlin",
    "artifact": "kotlin-gradle-plugin",
    "changelogUrl": "https://github.com/JetBrains/kotlin/releases",
    "mavenUrl": "https://repo1.maven.org/maven2/org/jetbrains/kotlin/kotlin-gradle-plugin",
    "customerioImpact": "Core language - affects all modules, coroutines usage, compilation"
  },
  "COROUTINES": {
    "name": "Kotlin Coroutines",
    "group": "org.jetbrains.kotlinx",
    "artifact": "kotlinx-coroutines-core",
    "changelogUrl": "https://github.com/Kotlin/kotlinx.coroutines/releases",
    "mavenUrl": "https://repo1.maven.org/maven2/org/jetbrains/kotlinx/kotlinx-coroutines-core",
    "customerioImpact": "Background processing, async operations, queue management"
  },
  "ANDROID_GRADLE_PLUGIN": {
    "name": "Android Gradle Plugin",
    "group": "com.android.tools.build",
    "artifact": "gradle",
    "changelogUrl": "https://developer.android.com/studio/releases/gradle-plugin",
    "mavenUrl": "https://repo1.maven.org/maven2/com/android/tools/build/gradle",
    "customerioImpact": "Build system, compilation, Android API compatibility"
  },
  "COMPOSE_BOM": {
    "name": "Compose BOM",
    "group": "androidx.compose",
    "artifact": "compose-bom",
    "changelogUrl": "https://developer.android.com/jetpack/androidx/releases/compose",
    "mavenUrl": "https://repo1.maven.org/maven2/androidx/compose/compose-bom",
    "customerioImpact": "In-app messaging UI components, sample app UI"
  },
  "FIREBASE_MESSAGING": {
    "name": "Firebase Messaging",
    "group": "com.google.firebase",
    "artifact": "firebase-messaging",
    "changelogUrl": "https://firebase.google.com/support/release-notes/android",
    "mavenUrl": "https://repo1.maven.org/maven2/com/google/firebase/firebase-messaging",
    "customerioImpact": "Push notification delivery, FCM token management, message handling"
  },
  "RETROFIT": {
    "name": "Retrofit",
    "group": "com.squareup.retrofit2",
    "artifact": "retrofit",
    "changelogUrl": "https://github.com/square/retrofit/releases",
    "mavenUrl": "https://repo1.maven.org/maven2/com/squareup/retrofit2/retrofit",
    "customerioImpact": "API communication, network requests, data synchronization"
  },
  "OKHTTP": {
    "name": "OkHttp",
    "group": "com.squareup.okhttp3",
    "artifact": "okhttp",
    "changelogUrl": "https://github.com/square/okhttp/releases",
    "mavenUrl": "https://repo1.maven.org/maven2/com/squareup/okhttp3/okhttp",
    "customerioImpact": "HTTP client, network interceptors, request/response handling"
  },
  "SEGMENT": {
    "name": "Segment Analytics",
    "group": "com.segment.analytics.kotlin",
    "artifact": "android",
    "changelogUrl": "https://github.com/segmentio/analytics-kotlin/releases",
    "mavenUrl": "https://repo1.maven.org/maven2/com/segment/analytics/kotlin/android",
    "customerioImpact": "Data pipeline architecture, event processing, analytics foundation"
  },
  "REDUX_KOTLIN": {
    "name": "Redux Kotlin",
    "group": "org.reduxkotlin",
    "artifact": "redux-kotlin-threadsafe-jvm",
    "changelogUrl": "https://github.com/reduxkotlin/redux-kotlin/releases",
    "mavenUrl": "https://repo1.maven.org/maven2/org/reduxkotlin/redux-kotlin-threadsafe-jvm",
    "customerioImpact": "In-app messaging state management, UI state consistency"
  },
  "HILT": {
    "name": "Dagger Hilt",
    "group": "com.google.dagger",
    "artifact": "hilt-android",
    "changelogUrl": "https://github.com/google/dagger/releases",
    "mavenUrl": "https://repo1.maven.org/maven2/com/google/dagger/hilt-android",
    "customerioImpact": "Sample app dependency injection only - not used in main SDK"
  }
}
```

## 🔍 **Analysis Framework**

### **Impact Assessment Categories**

#### **1. Customer.io Specific Impact**
- **Push Notifications**: How changes affect Firebase integration, FCM tokens, message delivery
- **In-App Messaging**: Effects on Compose UI, Redux state management, message display
- **Event Tracking**: Impact on Segment Analytics, data pipeline, event processing
- **Background Processing**: Changes to coroutines, queue management, async operations
- **Network Communication**: API client changes, request/response handling
- **Data Persistence**: Caching, storage, data consistency
- **Sample Apps**: Compilation, UI, dependency injection

#### **2. Breaking Changes Analysis**
- List specific API changes from changelog
- Assess likelihood of affecting Customer.io SDK
- Identify deprecated APIs that Customer.io might use
- Check for behavioral changes in core functionality

#### **3. Compatibility Matrix**
- Android SDK version compatibility
- Kotlin version requirements
- Gradle version compatibility
- Other dependency conflicts

#### **4. Migration Requirements**
- Code changes needed in Customer.io SDK
- Configuration updates required
- Testing focus areas
- Rollback considerations

#### **5. Risk Assessment Levels**
- **LOW**: Patch updates, bug fixes, no breaking changes
- **MEDIUM**: Minor updates, new features, potential compatibility issues
- **HIGH**: Major updates, breaking changes, significant API modifications

## 🤖 **Command Generation Guidelines**

### **Command Templates for AI Agents**
AI agents should generate contextual commands based on detected dependencies:

```javascript
// Example: Generate commands based on dependency changes
if (analysis.includes('Firebase')) {
  commands.push('@claude how does this Firebase update affect push notifications in Customer.io SDK?');
}
if (analysis.includes('Compose')) {
  commands.push('@claude what Compose changes might break our in-app messaging UI?');
}
```

📖 **Complete command reference**: [CLAUDE_COMMANDS.md](CLAUDE_COMMANDS.md)

## 📊 **Decision Matrix**

### **Recommendation Mapping**
| Risk Level | Recommendation | Action Required |
|------------|---------------|-----------------|
| **LOW** | `APPROVE` | Auto-merge after CI passes |
| **MEDIUM** | `MANUAL_REVIEW` | Review specific areas, focused testing |
| **HIGH** | `BLOCK` | Comprehensive analysis, extensive testing |

### **Risk Factors**
- **Major version updates** → HIGH risk
- **Breaking changes in changelog** → MEDIUM/HIGH risk  
- **Core dependency updates** (Kotlin, AGP, Firebase) → MEDIUM risk
- **Patch/bug fix updates** → LOW risk
- **Sample app only dependencies** → LOW risk

## 🎯 **Usage Guidelines**

### **For AI Agents**
1. **Parse dependency changes** using the mapping configuration
2. **Fetch changelogs** from provided URLs
3. **Analyze Customer.io impact** using the framework
4. **Generate targeted commands** based on affected components
5. **Provide risk assessment** using the decision matrix

### **For Developers**
1. **Review AI analysis** for accuracy and completeness
2. **Use suggested commands** for deeper investigation
3. **Focus testing** on highlighted impact areas
4. **Make informed decisions** based on risk assessment

## 🔄 **Configuration Updates**

When adding new dependencies or changing architecture:

1. **Update dependency mapping** with new entries
2. **Add Customer.io impact description** for new components
3. **Create component-specific commands** for new areas
4. **Update risk factors** if needed
5. **Test AI analysis** with sample dependency updates

---

*This configuration ensures consistent, comprehensive dependency analysis across all AI agents working with the Customer.io Android SDK.* 