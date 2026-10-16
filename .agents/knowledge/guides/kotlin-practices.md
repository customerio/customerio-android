---
covers: ['*/src/main/*']
---
# Kotlin and Android practices

## Code Style
- **Kotlin 2.1.21** with object-oriented and functional programming patterns
- Naming: PascalCase for classes, camelCase for properties/methods/variables
- Modular architecture (Core, DataPipelines, MessagingPush, MessagingInApp, etc.)
- Error handling: prefer sealed classes for typed errors, use Result types when appropriate
- Avoid non-null assertions (!!) except in tests where values are guaranteed
- Use explicit null checks and safe calls (?.) for nullable types
- Keep classes small and with single responsibility
- Keep methods focused and well-named

## Android-Specific Considerations
- Always check API level compatibility when using newer Android features
- Use AndroidX libraries instead of deprecated support libraries
- Handle Android lifecycle properly (Activities, Fragments, Services)
- Consider memory implications of long-running operations
- Use appropriate threading (Main thread for UI, background for network/disk)
- Follow Android security best practices (no hardcoded secrets, proper permissions)

## Memory and Performance
- Use appropriate collection types (List vs Array, mutable vs immutable)
- Avoid memory leaks by properly managing listeners and callbacks
- Use weak references for callbacks that reference Activities/Contexts
- Consider object pooling for frequently created objects
- Profile memory usage during development
- Use ProGuard/R8 rules appropriately for release builds
- Monitor SDK binary size impact

## Threading and Concurrency
- Use Kotlin coroutines for asynchronous operations
- Prefer structured concurrency over raw threads
- Always specify appropriate Dispatchers (Main, IO, Default)
- Handle cancellation properly in coroutines
- Use thread-safe data structures when sharing state
- Document threading requirements in public APIs
- Test concurrent code thoroughly

## Debugging and Troubleshooting
- Enable verbose logging in debug builds
- Use Android Studio debugger for step-through debugging
- Check logcat for SDK-specific log messages
- Use `adb` commands for device debugging
- Monitor network traffic for API calls
- Use memory profiler for performance analysis
