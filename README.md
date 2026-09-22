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

## Sample Chat on an emulator

`samplechat` is a second app in the same Gradle project (`dev.probe.samplechat`, launcher name **Sample Chat**). It shows a hardcoded transcript. The assistant energy note has this selectable sentence in the middle:

`The mitochondria converts nutrients into usable energy for the cell.`

From `android/`:

```
./gradlew :samplechat:assembleDebug :app:assembleDebug
```

Install both debug APKs on an API 33 or 34 emulator, open **Sample Chat**, long-press that sentence, and choose **Text Selection Probe** from the text selection toolbar. The probe should show path **Selected text**, action `android.intent.action.PROCESS_TEXT`, and that sentence.

If the floating toolbar cannot be driven, this adb intent is only a secondary check. It is not the toolbar path:

```
adb shell am start -a android.intent.action.PROCESS_TEXT -t text/plain \
  --es android.intent.extra.PROCESS_TEXT "The mitochondria converts nutrients into usable energy for the cell." \
  -n dev.probe.textselection/.MainActivity
```
