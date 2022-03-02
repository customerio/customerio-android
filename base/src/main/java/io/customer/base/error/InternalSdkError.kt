package io.customer.base.error

/**
 * Internal error of the SDK. A state of the SDK that should not happen. If you encounter one of these errors, please report it to Customer.io
 */
class InternalSdkError(message: String) : Throwable("Internal SDK error. Please report error to Customer.io support. Message: $message")
