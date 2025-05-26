# Claude Dependency Analysis Setup

This guide shows how to configure Claude for automated dependency analysis in the Customer.io Android SDK.

## 🎯 **What This Provides**

Claude integration automatically:
- **Analyzes dependency PRs** when they're opened (Dependabot PRs)
- **Responds to @claude mentions** in PR comments
- **Examines actual code** and build files with repository context
- **Runs build commands** to verify compatibility
- **Provides specific insights** for Customer.io SDK functionality

## 🚀 **Setup Requirements**

### **Prerequisites**
- Customer.io GitHub App with Claude integration
- Google Cloud Vertex AI access
- Self-hosted GitHub runners (for security)

### **Configuration Files**
- `.github/workflows/claude.yml` - Claude workflow configuration
- System prompt with Customer.io SDK context

## 🤖 **How It Works**

### **Automatic Triggering**
- **Dependency PRs**: Claude automatically analyzes when Dependabot creates PRs
- **Manual Triggering**: Use `@claude` in PR comments for on-demand analysis
- **Interactive Analysis**: Claude can examine files, run builds, and provide specific insights

### **Claude Capabilities**
- **Code Examination**: Reads actual source files and build configurations
- **Build Verification**: Runs `./gradlew` commands to test compatibility
- **Dependency Analysis**: Searches for API usage and compatibility issues
- **Customer.io Context**: Understands SDK architecture and critical components

## 💬 **Using Claude**

### **Automatic Analysis**
Claude automatically analyzes dependency PRs when they're created. No action needed.

### **Interactive Analysis**
Use `@claude` in PR comments:

```
@claude analyze these dependency updates for Customer.io Android SDK impact
```

### **Specific Questions**
```
@claude how does this Firebase update affect push notifications?
@claude check if this Kotlin update breaks our coroutines usage
@claude run ./gradlew clean build to verify compatibility
```

## 🔧 **Configuration Details**

### **Workflow Configuration**
The Claude workflow (`.github/workflows/claude.yml`) includes:

- **Triggers**: Dependency PRs and @claude mentions
- **Permissions**: Read code, write PR comments
- **Tools**: Git, Gradle, file system access
- **Model**: Claude Sonnet 4 via Vertex AI
- **Timeout**: 15 minutes for analysis

### **System Prompt**
Claude is configured with Customer.io SDK-specific context:

```
Customer.io SDK Components:
- Push notification service (Firebase integration)
- In-app messaging system with Compose UI
- Event tracking and analytics
- Background processing and queuing
- Network requests for API communication
- Data persistence and caching
- Sample apps: Kotlin Compose and Java layouts
```

### **Available Tools**
Claude can use these commands:
- `git diff` - See what changed
- `./gradlew` - Run build commands
- `grep` - Search for API usage
- `find` and `cat` - Examine files
- `ls` - List directory contents

## 🎯 **Analysis Focus**

### **What Claude Analyzes**
1. **Breaking Changes**: API changes affecting Customer.io SDK
2. **Compatibility**: Kotlin ↔ AGP ↔ Compose version matrix
3. **Customer.io Impact**: Effects on push notifications, messaging, analytics
4. **Migration Requirements**: Code changes and testing focus
5. **Risk Assessment**: LOW/MEDIUM/HIGH with reasoning

### **Key Files Examined**
- `buildSrc/src/main/kotlin/io.customer/android/Versions.kt`
- `build.gradle` files
- Source code in `src/` directories
- Sample apps in `samples/` directory

## 🚨 **Security Considerations**

### **Self-Hosted Runners**
- Claude runs on Customer.io's self-hosted runners
- No code leaves your infrastructure
- Secure access to private repositories

### **Permissions**
- **Read access**: Repository contents and PR data
- **Write access**: PR comments only
- **No merge permissions**: Cannot auto-merge PRs

### **API Keys**
- Vertex AI authentication via workload identity
- GitHub App authentication with private keys
- No API keys stored in repository

## 🔄 **Integration with Existing Workflow**

### **Enhanced Workflow**
1. **Dependabot** creates dependency update PR
2. **Claude** automatically analyzes the changes
3. **OpenAI** (automatic) provides deep changelog analysis
4. **You** use @claude for specific questions
5. **Decision** made with full AI-powered context

### **Workflow Benefits**
- **Code-aware analysis**: Claude examines actual implementation
- **Build verification**: Tests compatibility with Gradle commands
- **Interactive investigation**: Ask follow-up questions
- **Customer.io expertise**: Understands SDK architecture

## 📊 **Example Usage**

### **Automatic Analysis**
When Dependabot creates a Firebase update PR, Claude automatically:
1. Examines the version changes
2. Checks Firebase usage in push notification code
3. Runs build verification
4. Provides impact assessment

### **Interactive Analysis**
```
@claude analyze this Compose BOM update:
1. Check compatibility with our MessageView components
2. Run ./gradlew :samples:kotlin_compose:assembleDebug
3. Look for any breaking changes in in-app messaging
```

### **Build Verification**
```
@claude verify these updates don't break the build:
1. Run ./gradlew clean test
2. Build sample apps
3. Check for any compilation errors
```

## 🛠️ **Troubleshooting**

### **Claude Not Responding**
- Check if workflow is enabled
- Verify @claude mention format
- Review GitHub Actions logs

### **Build Failures**
- Claude will report build issues
- Check Gradle compatibility
- Review dependency conflicts

### **Analysis Quality**
- Provide specific context in questions
- Reference exact files or components
- Ask follow-up questions for clarity

## 💡 **Best Practices**

### **Effective Usage**
- **Be specific**: Ask about exact components or files
- **Provide context**: Mention Customer.io SDK functionality
- **Use build verification**: Ask Claude to test changes
- **Follow up**: Ask clarifying questions

### **Question Examples**
```
✅ "@claude check if this Retrofit update affects our API client in NetworkManager.kt"
❌ "@claude is this safe?"

✅ "@claude run ./gradlew test and check for any failures related to this Kotlin update"
❌ "@claude test this"
```

---

*Claude provides interactive, code-aware dependency analysis with the ability to examine your actual codebase and verify compatibility through build commands.* 