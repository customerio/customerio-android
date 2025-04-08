# 🛠️ Troubleshooting

This document covers common local development issues and how to resolve them.

## 🐘 Gradle CLI Fails Due to JDK 21

When running Gradle tasks outside Android Studio, errors may occur if the CLI uses a different JDK version than what's required by the project.

Recent versions of Android Studio (e.g. **Meerkat**) use **JDK 21** by default for Gradle. However, the project requires **JDK 17** due to compatibility constraints with [project's current Kotlin version](../buildSrc/src/main/kotlin/io.customer/android/Versions.kt#L27).

If the CLI uses JDK 21 (e.g. via system default), commit hooks may fail due to incompatibility with required JDK 17 as the [hook runs a Gradle task](../lefthook.yml#L6) to verify API changes in code files.

### Resolution Steps

**1. Set Gradle JDK to 17 in Android Studio**

Navigate to:

- Preferences -> Build, Execution, Deployment -> Build Tools -> Gradle
- Set Gradle JDK to JDK 17 version (e.g. `jbr-17`)
- Apply changes and sync project

For step-by-step instructions, [refer to this answer](https://stackoverflow.com/a/79049864/1771663).

**2. Configure CLI to use JDK 17**

To avoid Gradle failures in CLI, ensure that **JDK 17** is used consistently during local development. This can be done by updating `JAVA_HOME` environment variable, either temporarily (for current session only) or permanently (via shell profile).

**Option 1: Use a specific JDK path or dynamic lookup**

Choose either of the following, depending on preference:

- Set a known JDK 17 path (use `/usr/libexec/java_home -V` to list installed versions):

```bash
export JAVA_HOME="/Library/Java/JavaVirtualMachines/jbr-17.0.14.jdk/Contents/Home"
export PATH="$JAVA_HOME/bin:$PATH"
```

- Or use dynamic lookup to always pick latest installed JDK 17:

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
export PATH="$JAVA_HOME/bin:$PATH"
```

These commands can be:
- **Temporary:** Run directly in the terminal for one session
- **Permanent:** Add to shell profile like `~/.zshrc` or `~/.bash_profile` _- Restart the terminal or run `source ~/.zshrc` (or appropriate file) to apply the changes_

**Option 2: Use a Java version manager**

Tools like [jenv](https://github.com/jenv/jenv) can simplify managing multiple JDK versions across projects. This document doesn't cover setup details, but can be worth considering for long-term convenience.

**3. Verify the JDK version used by Gradle**

Confirm that the CLI uses JDK 17 by running:
```bash
./gradlew -version
```

Expected output:
```
JVM: 17.x.x
```

### Notes

- This guide is written for macOS. Commands and paths may differ on other operating systems.
- These are one-time setup steps per machine.
- This setup ensures consistent behavior between Android Studio and CLI builds and avoids version mismatch issues before pushing updates.
- Unused JDKs can be removed from `/Library/Java/JavaVirtualMachines` if needed.
