# Claude Dependency Analysis Commands Reference

Quick reference guide for analyzing dependency updates with Claude in GitHub PRs and comments.

## 🎯 **How to Use Claude**

### **Automatic Analysis**
Claude automatically analyzes dependency PRs (Dependabot PRs) when they're created.

### **Manual Analysis**
Use `@claude` in PR comments to trigger interactive analysis:

```
@claude analyze these dependency updates for Customer.io Android SDK impact
```

## 🔍 **Essential Commands**

### **General Analysis**
```
@claude analyze these dependency updates for Customer.io Android SDK impact
```

### **Specific Impact Questions**
```
@claude how does this Firebase update affect push notifications in Customer.io SDK?

@claude what Compose changes might break our in-app messaging UI?

@claude analyze Kotlin compatibility with our coroutines usage

@claude check if this Retrofit update affects our API communication
```

### **Risk Assessment**
```
@claude assess risk level of these updates for Customer.io SDK:
- Push notification delivery (Firebase)
- In-app messaging UI (Compose)
- Event tracking and analytics
- Background processing

Rate each as LOW/MEDIUM/HIGH and explain why.
```

## 🔧 **Advanced Analysis**

### **File-Specific Analysis**
```
@claude examine buildSrc/src/main/kotlin/io.customer/android/Versions.kt and explain these version changes

@claude check how this Firebase update affects CustomerIOPushMessaging.kt

@claude analyze build.gradle changes and their impact on the SDK
```

### **Breaking Changes Detection**
```
@claude find any breaking changes in these dependency updates that might affect Customer.io SDK

@claude check for deprecated APIs we might be using that are affected by these updates

@claude analyze compatibility between Kotlin, AGP, and Compose versions
```

### **Comprehensive Analysis Template**
```
@claude analyze these dependency updates for Customer.io Android SDK:

Context: SDK provides push notifications (Firebase), in-app messaging (Compose), 
event tracking, with Kotlin Compose and Java layout sample apps.

Please analyze:
1. Customer.io specific impact for each dependency
2. Breaking changes affecting our SDK
3. Compatibility matrix (Kotlin ↔ AGP ↔ Compose)
4. Required code changes and testing focus
5. Risk assessment (LOW/MEDIUM/HIGH)
6. Migration timeline and sequence

Focus on: [paste specific dependency changes here]
```

## 🧪 **Testing Focus**

### **Test Strategy Questions**
```
@claude what specific tests should I run to verify this Firebase update doesn't break push notifications?

@claude suggest testing strategy for these Compose BOM changes affecting in-app messaging

@claude what edge cases should I test for this Kotlin coroutines update?

@claude check if our sample apps will still compile with these updates
```

### **Build Verification**
```
@claude run ./gradlew clean build to verify these updates don't break the build

@claude check if sample apps compile: ./gradlew :samples:kotlin_compose:assembleDebug

@claude verify unit tests still pass: ./gradlew test
```

## 🔍 **Migration Planning**

### **Code Changes**
```
@claude what code changes are needed in Customer.io SDK for these updates?

@claude identify files that need updates for these dependency changes

@claude suggest migration sequence for these dependency updates

@claude check for any ProGuard rule updates needed
```

### **Configuration Updates**
```
@claude what build.gradle changes are needed for these updates?

@claude check if any Gradle plugin compatibility issues exist

@claude verify Android SDK version compatibility
```

## 💡 **Best Practices**

### **Effective Prompting**
- **Provide context**: Always mention Customer.io SDK functionality
- **Be specific**: Ask about exact impact rather than general safety
- **Reference files**: Mention specific files for targeted analysis
- **Follow up**: Ask for specific testing recommendations

### **Context Template**
```
Context: Customer.io Android SDK with:
- Push notifications via Firebase
- In-app messaging with Compose UI
- Event tracking and analytics
- Background processing and queuing
- Kotlin Compose + Java layout sample apps
```

### **Question Patterns**
```
✅ "How does this Firebase update affect FCM token management in Customer.io SDK?"
❌ "Is this update safe?"

✅ "What Compose BOM changes might break our MessageView component?"
❌ "Will this work?"

✅ "Analyze Kotlin 1.9 compatibility with our coroutines usage in EventProcessor"
❌ "Should I update Kotlin?"
```

## 🚀 **Quick Reference**

### **Common Dependencies**
| Dependency | Key Impact Areas | Claude Command |
|------------|------------------|----------------|
| **Firebase** | Push notifications, FCM tokens | `@claude how does this Firebase update affect push notifications?` |
| **Compose BOM** | In-app messaging UI | `@claude what Compose changes might break our in-app messaging?` |
| **Kotlin** | Coroutines, language features | `@claude analyze Kotlin compatibility with our coroutines usage` |
| **AGP** | Build configuration | `@claude check Android Gradle Plugin compatibility with our build` |
| **Retrofit** | API communication | `@claude analyze Retrofit changes affecting our API calls` |

### **Workflow Integration**
1. **Dependabot creates dependency PR**
2. **Claude automatically analyzes** (or use `@claude` to trigger)
3. **Use commands from this guide** for deeper analysis
4. **Apply suggestions** and make informed decisions

### **Claude Capabilities**
- **Examines actual code** and build files
- **Runs build commands** to verify compatibility
- **Searches for API usage** with grep/find
- **Provides specific file analysis** with detailed insights
- **Tests build compatibility** with Gradle commands

---

*Claude provides interactive, code-aware analysis with the ability to examine your actual codebase and run build verification commands.* 