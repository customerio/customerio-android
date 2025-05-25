# Dependency Updates

This document describes the dependency update system for the Customer.io Android SDK.

## Overview

The Customer.io Android SDK uses **Renovate** for automated dependency management. Renovate automatically:

1. **Detects all dependency updates** including BOM-managed dependencies
2. **Applies patch and minor updates** automatically after verification
3. **Reports major updates** for manual review via Dependency Dashboard
4. **Runs comprehensive tests** to verify compatibility
5. **Creates focused pull requests** with detailed information

## 🤖 Automated Updates

### How It Works
- **Patch updates** (1.2.3 → 1.2.4): Bug fixes, security patches - **auto-merged**
- **Minor updates** (1.2.3 → 1.3.0): New features, backward compatible - **auto-merged**
- **Major updates** (1.2.3 → 2.0.0): Breaking changes - **manual review required**

### Schedule
- **Automatic**: Every Monday at 9 AM UTC
- **Security updates**: Immediate (when vulnerabilities are detected)
- **Manual trigger**: Available via Dependency Dashboard

### Verification Process
Before any update is merged, Renovate automatically:
1. ✅ Runs clean build
2. ✅ Executes unit tests
3. ✅ Builds sample apps (`kotlin_compose` and `java_layout`)
4. ✅ Verifies no breaking changes

## 📊 Dependency Dashboard

Renovate provides a **Dependency Dashboard** issue that shows:
- 📦 Available updates
- ⚠️ Major updates requiring review
- 🔒 Security vulnerabilities
- 📈 Update status and progress

## 🎯 Supported Dependencies

Renovate automatically detects and updates:

### ✅ **Fully Supported**
- **Gradle dependencies** in `build.gradle` files
- **Version constants** in `Versions.kt`
- **BOM-managed dependencies** (Compose, AndroidX)
- **Plugin versions** in buildscript
- **Kotlin and Android Gradle Plugin**

### 🔧 **Grouped Updates**
- **Compose BOM**: All Compose dependencies updated together
- **Kotlin**: Kotlin compiler, stdlib, coroutines
- **Android Gradle Plugin**: Coordinated with Gradle wrapper

## 🛠️ Manual Operations

### Check for Updates Manually
Visit the **Dependency Dashboard** issue in your repository to:
- See all available updates
- Trigger updates manually
- Review major updates

### Override Auto-merge
If you need to review a patch/minor update:
1. Add `renovate:ignore` label to the PR
2. Review and merge manually

### Emergency Updates
For critical security updates:
1. Renovate will create immediate PRs
2. These bypass normal scheduling
3. Auto-merge after verification

## 🔧 Configuration

The Renovate configuration is in `renovate.json` at the repository root. Key settings:

```json
{
  "schedule": ["before 10am on monday"],
  "automerge": true,  // For patch/minor updates
  "dependencyDashboard": true,
  "postUpgradeTasks": {
    "commands": [
      "./gradlew clean test",
      "./gradlew :samples:kotlin_compose:assembleDebug",
      "./gradlew :samples:java_layout:assembleDebug"
    ]
  }
}
```

## 🆚 Advantages Over Previous System

| Feature | Previous (Ben-Manes) | Current (Renovate) |
|---------|---------------------|-------------------|
| **BOM Support** | ❌ Limited | ✅ Native |
| **Dependency Coverage** | ~43 dependencies | ✅ All dependencies |
| **Update Granularity** | Single PR | ✅ Grouped or individual |
| **Security Updates** | ❌ Manual | ✅ Automatic |
| **Dependency Dashboard** | ❌ None | ✅ Visual overview |
| **Merge Confidence** | ❌ None | ✅ Adoption data |

## 🚨 Troubleshooting

### Update Not Detected
- Check if dependency is in a supported file format
- Verify repository access for private dependencies
- Check Dependency Dashboard for any errors

### Build Failures
- Renovate will automatically close PRs that fail verification
- Check the PR for build logs and error details
- Major updates may require manual intervention

### Need Help?
- Check the **Dependency Dashboard** issue
- Review Renovate logs in failed PRs
- Consult [Renovate documentation](https://docs.renovatebot.com/)

---

*Renovate automatically maintains this dependency update system. No manual intervention required for most updates.* 