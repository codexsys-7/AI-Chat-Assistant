# Text Selection Probe

Minimal Android app that shows plain text received from the text-selection toolbar or from a share intent.

## Build

From `android/`:

```
./gradlew assembleDebug
```

## Test PROCESS_TEXT

1. Install the debug APK.
2. In another app, select plain text.
3. From the text selection toolbar, choose **Text Selection Probe**.
4. The screen should show path **Selected text**, action `android.intent.action.PROCESS_TEXT`, MIME type `text/plain`, and the selected text. The original selection is left unchanged.

## Test SEND

1. In another app, share plain text (`text/plain`) and choose **Text Selection Probe**.
2. The screen should show path **Shared text**, action `android.intent.action.SEND`, MIME type `text/plain`, and the shared text.

Logcat tag: `TextSelectionProbe`.
