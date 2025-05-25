# Dependency Update Tooling

This document describes how to use the dependency update tooling setup for the Customer.io Android SDK.

## Overview

We use the [Ben-Manes gradle versions plugin](https://github.com/ben-manes/gradle-versions-plugin) to check for dependency updates across the project. This plugin provides tasks to discover which dependencies have updates available.

## Setup

The dependency update tooling is configured in `scripts/dependency-updates.gradle`. To enable it, add the following line to your root `build.gradle` file:

```gradle
apply from: "${rootDir}/scripts/dependency-updates.gradle"
```

## Usage

### Basic Dependency Check

To check for dependency updates:

```bash
./gradlew dependencyUpdates
```

This will generate a plain text report showing:
- Dependencies that are up-to-date
- Dependencies that have newer versions available
- Dependencies that exceed the latest version found
- Dependencies that failed to be resolved
- Available Gradle updates

### Report Formats

The plugin supports multiple output formats:

#### Plain Text (Default)
```bash
./gradlew dependencyUpdates
```

#### JSON Format
```bash
./gradlew dependencyUpdates -DoutputFormatter=json
```

#### XML Format
```bash
./gradlew dependencyUpdates -DoutputFormatter=xml
```

#### HTML Format
```bash
./gradlew dependencyUpdates -DoutputFormatter=html
```

#### Multiple Formats
```bash
./gradlew dependencyUpdates -DoutputFormatter=json,xml,html
```

### Revision Strategies

You can control which types of versions to consider:

#### Release Versions Only
```bash
./gradlew dependencyUpdates -Drevision=release
```

#### Milestone Versions (Default)
```bash
./gradlew dependencyUpdates -Drevision=milestone
```

#### Integration Versions (Including SNAPSHOTs)
```bash
./gradlew dependencyUpdates -Drevision=integration
```

### Refresh Dependencies

To refresh the dependency cache and fetch the latest versions:

```bash
./gradlew dependencyUpdates --refresh-dependencies
```

### Custom Tasks

The configuration includes several helpful custom tasks:

#### Generate Report with Instructions
```bash
./gradlew dependencyUpdatesReport
```

#### Generate JSON Report
```bash
./gradlew dependencyUpdatesJson
```

#### Generate HTML Report
```bash
./gradlew dependencyUpdatesHtml
```

## Configuration

The dependency update tooling is configured with the following settings:

- **Gradle Updates**: Enabled (checks for Gradle updates)
- **Release Channel**: `release-candidate` (for Gradle updates)
- **Revision Strategy**: `release` (only stable releases)
- **Output Directory**: `build/dependencyUpdates`
- **Constraints Check**: Enabled (useful for BOMs and version catalogs)
- **Unstable Version Rejection**: Enabled (filters out alpha, beta, RC versions when current version is stable)

## Understanding the Report

### Report Sections

1. **Up-to-date dependencies**: Dependencies using the latest available version
2. **Dependencies with later versions**: Dependencies that have newer versions available
3. **Dependencies exceeding latest**: Dependencies using a version newer than the latest found (e.g., SNAPSHOTs)
4. **Unresolved dependencies**: Dependencies that couldn't be resolved
5. **Gradle updates**: Available Gradle version updates

### Example Output

```
------------------------------------------------------------
: Project Dependency Updates (report to plain text file)
------------------------------------------------------------

The following dependencies are using the latest release version:
 - androidx.core:core-ktx:1.6.0
 - org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3

The following dependencies have later release versions:
 - com.squareup.retrofit2:retrofit [2.9.0 -> 2.11.0]
 - com.squareup.okhttp3:okhttp [4.11.0 -> 4.12.0]

Gradle updates:
 - Gradle: [8.3.1 -> 8.4 -> 8.5]
```

## Best Practices

1. **Regular Checks**: Run dependency updates regularly (e.g., weekly or monthly)
2. **Review Changes**: Always review changelogs before updating dependencies
3. **Test Thoroughly**: Test your application after updating dependencies
4. **Update Gradually**: Update dependencies incrementally rather than all at once
5. **Monitor Security**: Pay special attention to security-related updates

## Integration with CI/CD

You can integrate dependency checking into your CI/CD pipeline:

```yaml
# Example GitHub Actions step
- name: Check for dependency updates
  run: ./gradlew dependencyUpdates -DoutputFormatter=json
  
- name: Upload dependency report
  uses: actions/upload-artifact@v3
  with:
    name: dependency-updates
    path: build/dependencyUpdates/report.json
```

## Troubleshooting

### Common Issues

1. **Plugin not found**: Ensure the plugin is properly added to the buildscript dependencies
2. **Network issues**: Use `--refresh-dependencies` to refresh the cache
3. **Proxy issues**: Configure Gradle proxy settings if behind a corporate firewall

### Getting Help

- [Ben-Manes Plugin Documentation](https://github.com/ben-manes/gradle-versions-plugin)
- [Gradle Plugin Portal](https://plugins.gradle.org/plugin/com.github.ben-manes.versions)

## Version Information

- **Plugin Version**: 0.52.0
- **Minimum Gradle Version**: 7.0
- **Kotlin Compatibility**: 2.x (Note: Kotlin 1.x is no longer supported in plugin version 0.52.0+) 