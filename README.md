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
4. A small dialog offers Explain, Give Example, and Ask Follow-up. The selected sentence is not shown there. The original selection is left unchanged.

## Test SEND

1. In another app, share plain text (`text/plain`) and choose **Text Selection Probe**.
2. The same dialog offers those three actions. The shared text is not shown there.

Logcat tag: `TextSelectionProbe`.

## Context capture

The probe can also keep about two or three visible sentences before and after a selection. That path is separate from reading `EXTRA_PROCESS_TEXT`. An accessibility service keeps the latest visible text of the foreground app that is not the probe, without requiring a selection range on the text node. `ACTION_PROCESS_TEXT` then brings this activity forward, so the probe finds the selected string in that stored text instead of reading the window after it is already in front. Events from the probe do not clear the last other-app snapshot. Nothing is written to disk or sent anywhere. If the surrounding sentences cannot be read, the screen says: `Selected text captured, surrounding context unavailable.` Selected text still appears when the accessibility service is off.

Enable it in Settings → Accessibility → Text Selection Probe → Context capture. On Android 13 and later, a sideloaded app may first need Settings → Apps → Text Selection Probe → allow restricted settings. Logcat tag: `ContextCapture`.

The debug fields are a `ContextPackage` from `ContextEngine`, not the raw accessibility snapshot. The engine trims and collapses whitespace, drops the selected sentence if it was copied into the surrounding text, drops chat turns labeled `You`, removes a leading `Assistant` label from text it keeps, and keeps at most three sentences on each side. Text with no speaker label is left as it is. It does not call a model or invent missing sentences. `CONTEXT QUALITY` is `HIGH` when both sides are meaningful, `MEDIUM` when only one side is, `LOW` when the surrounding text is extremely short, and `UNAVAILABLE` when no reliable surrounding text remains.

## Actions

Choosing Text Selection Probe opens a dialog-themed activity, not a system overlay. The dialog says what the actions are for, then shows Explain, Give Example, and Ask Follow-up. It does not show the selected sentence. If surrounding context is missing, it also says: `Selected text captured, surrounding context unavailable.` The actions stay usable. Tapping one builds an `AIRequest`, shows `Preparing response...` for a moment, then a response. The default build uses the local `MockAIProvider` (`mock-v1`) and sends nothing. A debug build can instead use `RemoteAIProvider`, which posts to the backend below. The response names the action, provider, model, both ids, and status. It does not repeat the request fields. **Request debug** on that screen opens the earlier request view. Empty selected text does not create a request.

## Remote provider

`MockAIProvider` stays the default. Set the Gradle property `ai.provider` to `REMOTE` to use `RemoteAIProvider`. That switch is build configuration, not a settings screen. Mock mode does not open a network connection.

From `android/`:

```
./gradlew :app:assembleDebug -Pai.provider=REMOTE -Pai.backend.url=http://10.0.2.2:8080
```

`10.0.2.2` is the emulator's name for a backend on the host. The same keys can live in `android/local.properties`, which is gitignored. Any value other than `REMOTE` keeps the mock provider.

The app talks only to that backend. It does not call OpenAI, and the APK does not contain a provider API key. The key belongs in the environment or in `backend/.env`.

```
cp backend/.env.example backend/.env
```

`backend/.env.example` contains `OPENAI_API_KEY=your_key_here`. Replace the placeholder locally. Do not commit `backend/.env`.

From `android/`:

```
./gradlew --no-daemon :backend:run
```

The process reads `OPENAI_API_KEY`, listens on `0.0.0.0` and `PORT` (default `8080`), and calls OpenAI with `OPENAI_MODEL` (default `gpt-4o-mini`). A missing key stays a controlled error. The server has no accounts, database, or history.

`POST /v1/complete`

Request:

```json
{
  "requestId": "request-1",
  "action": "EXPLAIN",
  "selectedText": "Cells store fuel.",
  "precedingText": "A cell still has to turn stored fuel into usable energy.",
  "followingText": null,
  "contextQuality": "HIGH",
  "prompt": {
    "systemInstruction": "Explain the passage using the available context.",
    "userContent": "Action: EXPLAIN\nContext quality: HIGH\nPreceding context: ...\nFollowing context: ...\nSelected text: Cells store fuel."
  }
}
```

`action` is `EXPLAIN`, `EXAMPLE`, or `FOLLOW_UP`. `prompt` is the existing `PromptEngine` result. The body does not include the conversation, a device id, the source package, or analytics. `precedingText` and `followingText` are null when that side was not captured. When context quality is `UNAVAILABLE`, the prompt says only the selected text is available and does not invent surrounding sentences.

Response:

```json
{
  "requestId": "request-1",
  "responseId": "response-1",
  "content": "...",
  "provider": "OpenAI",
  "model": "gpt-4o-mini",
  "status": "SUCCESS"
}
```

`status` is `SUCCESS` or `ERROR`. Failures use the same shape: a short user-facing message, a `Debug:` line, and no upstream error body. The app checks the body before it builds the existing `AIResponse`. The screen still lists Action, Response, Provider, Model, Request id, Response id, and Status. A real success shows the provider and model from this JSON. The screen header still says `MOCK`; that label was left in place with the rest of the response UI.

On an emulator, install the `REMOTE` debug APK, start the backend with `OPENAI_API_KEY` set, open Sample Chat, select a sentence, and choose Explain, Give Example, or Ask Follow-up. The screen should keep `Preparing response...` until the call finishes, then show the model text or a controlled error.

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
