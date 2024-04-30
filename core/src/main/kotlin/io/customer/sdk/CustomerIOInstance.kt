// TODO: Move this class to the correct package.
// We need to move this class to the right package to avoid breaking imports for the users of the SDK.
// We have placed the class in the wrong package for now to avoid breaking the build.
// Once old implementations are removed, we can move the class to the correct package.
package io.customer.sdk.android

/**
 * Allows mocking of [CustomerIO] for automated tests in the project.
 * Mock the implementation of this interface to test the behavior of the SDK without actually calling the SDK.
 */
interface CustomerIOInstance
