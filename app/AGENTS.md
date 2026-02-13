# TV Alarm Clock Agent Instructions

## App Architecture

This is a single-activity TV app built entirely with Jetpack Compose. The architecture is simple and follows a feature-based package structure. There are two main screens:

- `HomeScreen`: Displays the list of alarms and allows the user to add, delete, and toggle alarms.
- `ContentPickerScreen`: Allows the user to select a streaming app and content to be launched by the alarm.

The app uses `AlarmRepository` and `ContentRepository` to persist data to `SharedPreferences`. The `AlarmScheduler` uses `AlarmManager` to schedule the alarms. The `StreamingLauncher` uses `PackageManager` and deep links to launch the streaming apps.

## Coding Style

- **Compose:** Follow the official [Jetpack Compose API Guidelines](https://developer.android.com/jetpack/compose/api-guidelines).
- **Kotlin:** Follow the official [Kotlin Coding Conventions](https://kotlinlang.org/docs/coding-conventions.html).
- **ViewModel:** Use ViewModels to hold the state of the Composables and to handle the business logic.
- **Immutability:** Use immutable data classes for model objects.
- **State:** Use `mutableStateOf` to manage the state of the Composables. Lift the state to the nearest common ancestor.

## App Goals

The primary goal of this app is to provide a simple and reliable way to wake up to your favorite streaming content on your TV. The app should be easy to use and should support a wide range of streaming apps.

When making changes, please consider the following:

- **Simplicity:** Keep the UI and the codebase as simple as possible.
- **Reliability:** Ensure that the alarms are scheduled correctly and that the streaming apps are launched reliably.
- **Extensibility:** Make it easy to add support for new streaming apps.
- **TV First:** This is a TV app, so all UI and interactions should be optimized for a TV remote.
