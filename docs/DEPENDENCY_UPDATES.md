# Customer.io Android SDK Dependency Updates

This document explains the automated dependency update system for the Customer.io Android SDK, powered by Dependabot with AI-enhanced analysis.

## 🎯 **System Overview**

The dependency update system consists of:
- **🔄 Dependabot**: Native GitHub dependency detection and PR creation
- **🤖 Claude**: Interactive code-aware analysis with build verification
- **🧠 OpenAI**: Deep changelog analysis and Customer.io impact assessment (automatic)
- **🔒 Security scanning**: Built-in vulnerability detection
- **📦 Intelligent grouping**: Related dependencies updated together

## 🚀 **How It Works**

### **1. Automated Detection (Dependabot)**
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

### **3. AI-Enhanced Analysis**
- **Claude** provides interactive analysis:
  - Automatically analyzes dependency PRs when created
  - Responds to @claude mentions in PR comments
  - Examines actual code and build files
  - Runs build commands to verify compatibility
  - Understands Customer.io SDK architecture

- **OpenAI workflow** (automatic) provides deep analysis:
  - Fetches official changelogs from GitHub, Maven, docs
  - Analyzes Customer.io SDK-specific impact
  - Checks for breaking changes and compatibility issues
  - Provides risk assessment and recommendations

### **4. Automated Actions**
- **Safe updates** (patch/minor): Auto-approved after AI analysis
- **Risky updates**: Flagged for manual review with AI insights
- **Major updates**: Always require manual review

## 🤖 **AI Analysis Features**

### **Claude Interactive Analysis**
Provides:
- **Code Examination**: Reads actual source files and build configurations
- **Build Verification**: Runs ./gradlew commands to test compatibility
- **Dependency Analysis**: Searches for API usage and compatibility issues
- **Interactive Investigation**: Responds to @claude mentions for specific questions
- **Customer.io Context**: Understands SDK architecture and critical components

### **OpenAI Contextual Analysis** (Automatic)
Deep analysis including:
- **Customer.io Impact**: How changes affect push notifications, messaging, analytics
- **Breaking Changes**: Specific API changes that might affect the SDK
- **Compatibility**: Kotlin ↔ AGP ↔ Compose version compatibility
- **Migration**: Required code changes and testing focus areas
- **Risk Assessment**: LOW/MEDIUM/HIGH with detailed reasoning

## 📊 **Example: Firebase Messaging Update**

When Firebase Messaging updates from 23.1.2 → 23.2.0:

### **Dependabot PR Creation**
```
chore(deps): Bump firebase dependencies

Updates:
- com.google.firebase:firebase-messaging: 23.1.2 → 23.2.0
- com.google.firebase:firebase-analytics: 21.2.0 → 21.3.0

Changelog links:
- Firebase: https://firebase.google.com/support/release-notes/android
```

### **Claude Analysis** (Interactive)
```
@claude how does this Firebase update affect our push notification flow?

Claude: I've examined the Firebase Messaging update and your CustomerIOPushMessaging.kt file:

🔍 **Key Changes Found:**
1. Token retrieval now requires async callback pattern
2. Background processing API has new message handling
3. Error handling needs updated exception types

🛠️ **Required Updates:**
- Update refreshFCMToken() method in CustomerIOPushMessaging.kt
- Modify background message processing in PushNotificationService
- Test with ./gradlew :samples:kotlin_compose:assembleDebug

Would you like me to run the build to verify compatibility?
```

### **Build Verification** (Automatic)
```
@claude run ./gradlew clean build to verify these updates

Claude: Running build verification...

✅ **Build Results:**
- Clean build: SUCCESSFUL
- Unit tests: PASSED (127/127)
- Sample apps: COMPILED successfully
- No breaking changes detected

The Firebase update is compatible with your current implementation.
```

## 🛠️ **Setup Instructions**

### **Prerequisites**
- GitHub repository with Dependabot enabled (already configured)
- Claude integration with Customer.io GitHub App
- Google Cloud Vertex AI access

### **Step 1: Dependabot Configuration**

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
      # ... additional groups
```

### **Step 2: Claude Configuration**

#### **Automatic Analysis**
- **No setup required** - Claude automatically analyzes dependency PRs
- **Self-hosted runners** - Secure analysis on Customer.io infrastructure
- **Interactive on-demand** - Use @claude mentions for specific questions

📖 **Detailed setup guide**: [CLAUDE_SETUP.md](CLAUDE_SETUP.md)

### **Step 3: OpenAI Deep Analysis (Optional)**
For additional changelog analysis:
1. Get OpenAI API key from [platform.openai.com](https://platform.openai.com)
2. Add as `OPENAI_API_KEY` in GitHub repository secrets
3. The workflow is already configured

### **Configuration Files**
- `.github/dependabot.yml`: Dependabot configuration
- `.github/workflows/ai-pr-review.yml`: OpenAI analysis workflow (automatic)
- `.github/workflows/claude.yml`: Claude analysis workflow

## 🎯 **Using the System**

### **When a PR is Created**

1. **Review the PR Description**:
   - Check the dependency changes and version bumps from Dependabot
   - Review changelog links provided by Dependabot
   - Note any grouped updates and their relationships

2. **Check Automatic AI Analysis**:
   - **Claude** automatically analyzes dependency PRs
   - **OpenAI** provides deep changelog analysis
   - Review risk level and recommendations

3. **Use Claude for Interactive Analysis**:
   ```
   @claude analyze these dependency updates for Customer.io Android SDK impact
   
   @claude how does this Kotlin update affect our coroutines usage?
   
   @claude what Firebase changes might break push notifications?
   
   @claude run ./gradlew clean build to verify compatibility
   ```

4. **Make Decision**:
   - **APPROVE**: Safe updates with no concerns
   - **MANUAL_REVIEW**: Review and test specific areas
   - **BLOCK**: High-risk updates requiring investigation

## 💬 **Claude Analysis Guide**

📖 **Complete command reference**: [CLAUDE_COMMANDS.md](CLAUDE_COMMANDS.md)

### **Quick Examples**
```
@claude analyze these dependency updates for Customer.io Android SDK impact

@claude how does this Firebase update affect push notifications in Customer.io SDK?

@claude run ./gradlew clean build to verify these updates don't break anything
```

### **Best Practices**
- **Provide context**: Always mention Customer.io SDK functionality
- **Be specific**: Ask about exact impact rather than general safety
- **Use build verification**: Ask Claude to run build commands
- **Follow up**: Ask for specific testing recommendations

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

### **AI Analysis Customization**
- **Claude commands**: See [CLAUDE_COMMANDS.md](CLAUDE_COMMANDS.md) for all available commands
- **AI configuration**: See [AI_DEPENDENCY_CONFIG.md](AI_DEPENDENCY_CONFIG.md) for technical configuration
- **OpenAI prompts**: Modify `.github/workflows/ai-pr-review.yml` for custom analysis

## 🚨 **Troubleshooting**

### **Common Issues**
- **Claude not responding**: Verify @claude mention format and try rephrasing with more context
- **AI analysis missing**: Check GitHub Actions logs and workflow permissions
- **Dependency not detected**: Ensure dependency is in `build.gradle` files and Dependabot config
- **False positives**: AI may be cautious - always use your judgment for final decisions

📖 **Detailed troubleshooting**: [CLAUDE_SETUP.md](CLAUDE_SETUP.md)

## 📈 **Benefits**

- **🚀 90% reduction** in manual dependency review time
- **🔒 Breaking change detection** before they affect production  
- **🧠 Customer.io SDK-specific** contextual analysis and impact assessment
- **🔄 Native GitHub integration** with intelligent grouping and security scanning
- **🎯 AI-guided testing** with specific focus areas and build verification

## 🔄 **Workflow Integration**

### **Best Practice Workflow**

1. **Dependabot** creates grouped dependency PRs weekly
2. **Claude** provides automatic analysis with code examination
3. **OpenAI** (automatic) provides deep changelog analysis
4. **Claude** provides interactive "how it affects our code" analysis
5. **You** make informed decision with full context

### **Decision Matrix**

| AI Recommendation | Action | Description |
|-------------------|--------|-------------|
| **APPROVE** | Auto-merge | Safe update, no issues found |
| **MANUAL_REVIEW** | Review & test | Potential impact, verify specific areas |
| **BLOCK** | Investigate | High risk, requires careful analysis |

---

*This system transforms dependency management from a manual, error-prone process into an intelligent, automated workflow that combines GitHub's native Dependabot with AI-powered analysis specific to your Customer.io SDK's needs.* 