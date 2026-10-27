# WidgetSmith AI

Android app that creates home-screen widgets from a plain-language description. Describe what you want, check the preview, and add it to the home screen.

## How it works
- **On-device first:** widget specs are generated with Gemini Nano through ML Kit GenAI where the phone supports it.
- **Claude fallback:** when on-device generation isn't available, the app calls the Anthropic API with the user's own API key, stored on the device.
- **Live data:** widgets can show battery level, dates and other values the app computes locally.
- **Offline:** the app keeps working without a network connection for widgets it has already generated.

## Build
Requires Android Studio, JDK 17+, and Android 8 or newer on the target device.

```bash
gradle :app:assembleDebug
```

The app has no signing key in the repo. Add your own for a release build.

## Structure
- `app/src/main/java/app/widgetsmith/`
  - `Ai.kt`: model calls (on-device and Claude fallback)
  - `Spec.kt`: the widget specification format and data sources
  - `Widget.kt`: rendering the widget on the home screen
  - `ConfigActivity.kt`, `MainActivity.kt`: setup and creation screens
  - `Preview.kt`, `Offline.kt`, `Store.kt`: previews, offline support and storage
