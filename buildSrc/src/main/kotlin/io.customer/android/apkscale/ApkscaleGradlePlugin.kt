package io.customer.android.apkscale

import com.android.build.gradle.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project

class ApkscaleGradlePlugin : Plugin<Project> {

    override fun apply(project: Project) {
        // Ensure this is an Android library project
        val androidExtension = project.extensions.findByType(LibraryExtension::class.java)
            ?: throw IllegalStateException("apkscale plugin can only be applied to Android library projects")

        // Create extension
        val extension = project.extensions.create("apkscale", ApkscaleExtension::class.java)

        // Register the measureSize task
        val measureTask = project.tasks.register("measureSize", MeasureAndroidLibrarySizeTask::class.java)
        measureTask.configure {
            group = "verification"
            description = "Measures the size impact of this Android library"

            // Configure task inputs from extension - evaluate lazily
            abis.set(project.provider { extension.abis })
            humanReadable.set(project.provider { extension.humanReadable })

            // Make it depend on assembleRelease to ensure AAR is built
            dependsOn("assembleRelease")
        }
    }
}
