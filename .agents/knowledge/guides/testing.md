---
covers: ['*/src/test/*', '*/src/androidTest/*', 'common-test/**']
---
# Testing

- Write both JVM unit tests and Android instrumentation tests
- Use `UnitTest` base class for standard unit tests
- Use `AndroidTest` base class for instrumentation tests
- Use `RobolectricTest` for Android framework testing without device
- Mock dependencies using MockK framework
- Use test doubles and stubs from `common-test` module
- Follow AAA pattern (Arrange, Act, Assert)
- Name tests with `testMethodName_whenCondition_thenExpectedResult` format

### Running All Tests
```bash
./gradlew test
```

### Running Tests for a Specific Module
```bash
./gradlew :core:test
./gradlew :datapipelines:test
```

### Running a Specific Test Class
```bash
./gradlew :core:test --tests "EventBusTest"
```

### Running Android Instrumentation Tests
```bash
./gradlew connectedAndroidTest
```

### Test Categories
- **Unit Tests**: Test individual components in isolation using JVM
- **Integration Tests**: Test module interactions and SDK behavior
- **Android Tests**: Test Android-specific functionality on device/emulator
- **Robolectric Tests**: Test Android components in JVM environment
