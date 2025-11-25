# Diagnostics Usage Guide

## Overview

Diagnostics provides a production debugging tool that's:
- **Independent**: Core module, no Analytics dependency
- **Easy to access**: Available globally via `SDKComponent.diagnostics`
- **Opt-in**: Requires both local config and server-side enablement
- **Safe**: No-op when disabled, minimal overhead

## Setup

### 1. Enable in SDK Configuration

```kotlin
val config = CustomerIOConfigBuilder(appContext, "your-api-key")
    .logLevel(CioLogLevel.DEBUG)
    .diagnosticsEnabled(true) // Opt-in to diagnostics
    .build()

CustomerIO.initialize(config)
```

### 2. Server Controls Activation

The server sends a `sampleRate` in settings:
- `sampleRate > 0`: Diagnostics enabled
- `sampleRate = 0`: Diagnostics disabled

## Usage

### Recording Events

From **anywhere in the SDK** (core, datapipelines, messaging modules):

```kotlin
import io.customer.sdk.insights.Diagnostics

// Simple event - super clean API!
Diagnostics.record("push_delivered", mapOf(
    "delivery_id" to "abc123",
    "campaign_name" to "welcome_campaign"
))

// With JsonObject
val data = buildJsonObject {
    put("error_code", JsonPrimitive(500))
    put("error_message", JsonPrimitive("Connection timeout"))
}
Diagnostics.record("network_error", data)

// With DiagnosticEvent object
val event = DiagnosticEvent(
    name = "metric_recorded",
    data = buildJsonObject {
        put("metric_name", JsonPrimitive("open_rate"))
        put("value", JsonPrimitive(0.85))
    },
    level = CioLogLevel.INFO
)
Diagnostics.record(event)
```

### Automatic Push Notification Diagnostics

The SDK automatically records diagnostic events for push notifications. No additional code needed!

#### Success Events

**`push_delivered`** - Recorded when a push notification is successfully delivered to the device
- `delivery_id`: Customer.io delivery ID
- `device_token`: FCM device token
- Plus auto-enriched device context (OS, model, app version, etc.)

**`push_opened`** - Recorded when a user clicks/opens a push notification
- `delivery_id`: Customer.io delivery ID
- `device_token`: FCM device token
- `has_deep_link`: Whether the push contained a deep link
- Plus auto-enriched device context

#### Error Events

**`push_delivery_error`** - Issues during push delivery processing
- `error_type`: Type of error (see below)
- `error_message`: Description of the error
- `delivery_id`: (if available)

Error types:
- `empty_delivery_id`: Push received with missing/null deliveryId
- `duplicate_delivery_id`: Same push received multiple times

**`push_receive_error`** - Issues receiving push messages
- `error_type`: Type of error (see below)
- `error_message`: Description of the error

Error types:
- `empty_bundle`: Push message has no data payload
- `non_cio_push`: Push not from Customer.io (missing delivery credentials)
  - Includes: `has_delivery_id`, `has_delivery_token` booleans

**`push_click_error`** - Issues handling push notification clicks
- `error_type`: Type of error (see below)
- `error_message`: Description of the error
- `exception_type`: Exception class name (if applicable)

Error types:
- `missing_payload`: Notification clicked but payload missing
- `exception`: Unexpected error during click handling

**`push_image_load_error`** - Rich push image download failures
- `error_type`: `image_download_failed`
- `image_url`: URL that failed to load
- `error_message`: Failure reason
- `exception_type`: Exception class name

#### Example Diagnostic Event Payload

When a push is delivered, this event is automatically sent:

```json
{
  "name": "push_delivered",
  "timestamp": 1234567890,
  "level": "INFO",
  "data": {
    "delivery_id": "abc123",
    "device_token": "fcm-token-xyz",
    "sdk_version": "4.13.0",
    "device_os": "Android",
    "device_os_version": "34",
    "device_model": "Pixel 8",
    "device_manufacturer": "Google",
    "app_version": "2.1.0",
    "app_package": "com.example.app"
  }
}
```

### Manual Flush

```kotlin
// Manually trigger upload (normally happens with Analytics flush)
Diagnostics.flush()
```

### Alternative: Access via SDKComponent

You can still access via SDKComponent if needed:
```kotlin
import io.customer.sdk.core.di.SDKComponent

SDKComponent.diagnostics.record("event", data)  // Same thing
```

## How It Works

1. **Local Opt-in**: User enables via config (`diagnosticsEnabled(true)`)
2. **Instance Creation**: `DiagnosticsBridge` creates `DiagnosticsImpl` instance and registers it with SDKComponent using `SDKComponent.registerDiagnostics()`
3. **Server Control**: When server sends `sampleRate > 0`, diagnostics is activated
4. **Event Collection**: Events are stored locally with batching limits:
   - Max 100 events
   - Max 100KB file size
   - 24-hour TTL
5. **Upload**: Events uploaded to server when Analytics flushes or manually triggered

### Registration Flow

```kotlin
// DiagnosticsBridge creates instance
val diagnostics = DiagnosticsImpl(store, uploader, logger, scopeProvider)

// Registers with DiGraph (similar to setupAndroidComponent)
SDKComponent.registerDiagnostics(diagnostics)

// Now available globally via two ways:
Diagnostics.record("event", data)           // Convenience singleton
SDKComponent.diagnostics.record("event", data)  // Direct access
```

## Benefits

- **No boilerplate**: No need to inject dependencies or pass instances around
- **Type-safe**: Uses JsonObject for structured data
- **Automatic enrichment**: SDK version added to all events
- **Thread-safe**: Async file I/O with mutex protection, DiGraph singleton management
- **Smart batching**: Auto-prunes old events, enforces size limits
- **Zero impact when disabled**: No-op implementation when not active
- **Testable**: Can be overridden for tests using `SDKComponent.overrideDependency()`
- **Automatic cleanup**: Cleared on `SDKComponent.reset()` via DiGraph

## Architecture

```
┌─────────────────────────────────────────┐
│         Application Code                │
│   Diagnostics.record("event", data)     │
│   (object singleton - convenience)      │
│           ↓ delegates to                │
│   SDKComponent.diagnostics              │
└────────────┬────────────────────────────┘
             │
             ▼
┌─────────────────────────────────────────┐
│         SDKComponent (core)             │
│  ┌───────────────────────────────────┐  │
│  │ diagnostics: DiagnosticsRecorder  │  │
│  │ getOrNull<DiagnosticsRecorder>()  │  │
│  │ ?: NoOpDiagnostics                │  │
│  │                                   │  │
│  │ DiGraph's singletons map:         │  │
│  │ ["DiagnosticsRecorder"] -> impl   │  │
│  └───────────────────────────────────┘  │
└─────────────┬───────────────────────────┘
              │ registerDiagnostics()
              │ (extension in SDKComponentExt.kt)
              ▼
┌─────────────────────────────────────────┐
│   DiagnosticsBridge (datapipelines)     │
│  - Bridges Analytics ↔️ Diagnostics     │
│  - Monitors Analytics settings          │
│  - Creates DiagnosticsImpl instance     │
│  - Registers via registerDiagnostics()  │
│  - Enables/disables based on sampleRate │
└─────────────┬───────────────────────────┘
              │
              ▼
┌─────────────────────────────────────────┐
│   DiagnosticsImpl (core)                │
│  - Stores events (FileDiagnosticsStore) │
│  - Uploads events (via uploader)        │
│  - Enriches with SDK context            │
│  - Implements DiagnosticsRecorder       │
└─────────────────────────────────────────┘
```
