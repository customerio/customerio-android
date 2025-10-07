package io.customer.android.apkscale

import com.google.gson.Gson
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import org.gradle.api.DefaultTask
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction

abstract class MeasureAndroidLibrarySizeTask : DefaultTask() {

    @get:Input
    abstract val abis: SetProperty<String>

    @get:Input
    abstract val humanReadable: Property<Boolean>

    @TaskAction
    fun measureAndroidLibrarySize() {
        val outputDir = project.layout.buildDirectory.dir("outputs/aar").get().asFile
        val reportDir = project.layout.buildDirectory.dir("apkscale/build/outputs/reports").get().asFile

        if (!outputDir.exists()) {
            logger.warn("No AAR files found in ${outputDir.absolutePath}")
            return
        }

        reportDir.mkdirs()

        val aarFiles = outputDir.listFiles { _, name -> name.endsWith(".aar") }
        if (aarFiles.isNullOrEmpty()) {
            logger.warn("No AAR files found")
            return
        }

        val reports = mutableListOf<ApkscaleReport>()

        for (aarFile in aarFiles) {
            logger.info("Measuring size for ${aarFile.name}")
            val report = measureLibrarySize(aarFile)
            reports.add(report)
        }

        val reportFile = File(reportDir, "apkscale.json")
        val gson = Gson()
        reportFile.writeText(gson.toJson(reports))

        logger.info("Size report generated: ${reportFile.absolutePath}")
    }

    private fun measureLibrarySize(aarFile: File): ApkscaleReport {
        val tempDir = Files.createTempDirectory("apkscale_${aarFile.nameWithoutExtension}").toFile()

        try {
            val projectDir = createMockProject(tempDir, aarFile)
            val sizeMap = buildAndMeasure(projectDir)

            return ApkscaleReport(
                library = aarFile.nameWithoutExtension,
                size = sizeMap
            )
        } catch (e: Exception) {
            logger.error("Failed to measure size for ${aarFile.name}: ${e.message}")
            return ApkscaleReport(
                library = aarFile.nameWithoutExtension,
                size = mapOf("error" to "Failed to measure: ${e.message}")
            )
        } finally {
            tempDir.deleteRecursively()
        }
    }

    private fun createMockProject(tempDir: File, aarFile: File): File {
        val projectDir = File(tempDir, "mock-project")
        projectDir.mkdirs()

        // Create minimal Android project structure
        File(projectDir, "src/main").mkdirs()

        // Create AndroidManifest.xml
        val manifestFile = File(projectDir, "src/main/AndroidManifest.xml")
        manifestFile.writeText(
            """
            <?xml version="1.0" encoding="utf-8"?>
            <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                package="com.apkscale.mock">
                <application />
            </manifest>
            """.trimIndent()
        )

        // Create build.gradle
        val buildGradleFile = File(projectDir, "build.gradle")
        buildGradleFile.writeText(createBuildGradleContent(aarFile))

        // Create settings.gradle
        val settingsFile = File(projectDir, "settings.gradle")
        settingsFile.writeText(
            """
            pluginManagement {
                repositories {
                    google()
                    mavenCentral()
                    gradlePluginPortal()
                }
            }
            
            rootProject.name = 'mock-project'
            """.trimIndent()
        )

        // Copy gradle wrapper from main project
        val mainProjectGradleDir = File(project.rootDir, "gradle")
        val projectGradleDir = File(projectDir, "gradle")
        if (mainProjectGradleDir.exists()) {
            mainProjectGradleDir.copyRecursively(projectGradleDir, overwrite = true)
        }

        // Copy gradlew script
        val mainProjectGradlew = File(project.rootDir, "gradlew")
        val projectGradlew = File(projectDir, "gradlew")
        if (mainProjectGradlew.exists()) {
            mainProjectGradlew.copyTo(projectGradlew, overwrite = true)
            projectGradlew.setExecutable(true)
        }

        // Create gradle.properties with AndroidX enabled
        val gradlePropertiesFile = File(projectDir, "gradle.properties")
        gradlePropertiesFile.writeText(
            """
            android.useAndroidX=true
            android.enableJetifier=true
            """.trimIndent()
        )

        return projectDir
    }

    private fun createBuildGradleContent(aarFile: File): String {
        return """
            plugins {
                id 'com.android.application' version '8.6.1'
                id 'org.jetbrains.kotlin.android' version '2.1.21'
            }

            android {
                compileSdk 35
                namespace "com.apkscale.mock"
                
                defaultConfig {
                    applicationId "com.apkscale.mock"
                    minSdk 21
                    targetSdk 34
                    versionCode 1
                    versionName "1.0"
                }
                
                buildTypes {
                    release {
                        minifyEnabled false
                    }
                }
                
                flavorDimensions "library"
                productFlavors {
                    withLibrary {
                        dimension "library"
                    }
                    withoutLibrary {
                        dimension "library"
                    }
                }
            }

            repositories {
                google()
                mavenCentral()
            }

            dependencies {
                withLibraryImplementation files('${aarFile.absolutePath}')
                implementation 'androidx.appcompat:appcompat:1.7.0'
                implementation 'androidx.core:core-ktx:1.13.1'
                implementation 'com.google.android.material:material:1.12.0'
            }
        """.trimIndent()
    }

    private fun buildAndMeasure(projectDir: File): Map<String, String> {
        val sizeMap = mutableMapOf<String, String>()

        try {
            // Build both flavors
            val withLibraryApk = buildApk(projectDir, "withLibraryRelease")
            val withoutLibraryApk = buildApk(projectDir, "withoutLibraryRelease")

            if (withLibraryApk != null && withoutLibraryApk != null) {
                val sizeDifference = withLibraryApk.length() - withoutLibraryApk.length()
                sizeMap["universal"] = if (humanReadable.get()) {
                    formatSize(sizeDifference)
                } else {
                    sizeDifference.toString()
                }
            } else {
                sizeMap["universal"] = "0"
            }
        } catch (e: Exception) {
            logger.warn("Failed to build and measure: ${e.message}")
            sizeMap["universal"] = "0"
        }

        return sizeMap
    }

    private fun buildApk(projectDir: File, task: String): File? {
        try {
            val process = ProcessBuilder()
                .directory(projectDir)
                .command("./gradlew", "assemble$task", "--stacktrace")
                .redirectErrorStream(true)
                .start()

            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor(5, TimeUnit.MINUTES)

            if (!exitCode || process.exitValue() != 0) {
                logger.warn("Build failed for task $task")
                logger.warn("Build output: $output")
                return null
            }

            // Extract flavor name from task (e.g., "withLibraryRelease" -> "withLibrary")
            val flavorName = task.replaceFirst("Release$".toRegex(), "")
            val apkDir = File(projectDir, "build/outputs/apk/$flavorName/release")
            if (!apkDir.exists()) {
                logger.warn("APK directory does not exist: ${apkDir.absolutePath}")
                return null
            }

            val apkFiles = apkDir.listFiles { _, name -> name.endsWith(".apk") }
            if (apkFiles.isNullOrEmpty()) {
                logger.warn("No APK files found in ${apkDir.absolutePath}")
                return null
            }

            return apkFiles.firstOrNull()
        } catch (e: Exception) {
            logger.warn("Exception during build: ${e.message}")
            return null
        }
    }

    private fun formatSize(bytes: Long): String {
        val kb = bytes / 1024.0
        val mb = kb / 1024.0

        return when {
            mb >= 1.0 -> String.format("%.2f MB", mb)
            kb >= 1.0 -> String.format("%.2f KB", kb)
            else -> "$bytes B"
        }
    }
}
