package io.customer.sdk.core.di

import android.content.Context
import io.customer.sdk.insights.DiagnosticsRecorder

/**
 * The file contains extension functions for the SDKComponent object and its dependencies.
 */

/**
 * Create and register an instance of AndroidSDKComponent with the provided context,
 * only if it is not already initialized.
 * This function should be called from all entry points of the SDK to ensure that
 * AndroidSDKComponent is initialized before accessing any of its dependencies.
 */
fun SDKComponent.setupAndroidComponent(
    context: Context
) = registerDependency<AndroidSDKComponent> {
    AndroidSDKComponentImpl(context)
}

/**
 * Register the diagnostics instance with SDKComponent.
 * This function should be called by DiagnosticsBridge when diagnostics is initialized
 * (i.e., when both local opt-in and server-side sampleRate conditions are met).
 *
 * Once registered, diagnostics can be accessed globally via `SDKComponent.diagnostics`
 * or the convenience object `Diagnostics`.
 */
fun SDKComponent.registerDiagnostics(
    diagnostics: DiagnosticsRecorder
) = registerDependency<DiagnosticsRecorder> { diagnostics }
