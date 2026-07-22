# Jarvis Android Assistant

Private, sideloadable Android app with package name `com.jarvis.assistant`.

## Headless build on Debian/Ubuntu

```bash
git clone https://github.com/MoinsenWerner/jarvis-try-chatgpt.git
cd jarvis-try-chatgpt
chmod +x scripts/setup_build.sh
./scripts/setup_build.sh
```

The script installs OpenJDK 17, Android command-line tools, API/build-tools 35, Gradle 8.11.1, validates the local training dataset with an 80% minimum and builds:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Install with `adb install -r app/build/outputs/apk/debug/app-debug.apk`.

## Included

- Jetpack Compose dashboard, settings, local chat and detail screens.
- Package `com.jarvis.assistant`.
- Exported Tasker receivers: `receiveNewCommand`, `receiveNewReminder`, `receiveNewData`, `receiveNewVideo`, `receiveNewAudio`.
- Optional shared Tasker token.
- Accessibility-based reading of explicitly selected apps.
- Foreground microphone service for `Hey Jarvis`.
- MediaProjection consent flow and foreground capture service foundation.
- Local JSONL data store and `files/logs/actions.log` for every processed action.
- App selection, user name and model-mode settings.
- Lightweight local classifier and reproducible synthetic German dataset/training pipeline.
- GitHub Actions APK build.

## Tasker example

```bash
adb shell am broadcast -n com.jarvis.assistant/.receiveNewCommand \
  --es instruction "Erinnere mich morgen um 18 Uhr an den Einkauf"

adb shell am broadcast -n com.jarvis.assistant/.receiveNewAudio \
  --es instruction "Fasse die Sprachnachricht zusammen" \
  --es address "file:///storage/emulated/0/Download/message.m4a"
```

Instruction aliases: `instruction`, `command`, `data`, `text`, `extra`. Media address aliases: `address`, `uri`, `url`, `file`.

## Hard Android limits

Sideloading does not bypass Android security. Accessibility must be enabled manually. MediaProjection always requires user consent and a visible foreground notification. Continuous `SpeechRecognizer` operation is best-effort and OEM-dependent. Playback capture works only when the source app permits it. A client-side OpenAI key can be extracted on a compromised device. Standard Ollama is an HTTP server and is not embedded into this APK.

The included local model is a compact command/action classifier, not a full language model. The synthetic validation score is not equivalent to real-world accuracy. Destructive generic automation is intentionally not enabled by default.
