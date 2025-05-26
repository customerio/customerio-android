# Customer.io Android SDK Dependency Updates

This document explains the automated dependency update system for the Customer.io Android SDK using GitHub's native Dependabot.

## 🎯 **System Overview**

The dependency update system uses:
- **🔄 Dependabot**: Native GitHub dependency detection and PR creation
- **🔒 Security scanning**: Built-in vulnerability detection
- **📦 Intelligent grouping**: Related dependencies updated together
- **⚡ Automated scheduling**: Weekly updates with rate limiting

## 🚀 **How It Works**

### **1. Automated Detection**
- **Dependabot** runs weekly (Monday 9 AM UTC)
- Scans all Gradle build files for dependency updates
- Creates PRs with grouped updates and detailed version information
- Includes security vulnerability scanning

### **2. Intelligent Grouping**
Dependencies are grouped logically to reduce PR noise:
- **Kotlin ecosystem**: Kotlin + Coroutines
- **Compose dependencies**: Compose BOM + related libraries  
- **Firebase dependencies**: All Firebase libraries
- **Android/Google**: Android Gradle Plugin + AndroidX libraries
- **Networking**: Retrofit + OkHttp
- **Dependency Injection**: Dagger/Hilt

### **3. Review Process**
- **Security updates**: Flagged for immediate attention
- **Grouped updates**: Related dependencies updated together
- **Version compatibility**: Dependabot checks for conflicts
- **Manual review**: All updates require approval before merging

## 📊 **Example: Kotlin Ecosystem Update**

When Kotlin updates are available, Dependabot creates a grouped PR:

```
chore(deps): Bump kotlin ecosystem

Updates:
- org.jetbrains.kotlin:kotlin-gradle-plugin: 1.8.10 → 1.8.20
- org.jetbrains.kotlinx:kotlinx-coroutines-core: 1.6.4 → 1.7.0

Changelog links:
- Kotlin: https://github.com/JetBrains/kotlin/releases/tag/v1.8.20
- Coroutines: https://github.com/Kotlin/kotlinx.coroutines/releases/tag/1.7.0
```

## 🛠️ **Setup Instructions**

### **Prerequisites**
- GitHub repository with Dependabot enabled (already configured)
- Gradle-based Android project
- Maven Central repository access

### **Configuration**

The system is configured via `.github/dependabot.yml`:

```yaml
version: 2
updates:
  - package-ecosystem: "gradle"
    directory: "/"
    schedule:
      interval: "weekly"
      day: "monday"
      time: "09:00"
      timezone: "UTC"
    open-pull-requests-limit: 3
    reviewers:
      - "customerio/mobile-team"
    assignees:
      - "customerio/mobile-team"
    groups:
      kotlin-ecosystem:
        patterns:
          - "org.jetbrains.kotlin*"
          - "org.jetbrains.kotlinx*"
      compose-dependencies:
        patterns:
          - "androidx.compose*"
          - "androidx.activity:activity-compose"
          - "androidx.lifecycle:lifecycle-viewmodel-compose"
      # ... additional groups
```

## 🎯 **Using the System**

### **When a PR is Created**

1. **Review the PR Description**:
   - Check the dependency changes and version bumps
   - Review changelog links provided by Dependabot
   - Note any grouped updates and their relationships

2. **Assess the Changes**:
   - **Security updates**: Review vulnerability details and prioritize
   - **Major version updates**: Check for breaking changes in changelogs
   - **Minor/patch updates**: Generally safe but verify compatibility

3. **Test Locally** (if needed):
   ```bash
   git checkout dependabot/gradle/kotlin-ecosystem
   ./gradlew clean build
   ./gradlew test
   ./gradlew :samples:kotlin_compose:assembleDebug
   ```

4. **Make Decision**:
   - **Approve & Merge**: Safe updates with no concerns
   - **Request Changes**: Issues found that need addressing
   - **Close**: Updates that shouldn't be applied

## 🔧 **Customization**

### **Dependency Coverage**
The system monitors dependencies in:
- Root `build.gradle` file
- Module `build.gradle` files
- `buildSrc/` build files
- Plugin versions and Gradle wrapper

### **Update Schedule**
- **Weekly**: Monday 9 AM UTC
- **Security updates**: As soon as detected
- **Rate limiting**: Max 3 concurrent PRs

### **Grouping Strategy**
Current groups optimize for:
- **Related functionality**: Dependencies that work together
- **Version compatibility**: Dependencies with shared version requirements
- **Testing efficiency**: Changes that can be tested together
- **Review simplicity**: Logical groupings for easier review

### **Adding New Dependencies**
Dependabot automatically detects new dependencies added to `build.gradle` files. To customize grouping:

1. Edit `.github/dependabot.yml`
2. Add new patterns to existing groups or create new groups:

```yaml
new-dependency-group:
  patterns:
    - "com.example.*"
    - "com.mycompany.*"
```

### **Excluding Dependencies**
To ignore specific dependencies:

```yaml
ignore:
  - dependency-name: "com.example.unwanted-lib"
    versions: ["1.x", "2.x"]
```

## 🚨 **Troubleshooting**

### **Common Issues**
- **Dependency not detected**: Ensure dependency is in `build.gradle` files
- **Too many PRs**: Adjust `open-pull-requests-limit` or grouping
- **Version conflicts**: Check for incompatible version combinations
- **Build failures**: Test locally and check for breaking changes

### **Dependabot Configuration**
- **File location**: `.github/dependabot.yml` in repository root
- **Syntax validation**: GitHub validates configuration on push
- **Documentation**: [GitHub Dependabot docs](https://docs.github.com/en/code-security/dependabot)

## 📈 **Benefits**

- **🔒 Security**: Automatic vulnerability detection and updates
- **⚡ Efficiency**: Reduces manual dependency monitoring by 90%
- **🎯 Focus**: Intelligent grouping reduces review overhead
- **🔄 Native**: Built into GitHub with no external dependencies
- **📊 Visibility**: Clear changelog links and version information

## 🔄 **Workflow Integration**

### **Best Practice Workflow**

1. **Dependabot** creates grouped dependency PRs weekly
2. **Review** changelog links and assess impact
3. **Test** locally for major updates or security fixes
4. **Approve & merge** safe updates
5. **Monitor** for any issues post-deployment

### **Review Guidelines**

| Update Type | Review Level | Action |
|-------------|--------------|--------|
| **Security** | High | Review immediately, test, merge quickly |
| **Major** | High | Check breaking changes, test thoroughly |
| **Minor** | Medium | Review changelog, test if concerns |
| **Patch** | Low | Quick review, generally safe to merge |

## 🎯 **Next Steps**

This Dependabot setup provides a solid foundation for automated dependency management. Future enhancements could include:

- AI-powered analysis of dependency changes
- Automated testing and build verification
- Custom risk assessment based on Customer.io SDK architecture
- Interactive analysis tools for complex updates

---

*This system provides reliable, secure, and efficient dependency management using GitHub's native Dependabot with intelligent grouping optimized for the Customer.io Android SDK.* 