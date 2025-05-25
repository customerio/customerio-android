# Dependency Updates

This document describes the dependency update system for the Customer.io Android SDK.

## Overview

The dependency update system automatically:
1. **Detects patch and minor updates** for dependencies in `Versions.kt`
2. **Applies safe updates** automatically
3. **Runs tests and builds sample apps** to verify everything works
4. **Creates a pull request** with the changes
5. **Reports major updates** for manual review

## 🤖 Automated Updates

### How It Works
- **Patch updates** (1.2.3 → 1.2.4): Bug fixes, security patches
- **Minor updates** (1.2.3 → 1.3.0): New features, backward compatible
- **Major updates** (1.2.3 → 2.0.0): Breaking changes - **manual review required**

### Schedule
- **Automatic**: Every Monday at 9 AM UTC
- **Manual**: Trigger anytime via GitHub Actions → "Dependency Updates"

### Verification Process
Before creating a PR, the system:
1. ✅ Runs clean build
2. ✅ Executes unit tests  
3. ✅ Compiles both sample apps (`kotlin_compose` and `java_layout`)

## 🔧 Manual Usage

### Check for Updates
```bash
# Generate dependency report
./gradlew dependencyUpdates
```

### Apply Updates Manually
```bash
# Parse and apply updates
node scripts/dependency-parser.ts
```

### Different Report Formats
```bash
# JSON format (used by automation)
./gradlew dependencyUpdates -DoutputFormatter=json

# HTML format (human-readable)
./gradlew dependencyUpdates -DoutputFormatter=html
```

## 📦 What Gets Updated

The system updates any dependency with a version constant in `Versions.kt`:

- **Android & Kotlin**: AGP, Kotlin, Coroutines
- **AndroidX**: Core KTX, AppCompat, Lifecycle, etc.
- **Third-party**: Retrofit, OkHttp, Hilt, Firebase, etc.
- **Testing**: JUnit, Mockito, Robolectric, etc.

### Adding New Dependencies
To enable automatic updates for a new dependency:

1. **Add version constant** to `Versions.kt`:
   ```kotlin
   internal const val NEW_LIBRARY = "1.0.0"
   ```

2. **Use the constant** in your build files:
   ```kotlin
   implementation("com.example:new-library:${Versions.NEW_LIBRARY}")
   ```

3. **That's it!** The next workflow run will detect and update it.

## 📁 Files Involved

- **Workflow**: `.github/workflows/dependency-updates.yml`
- **Parser**: `scripts/dependency-parser.ts`
- **Config**: `scripts/dependency-updates.gradle`
- **Versions**: `buildSrc/src/main/kotlin/io.customer/android/Versions.kt`

## ⚠️ Limitations

- Only updates dependencies with version constants in `Versions.kt`
- Major updates require manual review and testing
- Android SDK versions (compileSdk, targetSdk) are not automatically updated

## 🔒 Security

- Only updates dependencies already trusted in your `Versions.kt`
- All changes are verified through build and test process
- Pull requests allow for code review before merging

## 🛠️ Troubleshooting

### Common Issues
1. **No updates found**: Check if dependencies are defined in `Versions.kt`
2. **Build failures**: Review the PR for breaking changes
3. **Network issues**: Use `./gradlew dependencyUpdates --refresh-dependencies`

### Getting Help
- [Ben-Manes Plugin Documentation](https://github.com/ben-manes/gradle-versions-plugin)
- Check GitHub Actions logs for detailed error messages 