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
4. A small dialog shows the selected sentence and the actions Explain, Give Example, and Ask Follow-up. The original selection is left unchanged.

## Test SEND

1. In another app, share plain text (`text/plain`) and choose **Text Selection Probe**.
2. The same dialog shows the shared text and those three actions.

Logcat tag: `TextSelectionProbe`.

## Context capture

The probe can also keep about two or three visible sentences before and after a selection. That path is separate from reading `EXTRA_PROCESS_TEXT`. An accessibility service keeps the latest visible text of the foreground app that is not the probe, without requiring a selection range on the text node. `ACTION_PROCESS_TEXT` then brings this activity forward, so the probe finds the selected string in that stored text instead of reading the window after it is already in front. Events from the probe do not clear the last other-app snapshot. Nothing is written to disk or sent anywhere. If the surrounding sentences cannot be read, the screen says: `Selected text captured, surrounding context unavailable.` Selected text still appears when the accessibility service is off.

Enable it in Settings → Accessibility → Text Selection Probe → Context capture. On Android 13 and later, a sideloaded app may first need Settings → Apps → Text Selection Probe → allow restricted settings. Logcat tag: `ContextCapture`.

The debug fields are a `ContextPackage` from `ContextEngine`, not the raw accessibility snapshot. The engine trims and collapses whitespace, drops the selected sentence if it was copied into the surrounding text, drops chat turns labeled `You`, removes a leading `Assistant` label from text it keeps, and keeps at most three sentences on each side. Text with no speaker label is left as it is. It does not call a model or invent missing sentences. `CONTEXT QUALITY` is `HIGH` when both sides are meaningful, `MEDIUM` when only one side is, `LOW` when the surrounding text is extremely short, and `UNAVAILABLE` when no reliable surrounding text remains.

## Actions

Choosing Text Selection Probe opens a dialog-themed activity, not a system overlay. The dialog shows the selected sentence and Explain, Give Example, and Ask Follow-up. If surrounding context is missing, it also says: `Selected text captured, surrounding context unavailable.` The actions stay usable. Tapping one builds an `AIRequest` and opens a separate screen with that request only. Nothing is sent. Empty selected text does not create a request. There is no model provider yet.

## Capability test

Sample Chat keeps the mitochondria transcript and adds one assistant paragraph about retrieval augmented generation. Select this sentence from that paragraph:

`The system first retrieves relevant documents and then provides those documents to the model as context.`

With Context capture on, choose **Text Selection Probe**. Preceding context should include `Retrieval augmented generation combines language models with external knowledge sources.` Following context should include `This can help the model answer questions using information that was not contained in its original training data.` The probe discovers those sentences from the visible text. They are not hardcoded in the probe.

## Sample Chat on an emulator

`samplechat` is a second app in the same Gradle project (`dev.probe.samplechat`, launcher name **Sample Chat**). It shows a hardcoded transcript. The assistant energy note has this selectable sentence in the middle:

`The mitochondria converts nutrients into usable energy for the cell.`

From `android/`:

```
./gradlew :samplechat:assembleDebug :app:assembleDebug
```

Install both debug APKs on an API 33 or 34 emulator, open **Sample Chat**, long-press that sentence, and choose **Text Selection Probe** from the text selection toolbar. The probe should show path **Selected text**, action `android.intent.action.PROCESS_TEXT`, and that sentence.

On Android 11 and later, Sample Chat must declare a `<queries>` intent for `ACTION_PROCESS_TEXT` and `text/plain`. Without it, the selection toolbar omits Text Selection Probe even though Copy, Select all, and Read aloud still appear.

If the floating toolbar cannot be driven, this adb intent is only a secondary check. It is not the toolbar path:

```
adb shell am start -a android.intent.action.PROCESS_TEXT -t text/plain \
  --es android.intent.extra.PROCESS_TEXT "The mitochondria converts nutrients into usable energy for the cell." \
  -n dev.probe.textselection/.MainActivity
```
